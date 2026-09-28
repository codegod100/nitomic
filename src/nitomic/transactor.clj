;; The transactor: the one process that writes a storage's log.
;;
;; While a transactor runs, peers queue their transaction data in the
;; storage (nitomic_queue) rather than writing the log themselves. The
;; transactor takes each queued transaction in order and, in one SQLite
;; transaction, runs it against the current database, appends it to the log
;; and records its outcome for the peer that queued it. Peers learn of new
;; transactions from the log, as they do without a transactor.
;;
;; It claims the storage while it runs (see nitomic.storage/claim!). Peers
;; queue only while the claim holds, and write the log themselves otherwise,
;; so stopping the transactor (or losing it) leaves every database
;; writable. On PostgreSQL it sleeps until a NOTIFY says something was
;; queued; on SQLite it looks again every couple of milliseconds.
(ns nitomic.transactor
  (:require [nitomic.db :as ndb]
            [nitomic.storage :as storage]
            [nitomic.tx :as tx]))

(defn start
  "Claim a storage, named by a JDBC URL or a SQLite path, on a connection of
  the transactor's own. Throws if another transactor has it."
  [spec]
  (let [s (storage/connect-store spec)
        id (str (random-uuid))]
    (try
      (storage/claim! s id)
      (catch Exception e
        (storage/close-store s)
        (throw e)))
    {:store s :id id :dbs (atom {}) :beat (atom (System/currentTimeMillis))}))

(defn- current-db
  "The database as of everything logged, kept between transactions."
  [{s :store dbs :dbs} name]
  (let [db (or (get @dbs name) (storage/load-db s name))
        db (reduce (fn [db rec] (ndb/apply-tx-record db rec))
                   db (storage/records-after s name (:basis-t db)))]
    (swap! dbs assoc name db)
    db))

(defn- process!
  "Transact one queued transaction, or record why it failed."
  [{s :store dbs :dbs :as tr} {:keys [id db tx-data]}]
  (storage/with-write-lock s
    (fn []
      (when (storage/still-pending? s id)
        (try
          (when-not (storage/exists? s db)
            (throw (ex-info (str "Could not find " db " in catalog")
                            {:db/error :db.error/db-not-found})))
          (let [report (tx/transact (current-db tr db) tx-data (java.util.Date.))]
            (storage/append! s db report)
            (storage/finish! s id :done {:t (:basis-t (:db-after report))})
            (swap! dbs assoc db (:db-after report)))
          (catch Exception e
            (storage/finish! s id :failed {:message (ex-message e)
                                           :data (storage/plain (ex-data e))})))))))

(defn- beat! [{s :store id :id beat :beat}]
  (let [now (System/currentTimeMillis)]
    (when (>= (- now @beat) storage/heartbeat-ms)
      (storage/heartbeat! s id)
      (reset! beat now))))

(defn step!
  "Process everything queued now. Returns how many transactions it took."
  [tr]
  (beat! tr)
  (let [batch (storage/pending (:store tr) 64)]
    (doseq [q batch] (process! tr q))
    (count batch)))

(defn stop!
  "Give up the storage: peers go back to writing the log themselves."
  [tr]
  (storage/release! (:store tr) (:id tr))
  (storage/close-store (:store tr))
  nil)

(defn run
  "Serve a storage until (stop?) returns true. While the queue is empty it
  waits for a peer to queue something (at most idle-ms at a time, so that
  it keeps its heartbeat and checks stop?)."
  ([spec] (run spec {}))
  ([spec {:keys [idle-ms stop?] :or {idle-ms 500 stop? (fn [] false)}}]
   (let [tr (start spec)]
     (try
       (loop []
         (when-not (stop?)
           (when (zero? (step! tr)) (storage/wait! (:store tr) idle-ms))
           (recur)))
       (finally (stop! tr))))))
