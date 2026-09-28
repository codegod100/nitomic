;; Durable storage in SQLite, for datomic:sql://<name>?jdbc:sqlite:<path>.
;;
;; A storage file holds any number of databases, like a Datomic SQL
;; storage. Each committed transaction is one row of the log: the datoms it
;; produced and the id counters after it, as EDN. A connection rebuilds its
;; database by replaying those rows onto the bootstrap database
;; (nitomic.db/apply-tx-record); the transaction logic never runs twice, so
;; replay gives back exactly the ids and values the transaction produced.
;;
;; Writers serialize on SQLite's write lock (BEGIN IMMEDIATE): a writer
;; first applies the rows other processes committed, then transacts against
;; that database and appends its own row before releasing the lock. That
;; makes every process its own transactor, one at a time.
(ns nitomic.storage
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clonim.sqlite :as sql]
            [nitomic.db :as ndb]))

(def ^:private format-version "1")

(def ^:private schema
  "create table if not exists nitomic_meta (
     key text primary key, value text not null);
   create table if not exists nitomic_databases (
     name text primary key);
   create table if not exists nitomic_log (
     db text not null, t integer not null, record text not null,
     primary key (db, t))")

(defn- open-store [path]
  (let [h (sql/open path)]
    ;; readers keep reading while a writer commits
    (sql/query h "PRAGMA journal_mode=WAL")
    (sql/execute! h schema)
    (sql/execute! h "insert or ignore into nitomic_meta (key, value) values ('format', ?)"
                  [format-version])
    (let [v (:value (first (sql/query h "select value from nitomic_meta where key = 'format'")))]
      (when-not (= v format-version)
        (sql/close h)
        (throw (ex-info (str "Unsupported nitomic storage format " v " in " path)
                        {:db/error :db.error/unsupported-storage-format}))))
    {:path path :handle h}))

(def ^:private stores (atom {}))

(defn store
  "The storage at path, opened (and created) on first use."
  [path]
  (or (get @stores path)
      (let [s (open-store path)]
        (swap! stores assoc path s)
        s)))

;; ------------------------------------------------------------ catalog
(defn exists? [{h :handle} name]
  (seq (sql/query h "select 1 as x from nitomic_databases where name = ?" [name])))

(defn create!
  "True if the database was created, false if it already existed."
  [{h :handle} name]
  (pos? (:changes (sql/execute! h "insert or ignore into nitomic_databases (name) values (?)"
                                [name]))))

(defn delete! [{h :handle} name]
  (sql/transaction h
    (fn []
      (sql/execute! h "delete from nitomic_log where db = ?" [name])
      (sql/execute! h "delete from nitomic_databases where name = ?" [name])))
  true)

(defn rename!
  "True if name existed and now goes by new-name."
  [{h :handle :as s} name new-name]
  (sql/transaction h
    (fn []
      (if (and (exists? s name) (not (exists? s new-name)))
        (do
          (sql/execute! h "update nitomic_databases set name = ? where name = ?" [new-name name])
          (sql/execute! h "update nitomic_log set db = ? where db = ?" [new-name name])
          true)
        false))))

(defn names [{h :handle}]
  (mapv :name (sql/query h "select name from nitomic_databases order by name")))

;; ------------------------------------------------------------ the log
(defn records-after
  "The logged transactions of a database with t greater than t, in order."
  [{h :handle} name t]
  (mapv (fn [row] (edn/read-string (:record row)))
        (sql/query h "select record from nitomic_log where db = ? and t > ? order by t"
                   [name t])))

(defn load-db
  "A database rebuilt from its log."
  [s name]
  (reduce ndb/apply-tx-record (ndb/new-db name) (records-after s name -1)))

(defn- check-storable [datoms]
  (doseq [[e a v] datoms]
    (when (fn? v)
      (throw (ex-info (str "Cannot store a fn in durable storage: datom "
                           (pr-str [e a]) " (transaction functions need datomic:mem)")
                      {:db/error :db.error/not-storable :e e :a a})))))

(defn append!
  "Record the transaction that produced db-after. Call inside with-write-lock."
  [{h :handle} name db-after]
  (let [{:keys [t tx inst data]} (peek (:log db-after))]
    (check-storable data)
    (sql/execute! h "insert into nitomic_log (db, t, record) values (?, ?, ?)"
                  [name t (pr-str {:t t :tx tx :inst inst :data data
                                   :next-t (:next-t db-after)
                                   :next-db-id (:next-db-id db-after)})])))

(defn with-write-lock
  "Call (f) holding the storage's write lock, committing what it wrote if it
  returns and rolling it back if it throws."
  [{h :handle} f]
  (sql/transaction h f))

;; ------------------------------------------------------------ URIs
(defn parse-uri
  "{:protocol :name :path} of datomic:sql://<name>?jdbc:sqlite:<path>."
  [uri]
  (let [rest-uri (subs uri (+ 3 (str/index-of uri "://")))
        q (str/index-of rest-uri "?")
        jdbc (when q (subs rest-uri (inc q)))]
    (when-not (and jdbc (str/starts-with? jdbc "jdbc:sqlite:"))
      (throw (ex-info (str "nitomic's sql storage is SQLite: expected "
                           "datomic:sql://<name>?jdbc:sqlite:<path>, got " uri)
                      {:db/error :db.error/invalid-db-uri})))
    {:protocol "sql"
     :name (subs rest-uri 0 q)
     :path (subs jdbc (count "jdbc:sqlite:"))}))
