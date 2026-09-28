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
;; that database and appends its own row before releasing the lock. Without a
;; transactor running, that makes every process its own transactor, one at a
;; time. With one running (nitomic.transactor, which keeps a heartbeat in
;; nitomic_meta), peers put their transaction data on nitomic_queue instead
;; and the transactor alone writes the log.
(ns nitomic.storage
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clonim.sqlite :as sql]
            [nitomic.db :as ndb]
            [nitomic.types :as types]))

(def ^:private format-version "1")

(def ^:private schema
  "create table if not exists nitomic_meta (
     key text primary key, value text not null);
   create table if not exists nitomic_databases (
     name text primary key);
   create table if not exists nitomic_log (
     db text not null, t integer not null, record text not null,
     primary key (db, t));
   create table if not exists nitomic_queue (
     id integer primary key autoincrement, db text not null,
     tx_data text not null, status text not null default 'pending',
     result text)")

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

(defn report-at
  "The transaction report of the logged transaction t, rebuilt from the log."
  [s name t]
  (let [recs (records-after s name -1)
        before (reduce ndb/apply-tx-record (ndb/new-db name)
                       (take-while #(< (:t %) t) recs))
        rec (first (filter #(= (:t %) t) recs))]
    (when rec
      {:db-before before
       :db-after (ndb/apply-tx-record before rec)
       :tx-data (mapv types/datom (:data rec))
       :tempids (:tempids rec {})})))

(defn- check-storable [datoms]
  (doseq [[e a v] datoms]
    (when (fn? v)
      (throw (ex-info (str "Cannot store a fn in durable storage: datom "
                           (pr-str [e a]) " (transaction functions need datomic:mem)")
                      {:db/error :db.error/not-storable :e e :a a})))))

(defn append!
  "Record a transaction from its report. Call inside with-write-lock."
  [{h :handle} name {:keys [db-after tempids]}]
  (let [{:keys [t tx inst data]} (peek (:log db-after))]
    (check-storable data)
    (sql/execute! h "insert into nitomic_log (db, t, record) values (?, ?, ?)"
                  [name t (pr-str {:t t :tx tx :inst inst :data data
                                   :tempids tempids
                                   :next-t (:next-t db-after)
                                   :next-db-id (:next-db-id db-after)})])))

(defn with-write-lock
  "Call (f) holding the storage's write lock, committing what it wrote if it
  returns and rolling it back if it throws."
  [{h :handle} f]
  (sql/transaction h f))

;; ------------------------------------------------------------ transactor
(def heartbeat-ms
  "How often a transactor records that it is alive. Peers treat one as gone
  after three missed heartbeats."
  1000)

(defn- now-ms [] (System/currentTimeMillis))

(defn transactor
  "{:id :at} of the transactor that last recorded a heartbeat, or nil."
  [{h :handle}]
  (when-let [v (:value (first (sql/query h "select value from nitomic_meta where key = 'transactor'")))]
    (let [[id at] (str/split v " ")]
      {:id id :at (Long/parseLong at)})))

(defn transactor-alive?
  "True while some transactor's heartbeat is fresh."
  [s]
  (let [tr (transactor s)]
    (boolean (and tr (< (- (now-ms) (:at tr)) (* 3 heartbeat-ms))))))

(defn heartbeat! [{h :handle} id]
  (sql/execute! h "insert or replace into nitomic_meta (key, value) values ('transactor', ?)"
                [(str id " " (now-ms))]))

(defn clear-heartbeat! [{h :handle} id]
  (sql/execute! h "delete from nitomic_meta where key = 'transactor' and value like ?"
                [(str id " %")]))

;; ------------------------------------------------------------ the queue
;; Transaction data crosses processes as EDN. Tempids and datoms have no EDN
;; form, so they travel as {:nitomic/tempid [part idx]} and as the
;; :db/add/:db/retract list a datom stands for.
(defn- encode [x]
  (cond
    (types/tempid? x) {:nitomic/tempid [(:part x) (:idx x)]}
    (types/datom? x) [(if (:added x) :db/add :db/retract) (:e x) (:a x) (:v x)]
    (fn? x) (throw (ex-info "Cannot send a fn to the transactor"
                            {:db/error :db.error/not-storable}))
    (map? x) (into {} (map (fn [[k v]] [(encode k) (encode v)]) x))
    (set? x) (into #{} (map encode x))
    (sequential? x) (mapv encode x)
    :else x))

(defn- decode [x]
  (cond
    (map? x) (if-let [[part idx] (:nitomic/tempid x)]
               (types/tempid part idx)
               (into {} (map (fn [[k v]] [(decode k) (decode v)]) x)))
    (set? x) (into #{} (map decode x))
    (sequential? x) (mapv decode x)
    :else x))

(defn plain
  "x as data any peer can read back: datoms become [e a v tx added] and
  anything else without an EDN form becomes its string."
  [x]
  (cond
    (types/tempid? x) (encode x)
    (types/datom? x) [(:e x) (:a x) (:v x) (:tx x) (:added x)]
    (map? x) (into {} (map (fn [[k v]] [(plain k) (plain v)]) x))
    (set? x) (into #{} (map plain x))
    (sequential? x) (mapv plain x)
    (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)
        (inst? x) (uuid? x)) x
    :else (str x)))

(defn enqueue!
  "Queue transaction data for the transactor; returns the queue id."
  [{h :handle} name tx-data]
  (:last-insert-rowid
   (sql/execute! h "insert into nitomic_queue (db, tx_data) values (?, ?)"
                 [name (pr-str (encode tx-data))])))

(defn pending
  "Queued transactions not yet processed, oldest first: [{:id :db :tx-data}]."
  [{h :handle} limit]
  (mapv (fn [r] {:id (:id r) :db (:db r) :tx-data (decode (edn/read-string (:tx_data r)))})
        (sql/query h "select id, db, tx_data from nitomic_queue where status = 'pending'
                      order by id limit ?" [limit])))

(defn still-pending? [{h :handle} id]
  (seq (sql/query h "select 1 as x from nitomic_queue where id = ? and status = 'pending'" [id])))

(defn finish!
  "Record the outcome of a queued transaction: :done with {:t n}, or :failed
  with {:message :data}. Call inside with-write-lock."
  [{h :handle} id status result]
  (sql/execute! h "update nitomic_queue set status = ?, result = ? where id = ?"
                [(name status) (pr-str result) id]))

(defn queue-result
  "{:status :done|:failed :result ...} once the transactor has processed a
  queued transaction, nil while it waits."
  [{h :handle} id]
  (let [r (first (sql/query h "select status, result from nitomic_queue where id = ?" [id]))]
    (when (and r (not= "pending" (:status r)))
      {:status (keyword (:status r))
       :result (when (:result r) (edn/read-string (:result r)))})))

(defn cancel!
  "Withdraw a queued transaction the transactor hasn't taken. True if it was
  withdrawn, false if it had already been processed."
  [{h :handle} id]
  (pos? (:changes (sql/execute! h "delete from nitomic_queue where id = ? and status = 'pending'"
                                [id]))))

(defn forget! [{h :handle} id]
  (sql/execute! h "delete from nitomic_queue where id = ?" [id]))

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
