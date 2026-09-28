;; The transactor, driven step by step in one process: a peer queues
;; transactions (d/transact returns before they run), the transactor
;; processes the queue, and deref returns the report. Expected output is
;; nitomic's own (test/expected/transactor.out).
(require '[datomic.api :as d]
         '[clojure.java.io :as io]
         '[nitomic.storage :as storage]
         '[nitomic.transactor :as transactor])

(def path (str (System/getProperty "java.io.tmpdir") "/nitomic-transactor-test.db"))
(defn clean! []
  (doseq [f [path (str path "-wal") (str path "-shm")]] (io/delete-file f true)))
(clean!)
(def uri (str "datomic:sql://people?jdbc:sqlite:" path))
(d/create-database uri)
(def conn (d/connect uri))
(def s (storage/store path))
(defn queued [] (count (storage/pending s 100)))

;; no transactor yet: the peer writes the log itself
@(d/transact conn [{:db/ident :person/name :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                   {:db/ident :person/friend :db/valueType :db.type/ref
                    :db/cardinality :db.cardinality/many}])
(println (storage/transactor-alive? s) (queued))                     ; false 0

;; with one running, transactions wait in the queue for it
(def tr (transactor/start path))
(println (storage/transactor-alive? s))                             ; true
(println (try (transactor/start path) (catch Exception e (:db/error (ex-data e)))))
(def f1 (d/transact conn [{:db/id "ada" :person/name "Ada"}
                          {:db/id (d/tempid :db.part/user -1) :person/name "Grace"
                           :person/friend "ada"}]))
(def f2 (d/transact conn [[:db/add [:person/name "Nobody"] :person/name "x"]]))
(def f3 (d/transact-async conn [{:person/name "Barbara"}]))
(println (queued) (d/basis-t (d/db conn)))                          ; 3, unchanged
(println (transactor/step! tr) (queued))                            ; 3 0

(def r1 @f1)
(println (:tempids r1))                                             ; string and tempid keys
(println (d/resolve-tempid (:db-after r1) (:tempids r1) (d/tempid :db.part/user -1)))
(println (count (:tx-data r1)) (d/basis-t (:db-before r1)) (d/basis-t (:db-after r1)))
(println (try @f2 (catch Exception e (:db/error (ex-data e)))))     ; the transactor's error
(println (d/q '[:find ?n . :where [?e :person/name "Barbara"] [?e :person/name ?n]]
              (:db-after @f3)))

;; everything went through the log: a fresh connection replays the same db
(d/release conn)
(println (= (d/db conn) (d/db (d/connect uri))))

;; reports reach tx-report-queues with their tempids
(def queue (d/tx-report-queue conn))
(def f4 (d/transact conn [{:db/id "e" :person/name "Edsger"}]))
(transactor/step! tr)
(println (:tempids @f4) (:tempids (.poll queue)))

;; fns can't be sent to another process
(println (try @(d/transact conn [[:db/add "x" :person/name (fn [] 1)]])
              (catch Exception e (:db/error (ex-data e)))))

;; when the transactor stops, peers write the log themselves again
(transactor/stop! tr)
(println (storage/transactor-alive? s)
         (d/basis-t (:db-after @(d/transact conn [{:person/name "Frances"}])))
         (queued))

;; a transactor that stops without taking a queued transaction
(let [tr2 (transactor/start path)
      f (d/transact conn [{:person/name "Hedy"}])]
  (transactor/stop! tr2)
  ;; the peer withdraws it
  (println (try @f (catch Exception e (:db/error (ex-data e)))) (queued)))
(clean!)
