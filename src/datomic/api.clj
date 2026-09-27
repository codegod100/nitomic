;; datomic.api — the Datomic peer API, reimplemented for clonim.
;;
;; Databases live in process memory, as with datomic:mem:// URIs; other
;; storage protocols in a URI are accepted and behave the same way. Everything
;; else follows the peer library: immutable database values, transactions
;; that report :db-before/:db-after/:tx-data/:tempids, Datalog queries with
;; rules, pull, lazy entities, and as-of/since/history views.
(ns datomic.api
  (:require [clojure.string :as str]
            [nitomic.db :as ndb]
            [nitomic.entity :as entity]
            [nitomic.pull :as npull]
            [nitomic.query :as query]
            [nitomic.tx :as tx]
            [nitomic.types :as types]))

;; ------------------------------------------------------------ databases
(def ^:private databases (atom {}))

(defn- db-name
  "The database name in datomic:<protocol>://<host...>/<name> or
  datomic:mem://<name>."
  [uri]
  (when-not (and (string? uri) (str/starts-with? uri "datomic:"))
    (throw (ex-info (str "Invalid database URI: " (pr-str uri))
                    {:db/error :db.error/invalid-db-uri})))
  (let [rest-uri (subs uri (inc (str/index-of uri "://")))
        path (subs rest-uri 2)
        parts (str/split path "/")]
    (last parts)))

(defn create-database
  "Create a database. True if it was created, false if it already existed."
  [uri]
  (let [n (db-name uri)]
    (if (contains? @databases n)
      false
      (do
        (swap! databases assoc n
               {:uri uri
                :name n
                :state (atom (ndb/new-db n))
                :queues (atom [])})
        true))))

(defn delete-database [uri]
  (let [n (db-name uri)]
    (swap! databases dissoc n)
    true))

(defn rename-database [uri new-name]
  (let [n (db-name uri)
        conn (get @databases n)]
    (when conn
      (swap! databases #(-> % (dissoc n) (assoc new-name (assoc conn :name new-name)))))
    (some? conn)))

(defn get-database-names [uri-pattern]
  (seq (sort (keys @databases))))

(defn connect
  "A connection to an existing database."
  [uri]
  (let [conn (get @databases (db-name uri))]
    (when-not conn
      (throw (ex-info (str "Could not find " (db-name uri) " in catalog")
                      {:db/error :db.error/db-not-found})))
    conn))

(defn release [conn] nil)
(defn shutdown [shutdown-clojure] nil)

(defn db "The current database value of a connection." [conn] @(:state conn))

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

(defn transact
  "Submit a transaction. Returns a future of the transaction report."
  [conn tx-data]
  (let [state (:state conn)]
    (try
      (let [report (tx/transact @state tx-data (now))]
        (reset! state (:db-after report))
        (doseq [q @(:queues conn)] (swap! q conj report))
        (realized-future report nil))
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
  from now on. Supports .poll, .take, .peek, .isEmpty, .size and .clear."
  [conn]
  (let [items (atom [])
        pop! (fn []
               (let [x (first @items)]
                 (swap! items #(vec (rest %)))
                 x))]
    (swap! (:queues conn) conj items)
    (reify
      java.util.concurrent.BlockingQueue
      (poll [_] (pop!))
      (take [_] (pop!))
      (peek [_] (first @items))
      (isEmpty [_] (empty? @items))
      (size [_] (count @items))
      (clear [_] (reset! items []))
      clojure.lang.Seqable
      (seq [_] (seq @items)))))

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
