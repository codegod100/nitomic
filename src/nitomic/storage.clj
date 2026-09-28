;; Durable storage in SQLite or PostgreSQL, for
;; datomic:sql://<name>?jdbc:sqlite:<path> and
;; datomic:sql://<name>?jdbc:postgresql://<host>/<db>?user=...&password=...
;;
;; A storage holds any number of databases, like a Datomic SQL storage.
;; Each committed transaction is one row of the log: the datoms it produced,
;; its tempids and the id counters after it, as EDN. A connection rebuilds
;; its database by replaying those rows onto the bootstrap database
;; (nitomic.db/apply-tx-record); the transaction logic never runs twice, so
;; replay gives back exactly the ids and values the transaction produced.
;;
;; Writers serialize on the storage's write lock (SQLite's BEGIN IMMEDIATE,
;; or a PostgreSQL transaction-scoped advisory lock): a writer first applies
;; the rows others committed, then transacts against that database and
;; appends its own row before releasing the lock. Without a transactor
;; running, that makes every process its own transactor, one at a time.
;; With one running (nitomic.transactor), peers put their transaction data
;; on nitomic_queue instead and the transactor alone writes the log.
;;
;; PostgreSQL also pushes: every commit, queued transaction and result
;; sends a NOTIFY on the nitomic channel, so waiting (wait!) returns as soon
;; as something happens. SQLite has no such channel; there, wait! sleeps
;; briefly and callers look again.
(ns nitomic.storage
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clonim.postgres :as pg]
            [clonim.sqlite :as sqlite]
            [nitomic.db :as ndb]
            [nitomic.types :as types]))

(def ^:private format-version "1")

(def ^:private schemas
  {:sqlite
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
      result text)"
   :postgres
   "create table if not exists nitomic_meta (
      key text primary key, value text not null);
    create table if not exists nitomic_databases (
      name text primary key);
    create table if not exists nitomic_log (
      db text not null, t bigint not null, record text not null,
      primary key (db, t));
    create table if not exists nitomic_queue (
      id bigserial primary key, db text not null,
      tx_data text not null, status text not null default 'pending',
      result text)"})

;; PostgreSQL advisory locks are named by two ints: this one, then 1 for
;; the transactor's claim and 2 for the write lock.
(def ^:private lock-space 1852404845)
(def ^:private channel "nitomic")

;; ------------------------------------------------------------ backends
(defn- dollar-params
  "SQL written with ? placeholders, numbered $1, $2, ... for PostgreSQL."
  [sql]
  (let [parts (str/split sql "?" -1)]
    (apply str (first parts)
           (map-indexed (fn [i part] (str "$" (inc i) part)) (rest parts)))))

(defn- query [{:keys [kind handle]} sql params]
  (if (= kind :postgres)
    (pg/query handle (dollar-params sql) params)
    (sqlite/query handle sql params)))

(defn- execute! [{:keys [kind handle]} sql params]
  (if (= kind :postgres)
    (pg/execute! handle (dollar-params sql) params)
    (sqlite/execute! handle sql params)))

(defn- notify! [{:keys [kind] :as s} payload]
  (when (= kind :postgres)
    (query s "select pg_notify(?, ?) as sent" [channel payload])))

(defn with-write-lock
  "Call (f) holding the storage's write lock, committing what it wrote if it
  returns and rolling it back if it throws."
  [{:keys [kind handle] :as s} f]
  (if (= kind :postgres)
    (pg/transaction handle
      (fn []
        (query s (str "select pg_advisory_xact_lock(" lock-space ", 2) as locked") nil)
        (f)))
    (sqlite/transaction handle f)))

(defn- transaction
  "Call (f) in a transaction, without the write lock."
  [{:keys [kind handle]} f]
  (if (= kind :postgres)
    (pg/transaction handle f)
    (sqlite/transaction handle f)))

(defn wait!
  "Wait until another process may have changed the storage, or timeout-ms
  passes. PostgreSQL returns as soon as a NOTIFY arrives; SQLite, which
  can't be told, sleeps a moment."
  [{:keys [kind handle]} timeout-ms]
  (if (= kind :postgres)
    (pg/notifications handle timeout-ms)
    (Thread/sleep (min timeout-ms 2)))
  nil)

(defn push?
  "True if this storage tells waiting processes about changes."
  [s]
  (= :postgres (:kind s)))

(defn- drain!
  "Drop notifications that arrived while nobody was waiting."
  [{:keys [kind handle]}]
  (when (= kind :postgres) (pg/notifications handle 0)))

;; ------------------------------------------------------------ opening
(defn- location
  "{:kind :conninfo} of a storage given as a JDBC URL (jdbc:sqlite:<path>
  or jdbc:postgresql://...) or as a bare SQLite path."
  [spec]
  (cond
    (str/starts-with? spec "jdbc:sqlite:")
    {:kind :sqlite :conninfo (subs spec (count "jdbc:sqlite:"))}
    (str/starts-with? spec "jdbc:postgresql:")
    {:kind :postgres :conninfo (subs spec (count "jdbc:"))}
    (str/starts-with? spec "jdbc:")
    (throw (ex-info (str "nitomic's sql storage is SQLite or PostgreSQL, not " spec)
                    {:db/error :db.error/invalid-db-uri}))
    :else {:kind :sqlite :conninfo spec}))

(defn connect-store
  "A new connection to the storage named by spec (see location), creating
  its tables on first use. Most callers want the shared one from store."
  [spec]
  (let [{:keys [kind conninfo]} (location spec)
        handle (if (= kind :postgres) (pg/connect conninfo) (sqlite/open conninfo))
        s {:kind kind :path spec :handle handle}]
    (if (= kind :postgres)
      (do
        ;; one creator at a time: concurrent CREATE TABLE IF NOT EXISTS races
        (with-write-lock s (fn [] (pg/execute! handle (schemas :postgres))))
        (pg/listen handle channel))
      (do
        ;; readers keep reading while a writer commits
        (sqlite/query handle "PRAGMA journal_mode=WAL")
        (sqlite/execute! handle (schemas :sqlite))))
    (execute! s "insert into nitomic_meta (key, value) values ('format', ?) on conflict do nothing"
              [format-version])
    (let [v (:value (first (query s "select value from nitomic_meta where key = 'format'" nil)))]
      (when-not (= v format-version)
        (throw (ex-info (str "Unsupported nitomic storage format " v " in " spec)
                        {:db/error :db.error/unsupported-storage-format}))))
    s))

(defn close-store [{:keys [kind handle]}]
  (if (= kind :postgres) (pg/close handle) (sqlite/close handle)))

(def ^:private stores (atom {}))

(defn store
  "The shared connection to the storage named by spec, opened on first use."
  [spec]
  (or (get @stores spec)
      (let [s (connect-store spec)]
        (swap! stores assoc spec s)
        s)))

;; ------------------------------------------------------------ catalog
(defn exists? [s name]
  (seq (query s "select 1 as x from nitomic_databases where name = ?" [name])))

(defn create!
  "True if the database was created, false if it already existed."
  [s name]
  (pos? (:changes (execute! s "insert into nitomic_databases (name) values (?) on conflict do nothing"
                            [name]))))

(defn delete! [s name]
  (with-write-lock s
    (fn []
      (execute! s "delete from nitomic_log where db = ?" [name])
      (execute! s "delete from nitomic_queue where db = ?" [name])
      (execute! s "delete from nitomic_databases where name = ?" [name])))
  true)

(defn rename!
  "True if name existed and now goes by new-name."
  [s name new-name]
  (with-write-lock s
    (fn []
      (if (and (exists? s name) (not (exists? s new-name)))
        (do
          (execute! s "update nitomic_databases set name = ? where name = ?" [new-name name])
          (execute! s "update nitomic_log set db = ? where db = ?" [new-name name])
          true)
        false))))

(defn names [s]
  (mapv :name (query s "select name from nitomic_databases order by name" nil)))

;; ------------------------------------------------------------ the log
(defn records-after
  "The logged transactions of a database with t greater than t, in order."
  [s name t]
  (drain! s)
  (mapv (fn [row] (edn/read-string (:record row)))
        (query s "select record from nitomic_log where db = ? and t > ? order by t"
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
  [s name {:keys [db-after tempids]}]
  (let [{:keys [t tx inst data]} (peek (:log db-after))]
    (check-storable data)
    (execute! s "insert into nitomic_log (db, t, record) values (?, ?, ?)"
              [name t (pr-str {:t t :tx tx :inst inst :data data
                               :tempids tempids
                               :next-t (:next-t db-after)
                               :next-db-id (:next-db-id db-after)})])
    ;; delivered when the transaction commits
    (notify! s (str "log " t))))

;; ------------------------------------------------------------ transactor
;; A transactor claims a storage for as long as it runs. On PostgreSQL the
;; claim is a session advisory lock, which the server drops the moment the
;; transactor's connection goes away. SQLite has nothing like it, so there
;; the transactor records a heartbeat every heartbeat-ms and counts as gone
;; after three missed ones.
(def heartbeat-ms 1000)

(defn- now-ms [] (System/currentTimeMillis))

(defn- heartbeat [s]
  (when-let [v (:value (first (query s "select value from nitomic_meta where key = 'transactor'" nil)))]
    (let [[id at] (str/split v " ")]
      {:id id :at (Long/parseLong at)})))

(defn transactor-alive?
  "True while a transactor has the storage."
  [s]
  (if (push? s)
    (boolean (seq (query s (str "select 1 as x from pg_locks
                                 where locktype = 'advisory' and granted
                                   and classid = " lock-space " and objid = 1 and objsubid = 2
                                   and database = (select oid from pg_database
                                                   where datname = current_database())")
                         nil)))
    (let [hb (heartbeat s)]
      (boolean (and hb (< (- (now-ms) (:at hb)) (* 3 heartbeat-ms)))))))

(defn heartbeat! [s id]
  (when-not (push? s)
    (execute! s "insert into nitomic_meta (key, value) values ('transactor', ?)
                 on conflict (key) do update set value = excluded.value"
              [(str id " " (now-ms))])))

(defn claim!
  "Make this connection the storage's transactor; throws if another one
  has it."
  [s id]
  (let [taken (fn []
                (throw (ex-info "Another transactor is running on this storage"
                                {:db/error :db.error/transactor-running})))]
    (if (push? s)
      (when-not (:claimed (first (query s (str "select pg_try_advisory_lock(" lock-space
                                               ", 1) as claimed") nil)))
        (taken))
      (with-write-lock s
        (fn []
          (when (transactor-alive? s) (taken))
          (heartbeat! s id))))))

(defn release!
  "Give up the claim made with claim!."
  [s id]
  (if (push? s)
    (query s (str "select pg_advisory_unlock(" lock-space ", 1) as released") nil)
    (execute! s "delete from nitomic_meta where key = 'transactor' and value like ?"
              [(str id " %")])))

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
  [s name tx-data]
  (let [id (:id (first (query s "insert into nitomic_queue (db, tx_data) values (?, ?) returning id"
                              [name (pr-str (encode tx-data))])))]
    (notify! s (str "queue " id))
    id))

(defn pending
  "Queued transactions not yet processed, oldest first: [{:id :db :tx-data}]."
  [s limit]
  (drain! s)
  (mapv (fn [r] {:id (:id r) :db (:db r) :tx-data (decode (edn/read-string (:tx_data r)))})
        (query s "select id, db, tx_data from nitomic_queue where status = 'pending'
                  order by id limit ?" [limit])))

(defn still-pending? [s id]
  (seq (query s "select 1 as x from nitomic_queue where id = ? and status = 'pending'" [id])))

(defn finish!
  "Record the outcome of a queued transaction: :done with {:t n}, or :failed
  with {:message :data}. Call inside with-write-lock."
  [s id status result]
  (execute! s "update nitomic_queue set status = ?, result = ? where id = ?"
            [(name status) (pr-str result) id])
  (notify! s (str "done " id)))

(defn queue-result
  "{:status :done|:failed :result ...} once the transactor has processed a
  queued transaction, nil while it waits."
  [s id]
  (let [r (first (query s "select status, result from nitomic_queue where id = ?" [id]))]
    (when (and r (not= "pending" (:status r)))
      {:status (keyword (:status r))
       :result (when (:result r) (edn/read-string (:result r)))})))

(defn cancel!
  "Withdraw a queued transaction the transactor hasn't taken. True if it was
  withdrawn, false if it had already been processed."
  [s id]
  (pos? (:changes (execute! s "delete from nitomic_queue where id = ? and status = 'pending'"
                            [id]))))

(defn forget! [s id]
  (execute! s "delete from nitomic_queue where id = ?" [id]))

;; ------------------------------------------------------------ URIs
(defn parse-uri
  "{:protocol :name :path} of datomic:sql://<name>?<jdbc-url>, where :path
  is the JDBC URL naming the storage."
  [uri]
  (let [rest-uri (subs uri (+ 3 (str/index-of uri "://")))
        q (str/index-of rest-uri "?")
        jdbc (when q (subs rest-uri (inc q)))]
    (when-not (and jdbc (or (str/starts-with? jdbc "jdbc:sqlite:")
                            (str/starts-with? jdbc "jdbc:postgresql:")))
      (throw (ex-info (str "nitomic's sql storage is SQLite or PostgreSQL: expected "
                           "datomic:sql://<name>?jdbc:sqlite:<path> or "
                           "datomic:sql://<name>?jdbc:postgresql://<host>/<db>..., got " uri)
                      {:db/error :db.error/invalid-db-uri})))
    {:protocol "sql"
     :name (subs rest-uri 0 q)
     :path jdbc}))
