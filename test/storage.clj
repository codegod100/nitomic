;; Durable databases in SQLite: datomic:sql://<name>?jdbc:sqlite:<path>.
;; Expected output is nitomic's own (test/expected/storage.out): Datomic's
;; SQL storage needs a transactor and a JDBC database, so there is no JVM
;; reference to record.
(require '[datomic.api :as d]
         '[clojure.java.io :as io])

(def path (str (System/getProperty "java.io.tmpdir") "/nitomic-storage-test.db"))
(defn clean! []
  (doseq [f [path (str path "-wal") (str path "-shm")]] (io/delete-file f true)))
(clean!)
(def uri (str "datomic:sql://people?jdbc:sqlite:" path))

;; the catalog lives in the file
(println (d/create-database uri) (d/create-database uri))          ; true false
(d/create-database (str "datomic:sql://other?jdbc:sqlite:" path))
(println (d/get-database-names (str "datomic:sql://*?jdbc:sqlite:" path)))

(def conn (d/connect uri))
@(d/transact conn [{:db/ident :person/name :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                   {:db/ident :person/friend :db/valueType :db.type/ref
                    :db/cardinality :db.cardinality/many}
                   {:db/ident :person/born :db/valueType :db.type/instant
                    :db/cardinality :db.cardinality/one}
                   {:db/ident :person/score :db/valueType :db.type/double
                    :db/cardinality :db.cardinality/one}])
(def r @(d/transact conn [{:db/id "ada" :person/name "Ada" :person/score 9.5
                           :person/born #inst "1815-12-10"}
                          {:db/id "grace" :person/name "Grace" :person/friend "ada"}]))
(println (:tempids r))
@(d/transact conn [[:db/add [:person/name "Ada"] :person/score 10.0]
                   [:db/retract [:person/name "Grace"] :person/friend [:person/name "Ada"]]])
(def before (d/db conn))

;; a failed transaction stores nothing
(println (try @(d/transact conn [{:person/name "Ada" :db/id "x"}
                                 [:db/add "x" :person/score "not a double"]])
              (catch Exception e (:db/error (ex-data e)))))

;; a new connection rebuilds exactly the same database from the log
(d/release conn)
(def peer-b (d/connect uri))
(println (not (identical? conn peer-b)) (= before (d/db peer-b)))
(println (d/basis-t (d/db peer-b)) (d/next-t (d/db peer-b)))
(println (d/q '[:find ?n ?s :where [?e :person/name ?n] [?e :person/score ?s]] (d/db peer-b)))
(println (:person/born (d/pull (d/db peer-b) '[:person/born] [:person/name "Ada"])))
(println (count (d/tx-range (d/log peer-b) nil nil)))
(println (d/q '[:find ?s :where [?e :person/score ?s]]
              (d/as-of (d/db peer-b) (d/t->tx (dec (d/basis-t (d/db peer-b)))))))
(println (sort (d/q '[:find ?s ?added :where [?e :person/score ?s _ ?added]]
                    (d/history (d/db peer-b)))))

;; two connections to one storage are two peers: each catches up with the
;; other's commits, and transacting first takes the other's into account
(def queue (d/tx-report-queue peer-b))
@(d/transact conn [{:person/name "Barbara"}])
(println (count (seq queue)) (count (:tempids (.poll queue))))      ; delivered, with its tempid
(println (d/q '[:find ?n . :where [?e :person/name "Barbara"] [?e :person/name ?n]]
              (d/db peer-b)))
(def rb @(d/transact peer-b [{:db/id "e" :person/name "Edsger"}]))
(def rc @(d/transact conn [{:db/id "e" :person/name "Frances"}]))
(println (get (:tempids rb) "e") (get (:tempids rc) "e"))           ; distinct ids
(println (= (d/db conn) (d/db peer-b)))

;; transaction functions are fns, which a file can't hold
(println (try @(d/transact conn [{:db/ident :my/fn :db/fn (d/function (fn [db] []))}])
              (catch Exception e (:db/error (ex-data e)))))
(println (d/basis-t (d/db conn)) (= (d/db conn) (d/db peer-b)))

;; rename and delete act on the storage
(println (d/rename-database (str "datomic:sql://other?jdbc:sqlite:" path) "renamed"))
(println (d/get-database-names (str "datomic:sql://*?jdbc:sqlite:" path)))
(println (d/delete-database (str "datomic:sql://renamed?jdbc:sqlite:" path)))
(println (try (d/connect (str "datomic:sql://renamed?jdbc:sqlite:" path))
              (catch Exception e (:db/error (ex-data e)))))

;; only SQLite storage is supported
(println (try (d/create-database "datomic:sql://x?jdbc:postgresql://localhost/datomic")
              (catch Exception e (:db/error (ex-data e)))))
(clean!)
