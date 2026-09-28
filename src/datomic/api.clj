;; datomic.api — the Datomic peer API, reimplemented for clonim.
;;
;; datomic:sql://<name>?jdbc:sqlite:<path> databases are durable, kept in a
;; SQLite file by nitomic.storage. Every other URI names a database in process
;; memory, as with datomic:mem://. Everything else follows the peer library:
;; immutable database values, transactions that report
;; :db-before/:db-after/:tx-data/:tempids, Datalog queries with rules, pull,
;; lazy entities, and as-of/since/history views.
(ns datomic.api
  (:require [clojure.string :as str]
            [nitomic.db :as ndb]
            [nitomic.entity :as entity]
            [nitomic.pull :as npull]
            [nitomic.query :as query]
            [nitomic.storage :as storage]
            [nitomic.tx :as tx]
            [nitomic.types :as types]))

;; ------------------------------------------------------------ databases
;; In-memory databases by name, and connections to stored ones by
;; [path name].
(def ^:private databases (atom {}))
(def ^:private sql-conns (atom {}))

(defn- parse-uri
  "{:protocol :name}, plus :path for datomic:sql://<name>?jdbc:sqlite:<path>.
  Other URIs are datomic:<protocol>://<host...>/<name> or
  datomic:mem://<name>."
  [uri]
  (when-not (and (string? uri) (str/starts-with? uri "datomic:")
                 (str/index-of uri "://"))
    (throw (ex-info (str "Invalid database URI: " (pr-str uri))
                    {:db/error :db.error/invalid-db-uri})))
  (let [protocol (subs uri 8 (str/index-of uri "://"))]
    (if (= protocol "sql")
      (storage/parse-uri uri)
      (let [path (subs uri (+ 3 (str/index-of uri "://")))]
        {:protocol protocol :name (last (str/split path "/"))}))))

(defn- sql? [u] (= "sql" (:protocol u)))

(defn create-database
  "Create a database. True if it was created, false if it already existed."
  [uri]
  (let [u (parse-uri uri)
        n (:name u)]
    (cond
      (sql? u) (storage/create! (storage/store (:path u)) n)
      (contains? @databases n) false
      :else
      (do
        (swap! databases assoc n
               {:uri uri
                :name n
                :state (atom (ndb/new-db n))
                :queues (atom [])})
        true))))

(defn delete-database [uri]
  (let [u (parse-uri uri)
        n (:name u)]
    (if (sql? u)
      (do (swap! sql-conns dissoc [(:path u) n])
          (storage/delete! (storage/store (:path u)) n))
      (do (swap! databases dissoc n)
          true))))

(defn rename-database [uri new-name]
  (let [u (parse-uri uri)
        n (:name u)]
    (if (sql? u)
      (do (swap! sql-conns dissoc [(:path u) n])
          (storage/rename! (storage/store (:path u)) n new-name))
      (let [conn (get @databases n)]
        (when conn
          (swap! databases #(-> % (dissoc n) (assoc new-name (assoc conn :name new-name)))))
        (some? conn)))))

(defn get-database-names
  "Database names in the storage a URI pattern names (its database name is
  ignored, as with Datomic's datomic:sql://*?...), or in memory."
  [uri-pattern]
  (let [u (parse-uri uri-pattern)]
    (seq (if (sql? u)
           (storage/names (storage/store (:path u)))
           (sort (keys @databases))))))

(defn- connect-sql [uri {:keys [path name]}]
  (or (get @sql-conns [path name])
      (let [s (storage/store path)]
        (when-not (storage/exists? s name)
          (throw (ex-info (str "Could not find " name " in catalog")
                          {:db/error :db.error/db-not-found})))
        (let [conn {:uri uri
                    :name name
                    :state (atom (storage/load-db s name))
                    :queues (atom [])
                    :recent (atom {})
                    :store s}]
          (swap! sql-conns assoc [path name] conn)
          conn))))

(defn connect
  "A connection to an existing database."
  [uri]
  (let [u (parse-uri uri)]
    (if (sql? u)
      (connect-sql uri u)
      (let [conn (get @databases (:name u))]
        (when-not conn
          (throw (ex-info (str "Could not find " (:name u) " in catalog")
                          {:db/error :db.error/db-not-found})))
        conn))))

(defn release
  "Forget a stored database's connection: the next connect rebuilds it from
  storage. The released connection keeps working. In-memory connections
  are the database itself, so this does nothing to them."
  [conn]
  (when (:store conn)
    (swap! sql-conns dissoc [(get-in conn [:store :path]) (:name conn)]))
  nil)

(defn shutdown [shutdown-clojure] nil)

(defn- publish! [conn report]
  (doseq [q @(:queues conn)] (swap! q conj report)))

(def ^:private recent-reports
  "How many reports of caught-up transactions a connection keeps, for the
  transactions it queued to find their own."
  64)

(defn- remember! [conn report]
  (swap! (:recent conn)
         (fn [m]
           (let [m (assoc m (:basis-t (:db-after report)) report)]
             (if (> (count m) recent-reports) (dissoc m (apply min (keys m))) m)))))

(defn- apply-records!
  "Apply transactions others stored (log records, in order), reporting each
  to the connection's tx-report-queues as Datomic delivers every
  transaction to every peer."
  [conn records]
  (let [state (:state conn)]
    (doseq [rec records]
      (let [before @state
            after (ndb/apply-tx-record before rec)
            report {:db-before before
                    :db-after after
                    :tx-data (mapv types/datom (:data rec))
                    :tempids (:tempids rec {})}]
        (reset! state after)
        (remember! conn report)
        (publish! conn report)))))

(defn- catch-up!
  "Apply the transactions stored since this connection's basis, by other
  connections or by the transactor."
  [conn]
  (when-let [s (:store conn)]
    (apply-records! conn (storage/records-after s (:name conn) (:basis-t @(:state conn)))))
  conn)

(defn db
  "The current database value of a connection, including what other
  connections to its storage have committed."
  [conn]
  (catch-up! conn)
  @(:state conn))

;; ------------------------------------------------------------ futures
(defn- realized-future
  "What transact returns: deref yields the report, or throws the error the
  transaction failed with."
  [value error]
  (reify
    clojure.lang.IDeref
    (deref [_] (if error (throw error) value))
    java.util.concurrent.Future
    (get [_] (if error (throw error) value))
    (isDone [_] true)
    (isCancelled [_] false)))

;; ------------------------------------------------------------ transactions
(defn- now [] (java.util.Date.))

(defn- await-queued
  "The report of a transaction queued for the transactor, once it has been
  processed; throws what it failed with. If the transactor goes away first,
  the transaction is withdrawn and fails with :db.error/transactor-unavailable."
  [conn id]
  (let [s (:store conn)
        {:keys [status result]}
        (loop []
          (or (storage/queue-result s id)
              (if (and (not (storage/transactor-alive? s)) (storage/cancel! s id))
                (throw (ex-info "The transactor stopped before processing the transaction"
                                {:db/error :db.error/transactor-unavailable}))
                (do (storage/wait! s 1000) (recur)))))]
    (storage/forget! s id)
    (if (= status :failed)
      (throw (ex-info (:message result) (or (:data result) {})))
      (do
        (catch-up! conn)
        (or (get @(:recent conn) (:t result))
            (storage/report-at s (:name conn) (:t result)))))))

(defn- queued-future
  "A future of a queued transaction's report, waited for on first deref."
  [conn id]
  (let [outcome (atom nil)
        settle (fn []
                 (when-not @outcome
                   (reset! outcome (try [(await-queued conn id) nil]
                                        (catch Exception e [nil e]))))
                 (let [[v e] @outcome] (if e (throw e) v)))]
    (reify
      clojure.lang.IDeref
      (deref [_] (settle))
      java.util.concurrent.Future
      (get [_] (settle))
      (isDone [_] (boolean (or @outcome (storage/queue-result (:store conn) id))))
      (isCancelled [_] false))))

(defn- write-postgres
  "Write a transaction to PostgreSQL storage in two round trips (see
  nitomic.storage/begin-write!): the report, or :queue when a transactor has
  the storage and should take it instead."
  [conn tx-data]
  (let [s (:store conn)
        state (:state conn)
        {:keys [transactor? records]}
        (try (storage/begin-write! s (:name conn) (:basis-t @state))
             (catch Exception e (storage/abort-write! s) (throw e)))]
    (if transactor?
      (do (storage/abort-write! s) :queue)
      (try
        ;; others' transactions, committed before we got the lock
        (apply-records! conn records)
        (let [report (tx/transact @state tx-data (now))]
          (storage/commit-write! s (:name conn) report)
          report)
        (catch Exception e
          (storage/abort-write! s)
          (throw e))))))

(defn transact
  "Submit a transaction. Returns a future of the transaction report.

  For a stored database with a transactor running, the transaction is
  queued for it and the future waits for the transactor. Otherwise the
  connection writes the log itself."
  [conn tx-data]
  (let [state (:state conn)
        s (:store conn)
        queue! (fn [] (queued-future conn (storage/enqueue! s (:name conn) tx-data)))]
    (try
      (cond
        (and s (storage/push? s))
        (let [report (write-postgres conn tx-data)]
          (if (= report :queue)
            (queue!)
            (do (reset! state (:db-after report))
                (publish! conn report)
                (realized-future report nil))))

        (and s (storage/transactor-alive? s))
        (queue!)

        :else
        (let [report
              (if s
                ;; Holding the write lock, catch up with other writers, then
                ;; store this transaction before anyone else can write.
                (storage/with-write-lock s
                  (fn []
                    (catch-up! conn)
                    (let [report (tx/transact @state tx-data (now))]
                      (storage/append! s (:name conn) report)
                      report)))
                (tx/transact @state tx-data (now)))]
          (reset! state (:db-after report))
          (publish! conn report)
          (realized-future report nil)))
      (catch Exception e
        (realized-future nil e)))))

(defn transact-async [conn tx-data] (transact conn tx-data))

(defn with
  "Apply a transaction to a database value without durably recording it."
  [db tx-data]
  (tx/transact db tx-data (now)))

(defn sync
  ([conn] (realized-future (db conn) nil))
  ([conn t] (realized-future (db conn) nil)))

(defn sync-index [conn t] (realized-future (db conn) nil))
(defn sync-schema [conn t] (realized-future (db conn) nil))
(defn sync-excise [conn t] (realized-future (db conn) nil))
(defn request-index [conn] true)
(defn gc-storage [conn older-than] nil)

(defn tx-report-queue
  "A queue that receives the report of every transaction on the connection
  from now on. Supports .poll, .take, .peek, .isEmpty, .size and .clear.
  For a stored database, reading the queue first picks up what other
  connections have committed, and on PostgreSQL .take waits for the next
  transaction when there is none yet."
  [conn]
  (let [items (atom [])
        pending (fn [] (catch-up! conn) @items)
        pop! (fn []
               (let [x (first (pending))]
                 (swap! items #(vec (rest %)))
                 x))
        s (:store conn)
        take! (fn []
                (if (and s (storage/push? s))
                  (loop []
                    (if (seq (pending))
                      (pop!)
                      (do (storage/wait! s 1000) (recur))))
                  (pop!)))]
    (swap! (:queues conn) conj items)
    (reify
      java.util.concurrent.BlockingQueue
      (poll [_] (pop!))
      (take [_] (take!))
      (peek [_] (first (pending)))
      (isEmpty [_] (empty? (pending)))
      (size [_] (count (pending)))
      (clear [_] (reset! items []))
      clojure.lang.Seqable
      (seq [_] (seq (pending))))))

(defn remove-tx-report-queue [conn]
  (reset! (:queues conn) [])
  nil)

;; ------------------------------------------------------------ ids
(defn tempid
  "A temporary id in a partition; with n, the tempid numbered n."
  ([partition] (types/tempid partition))
  ([partition n] (types/tempid partition n)))

(defn resolve-tempid
  "The id a transaction assigned to a tempid."
  [db tempids tid]
  (get tempids (if (types/tempid? tid) (:idx tid) tid)))

(defn entid [db ident] (ndb/entid db ident))
(defn ident [db eid]
  (if (keyword? eid)
    (when (ndb/entid db eid) eid)
    (ndb/ident-of db (ndb/entid db eid))))

(defn part [eid] (ndb/part eid))
(defn t->tx [t] (ndb/t->tx t))
(defn tx->t [tx] (ndb/tx->t tx))

(defn entid-at [db partition t-or-date]
  (let [p (if (integer? partition) partition (ndb/entid db partition))
        t (if (inst? t-or-date) (ndb/resolve-t db t-or-date) t-or-date)]
    (ndb/entid-at p t)))

(defn- hex [n width]
  (let [digits "0123456789abcdef"]
    (loop [n n i 0 out ()]
      (if (= i width)
        (apply str out)
        (recur (quot n 16) (inc i) (cons (nth digits (mod n 16)) out))))))

(defn squuid
  "A UUID whose leading 32 bits are the current time in seconds, so that
  successively created ids sort roughly by creation time."
  []
  (let [u (str (random-uuid))
        secs (quot (inst-ms (now)) 1000)
        h (hex secs 8)]
    (parse-uuid (str h (subs u 8)))))

(defn squuid-time-millis [uuid]
  (let [s (subs (str uuid) 0 8)
        digits "0123456789abcdef"]
    (* 1000 (reduce (fn [n c] (+ (* n 16) (str/index-of digits (str c)))) 0 s))))

;; ------------------------------------------------------------ views
(defn basis-t [db] (or (:as-of-t db) (:basis-t db)))
(defn next-t [db] (:next-t db))
(defn as-of [db t] (ndb/as-of db t))
(defn as-of-t [db] (:as-of-t db))
(defn since [db t] (ndb/since db t))
(defn since-t [db] (:since-t db))
(defn history [db] (ndb/history db))
(defn is-history [db] (some? (:history db)))
(defn is-filtered [db] (boolean (or (:filtered db) (:as-of-t db) (:since-t db))))

(defn filter
  "A database containing only the datoms for which (pred db datom) is true."
  [db pred]
  (let [kept (clojure.core/filter
              (fn [[e a v t]] (pred db (types/datom e a v t true)))
              (ndb/current-datoms db))
        idx (reduce (fn [idx [e a v t]] (ndb/index-add idx (ndb/ref-attr? db a) e a v t))
                    {:eavt {} :aevt {} :avet {} :vaet {}} kept)]
    (assoc (merge db idx) :filtered true)))

;; ------------------------------------------------------------ reading
(defn q
  "Run a Datalog query. The query is a vector, a map or a string."
  [query & inputs]
  (apply query/q query inputs))

(defn query
  "Run a query given as a map of :query and :args."
  [{:keys [query args]}]
  (apply query/q query args))

(defn register-fn!
  "Make f callable from query clauses as sym (see nitomic.query)."
  [sym f]
  (query/register-fn! sym f))

(defn function
  "A database function. On the JVM this compiles {:lang :params :code}; a
  native program cannot compile code at runtime, so here it takes the fn
  itself (or a map holding it under :fn) and returns it for :db/fn."
  [f]
  (if (map? f) (:fn f) f))

(defn invoke
  "Invoke the database function stored under an ident."
  [db ident & args]
  (let [f (first (keys (get-in db [:eavt (ndb/entid db ident) (ndb/entid db :db/fn)])))]
    (apply f args)))

(defn pull [db pattern eid] (npull/pull db pattern eid))
(defn pull-many [db pattern eids] (npull/pull-many db pattern eids))

(defn entity [db eid] (entity/entity db eid))
(defn entity-db [entity] (.db entity))
(defn touch [entity] (.touch entity))

(defn attribute
  "Schema information about an attribute."
  [db attrid]
  (let [a (ndb/attr db (ndb/entid db attrid))]
    (when a
      {:id (:id a)
       :ident (:ident a)
       :value-type (:value-type a)
       :cardinality (:cardinality a)
       :indexed (:indexed a)
       :has-avet (boolean (or (:indexed a) (:unique a)))
       :unique (:unique a)
       :is-component (:is-component a)
       :no-history (:no-history a)
       :fulltext (:fulltext a)})))

(defn- index-order [index]
  (case index
    :eavt (fn [[e a v t]] [e a v t])
    :aevt (fn [[e a v t]] [a e v t])
    :avet (fn [[e a v t]] [a v e t])
    :vaet (fn [[e a v t]] [v a e t])
    (throw (ex-info (str "Unknown index: " index) {:db/error :db.error/invalid-index}))))

(defn- type-rank [x]
  (cond (nil? x) 0 (boolean? x) 1 (number? x) 2 (string? x) 3 (keyword? x) 4
        (symbol? x) 5 (inst? x) 6 (uuid? x) 7 :else 8))

(defn- cmp-key
  "Compare index keys element by element; values of unlike types order by
  type name, so a mixed index still sorts totally."
  [x y]
  (loop [i 0]
    (if (= i (count x))
      0
      (let [a (nth x i) b (nth y i)
            c (if (= (type-rank a) (type-rank b))
                (compare a b)
                (compare (type-rank a) (type-rank b)))]
        (if (zero? c) (recur (inc i)) c)))))

(defn- resolve-components
  "Index components with idents and lookup refs resolved to ids, in the
  order of the index's positions."
  [db index components]
  (let [positions (case index
                    :eavt [:e :a :v :t]
                    :aevt [:a :e :v :t]
                    :avet [:a :v :e :t]
                    :vaet [:v :a :e :t])
        attr (some (fn [[p c]] (when (= p :a) (ndb/attr-id db c)))
                   (map vector positions components))]
    (vec (map (fn [p c]
                (case p
                  :e (ndb/entid db c)
                  :a (ndb/attr-id db c)
                  :v (if (and attr (ndb/ref-attr? db attr)) (ndb/entid db c) c)
                  c))
              positions components))))

(defn- index-datoms [db index]
  (let [datoms (case index
                 :vaet (for [[v aes] (:vaet db) [a es] aes [e t] es] [e a v t])
                 (ndb/current-datoms db))
        datoms (if (= index :avet)
                 ;; the AVET index covers indexed and unique attributes
                 (clojure.core/filter
                  (fn [[_ a]] (let [at (ndb/attr db a)] (or (:indexed at) (:unique at))))
                  datoms)
                 datoms)
        key-of (index-order index)]
    (sort (fn [x y] (cmp-key (key-of x) (key-of y))) datoms)))

(defn datoms
  "The datoms of an index whose leading positions match the components."
  [db index & components]
  (let [key-of (index-order index)
        prefix (resolve-components db index components)]
    (map (fn [[e a v t]] (types/datom e a v t true))
         (clojure.core/filter
          (fn [d] (= prefix (vec (take (count prefix) (key-of d)))))
          (index-datoms db index)))))

(defn seek-datoms
  "The datoms of an index from the first one at or after the components."
  [db index & components]
  (let [key-of (index-order index)
        start (resolve-components db index components)
        n (count start)]
    (map (fn [[e a v t]] (types/datom e a v t true))
         (drop-while (fn [d] (neg? (cmp-key (vec (take n (key-of d))) start)))
                     (index-datoms db index)))))

(defn index-range
  "AVET datoms of an attribute with values in [start, end); nil is open."
  [db attrid start end]
  (let [a (ndb/attr-id db attrid)]
    (map (fn [[e a v t]] (types/datom e a v t true))
         (clojure.core/filter
          (fn [[_ _ v _]]
            (and (or (nil? start) (not (neg? (cmp-key [v] [start]))))
                 (or (nil? end) (neg? (cmp-key [v] [end])))))
          (clojure.core/filter (fn [[_ da]] (= da a)) (index-datoms db :avet))))))

;; ------------------------------------------------------------ the log
(defn log [conn] {:log (:log (db conn))})

(defn tx-range
  "Transactions from start (inclusive) to end (exclusive), each as
  {:t t :data [datoms]}. Bounds are t values or transaction ids, nil open."
  [log start end]
  (let [t-of (fn [x] (when x (if (>= x ndb/tx0) (ndb/tx->t x) x)))
        lo (t-of start)
        hi (t-of end)]
    (map (fn [rec] {:t (:t rec) :data (mapv types/datom (:data rec))})
         (clojure.core/filter (fn [rec] (and (or (nil? lo) (>= (:t rec) lo))
                                             (or (nil? hi) (< (:t rec) hi))))
                              (:log log)))))

(defn db-stats [db]
  {:datoms (count (ndb/current-datoms db))
   :attrs (into {} (map (fn [[a es]] [(ndb/ident-of db a) {:count (count es)}])
                        (:aevt db)))})
