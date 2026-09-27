;; A tour of the peer API beyond the getting-started walkthrough. Like it,
;; this runs unchanged on the JVM against Datomic Pro and on clonim against
;; nitomic, and the outputs are compared line by line (test/run.sh).
(require '[datomic.api :as d]
         '[clojure.string :as str])

(defn- canon-str [x]
  (cond
    (map? x) (str "{" (str/join ", " (sort (map (fn [[k v]] (str (canon-str k) " " (canon-str v))) x))) "}")
    (set? x) (str "#{" (str/join " " (sort (map canon-str x))) "}")
    (vector? x) (str "[" (str/join " " (map canon-str x)) "]")
    (seq? x) (str "(" (str/join " " (map canon-str x)) ")")
    (inst? x) "#inst"
    :else (pr-str x)))

(defn show [label x] (println (str label ":") (canon-str x)))
(defn show-sorted [label xs]
  (println (str label ":") (str "(" (str/join " " (sort (map canon-str xs))) ")")))

(defn error-code [e]
  (or (:db/error (ex-data e))
      (when (.getCause e) (:db/error (ex-data (.getCause e))))
      :no-code))

(defmacro attempt [label body]
  (list 'try
        (list 'let ['r body] (list 'show label 'r))
        (list 'catch 'Exception 'e (list 'show label (list 'error-code 'e)))))

(defn tx! [conn data] @(d/transact conn data))

(def uri "datomic:mem://features")
(d/create-database uri)
(def conn (d/connect uri))
(show "create again" (d/create-database uri))

;; ---------------------------------------------------------------- schema
(def schema
  [{:db/ident :person/email :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :person/name :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident :person/age :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :person/height :db/valueType :db.type/double
    :db/cardinality :db.cardinality/one}
   {:db/ident :person/active :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident :person/nick :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :person/friend :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident :person/parent :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :person/ssn :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/value}
   {:db/ident :person/address :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one :db/isComponent true}
   {:db/ident :person/phone :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many :db/isComponent true}
   {:db/ident :person/born :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :person/token :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident :person/status :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :address/street :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :phone/number :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :status/active}
   {:db/ident :status/retired}])

(def schema-report (tx! conn schema))
(show-sorted "schema tempids" (vals (:tempids schema-report)))
(show "attribute ids" (map #(d/entid (d/db conn) %) [:person/email :person/name :address/street]))
(show "enum ids" (map #(d/part (d/entid (d/db conn) %)) [:status/active :status/retired]))
(def attr-keys [:id :ident :value-type :cardinality :indexed :has-avet :unique
                :is-component :no-history :fulltext])
(show "attribute" (map #(get (d/attribute (d/db conn) :person/email) %) attr-keys))
(show "attribute component" (map #(get (d/attribute (d/db conn) :person/phone) %) attr-keys))

;; ---------------------------------------------------------------- data
(def r1 (tx! conn [{:db/id "alice"
                    :person/email "alice@example.com"
                    :person/name "Alice"
                    :person/age 34
                    :person/height 1.7
                    :person/active true
                    :person/nick [:al :ally]
                    :person/ssn "111"
                    :person/born #inst "1990-05-01T00:00:00.000-00:00"
                    :person/token #uuid "5b0d8c0e-1111-4c2b-9f00-000000000001"
                    :person/status :status/active
                    :person/address {:address/street "1 Main St"}
                    :person/phone [{:phone/number "555-1"} {:phone/number "555-2"}]}
                   {:db/id "bob"
                    :person/email "bob@example.com"
                    :person/name "Bob"
                    :person/age 40
                    :person/friend ["alice"]
                    :person/status :status/retired}
                   {:db/id "carol"
                    :person/email "carol@example.com"
                    :person/name "Carol"
                    :person/age 12
                    :person/parent "alice"
                    :person/friend ["bob" "alice"]}
                   {:person/email "dave@example.com"
                    :person/name "Dave"
                    :person/age 8
                    :person/parent "carol"}]))
(def db1 (:db-after r1))
(def alice (d/resolve-tempid db1 (:tempids r1) "alice"))
(def bob (d/resolve-tempid db1 (:tempids r1) "bob"))
(def carol (d/resolve-tempid db1 (:tempids r1) "carol"))
(show "ids" [alice bob carol])
(show "tx-data count" (count (:tx-data r1)))
(show "tx datom" (let [dt (first (filter #(and (= alice (:e %)) (= (:a %) (d/entid db1 :person/name)))
                                         (:tx-data r1)))]
                   [(:e dt) (d/ident db1 (:a dt)) (:added dt) (= (:tx dt) (d/t->tx (d/basis-t db1)))]))
(show "basis-t" (d/basis-t db1))
(show "t<->tx" [(d/tx->t (d/t->tx 1234)) (d/t->tx 1000)])

;; ---------------------------------------------------------------- identity
(show "lookup ref" (d/entid db1 [:person/email "bob@example.com"]))
(show "ident" [(d/ident db1 :person/name) (d/entid db1 :status/active)])
(def r2 (tx! conn [{:person/email "bob@example.com" :person/age 41}]))
(show "upsert keeps id" (= bob (d/entid (:db-after r2) [:person/email "bob@example.com"])))
(show-sorted "upsert tx-data" (map (fn [dt] [(d/ident (:db-after r2) (:a dt)) (:v dt) (:added dt)])
                            (filter #(= bob (:e %)) (:tx-data r2))))
(tx! conn [[:db/add [:person/email "carol@example.com"] :person/nick :cc]])
(show "lookup ref in e" (:person/nick (d/entity (d/db conn) carol)))
(tx! conn [[:db/add alice :person/friend [:person/email "carol@example.com"]]])
(show "lookup ref in v" (map :person/name (:person/friend (d/entity (d/db conn) alice))))

(attempt "unique value conflict"
         (tx! conn [{:person/email "eve@example.com" :person/ssn "111"}]))
(attempt "wrong type" (tx! conn [{:person/email "eve@example.com" :person/age "old"}]))
(attempt "unknown attribute" (tx! conn [{:person/email "eve@example.com" :person/shoe 9}]))
(attempt "cas ok" (count (:tx-data (tx! conn [[:db/cas bob :person/age 41 42]]))))
(attempt "cas failed" (tx! conn [[:db/cas bob :person/age 41 43]]))
(show "age after cas" (:person/age (d/entity (d/db conn) bob)))

;; ---------------------------------------------------------------- entity
(let [e (d/entity (d/db conn) alice)]
  (show "entity keys" (set (keys e)))
  (show "entity values" [(:person/name e) (:person/age e) (:person/height e) (:person/active e)
                         (:person/nick e) (:person/status e) (:person/token e)])
  (show "entity inst" (inst-ms (:person/born e)))
  (show "component" (:address/street (:person/address e)))
  (show "component many" (set (map :phone/number (:person/phone e))))
  (show "reverse ref" (set (map :person/name (:person/_parent e))))
  (show "reverse friends" (set (map :person/name (:person/_friend e))))
  (show "component reverse" (:person/name (:person/_address (:person/address e))))
  (show "entity db" (= (d/basis-t (d/entity-db e)) (d/basis-t (d/db conn))))
  (show "entity id" (:db/id e))
  (show "missing attr" (:person/parent e))
  (show "entity get default" (get e :person/parent :none))
  (show "touch" (:person/name (d/touch e))))
(show "entity by ident" (:db/ident (d/entity (d/db conn) :person/email)))

;; ---------------------------------------------------------------- pull
(let [db (d/db conn)]
  (show "pull attrs" (d/pull db [:person/name :person/age :person/nick] alice))
  (show "pull db/id" (= alice (:db/id (d/pull db [:db/id] alice))))
  (show "pull enum" (d/pull db [{:person/status [:db/ident]}] alice))
  (show "pull nested" (d/pull db [:person/name {:person/friend [:person/name]}] bob))
  (show "pull reverse" (d/pull db [:person/name {:person/_parent [:person/name]}] alice))
  (show "pull component *" (-> (d/pull db '[*] alice)
                               (select-keys [:person/address :person/phone :person/name])
                               (update :person/address dissoc :db/id)
                               (update :person/phone #(set (map :phone/number %)))))
  (show "pull as/default/limit"
        (d/pull db [[:person/name :as "name"]
                    [:person/parent :default :none]
                    [:person/nick :limit 1]]
                bob))
  (show "pull legacy limit" (count (:person/friend (d/pull db '[(limit :person/friend 1)] alice))))
  (show "pull recursive" (d/pull db [:person/name {:person/parent '...}]
                                 [:person/email "dave@example.com"]))
  (show "pull recursion limit" (d/pull db [:person/name {:person/parent 1}]
                                       [:person/email "dave@example.com"]))
  (show "pull lookup ref" (d/pull db [:person/age] [:person/email "carol@example.com"]))
  (show "pull nothing" (d/pull db [:person/shoe-size] alice))
  (show "pull-many" (d/pull-many db [:person/name] [alice bob carol])))

;; ---------------------------------------------------------------- queries
(let [db (d/db conn)]
  (show "scalar" (d/q '[:find ?n . :in $ ?e :where [?e :person/name ?n]] db bob))
  (show "tuple" (d/q '[:find [?n ?a] :in $ ?e :where [?e :person/name ?n] [?e :person/age ?a]] db bob))
  (show-sorted "rel" (d/q '[:find ?n ?a :where [?e :person/name ?n] [?e :person/age ?a]] db))
  (show "count" (d/q '[:find (count ?e) . :where [?e :person/name]] db))
  (show-sorted "aggregates" (d/q '[:find (min ?a) (max ?a) (sum ?a) (count-distinct ?a)
                            :where [_ :person/age ?a]] db))
  (show "avg" (d/q '[:find (avg ?a) . :where [_ :person/age ?a]] db))
  (show "median" (d/q '[:find (median ?a) . :where [_ :person/age ?a]] db))
  (show-sorted "grouped" (d/q '[:find ?parent (count ?kid)
                         :where [?kid :person/parent ?p] [?p :person/name ?parent]] db))
  (show "with" (d/q '[:find (sum ?h) . :with ?e :where [?e :person/height ?h]] db))
  (show "distinct agg" (d/q '[:find (distinct ?nick) . :where [_ :person/nick ?nick]] db))
  (show "min n" (d/q '[:find (min 2 ?a) . :where [_ :person/age ?a]] db))
  (show-sorted "not" (d/q '[:find [?n ...] :where [?e :person/name ?n] (not [?e :person/parent _])] db))
  (show-sorted "not-join" (d/q '[:find [?n ...]
                          :where [?e :person/name ?n]
                          (not-join [?e] [?k :person/parent ?e])] db))
  (show-sorted "or" (d/q '[:find [?n ...]
                    :where [?e :person/name ?n]
                    (or [?e :person/status :status/retired]
                        [?e :person/age 12])] db))
  (show-sorted "or-join" (d/q '[:find [?n ...]
                         :where [?e :person/name ?n]
                         (or-join [?e]
                                  (and [?e :person/friend ?f] [?f :person/name "Bob"])
                                  [?e :person/nick :al])] db))
  (show-sorted "predicate" (d/q '[:find [?n ...] :where [?e :person/name ?n] [?e :person/age ?a] [(> ?a 30)]] db))
  (show-sorted "fn binding" (d/q '[:find ?n ?next :where [?e :person/name ?n] [?e :person/age ?a]
                            [(inc ?a) ?next]] db))
  (show-sorted "fn tuple binding" (d/q '[:find ?a ?b :where [(vector 1 2) [?a ?b]]] db))
  (show-sorted "fn coll binding" (d/q '[:find [?x ...] :where [(range 3) [?x ...]]] db))
  (show-sorted "fn rel binding" (d/q '[:find ?a ?b :where [(hash-map 1 2 3 4) [[?a ?b]]]] db))
  (show-sorted "get-else" (d/q '[:find ?n ?p :where [?e :person/name ?n]
                          [(get-else $ ?e :person/parent :none) ?p]] db))
  (show-sorted "missing?" (d/q '[:find [?n ...] :where [?e :person/name ?n] [(missing? $ ?e :person/age)]] db))
  (show "ground" (d/q '[:find ?x . :where [(ground 42) ?x]] db))
  (show "str fn" (d/q '[:find ?s . :in $ ?e :where [?e :person/name ?n] [(str ?n "!") ?s]] db bob))
  (show-sorted "map query" (d/q {:find '[?n] :in '[$ ?age] :where '[[?e :person/age ?age] [?e :person/name ?n]]}
                         db 12))
  (show-sorted "d/query" (d/query {:query '[:find [?n ...] :where [_ :person/name ?n]] :args [db]}))
  (show-sorted "two sources" (d/q '[:find ?n ?score
                             :in $ $scores
                             :where [?e :person/name ?n] [$scores ?n ?score]]
                           db [["Alice" 10] ["Bob" 20] ["Zed" 30]]))
  (show-sorted "attribute var" (d/q '[:find [?attr ...] :in $ ?e :where [?e ?a _] [?a :db/ident ?attr]] db bob))
  (show-sorted "recursive rule"
        (d/q '[:find [?name ...]
               :in $ % ?start
               :where (ancestor ?start ?a) [?a :person/name ?name]]
             db
             '[[(ancestor ?x ?y) [?x :person/parent ?y]]
               [(ancestor ?x ?y) [?x :person/parent ?z] (ancestor ?z ?y)]]
             [:person/email "dave@example.com"]))
  (show-sorted "rule both unbound"
        (d/q '[:find ?x ?y :in $ % :where (ancestor ?x ?y)]
             db
             '[[(ancestor ?x ?y) [?x :person/parent ?y]]
               [(ancestor ?x ?y) [?x :person/parent ?z] (ancestor ?z ?y)]]))
  (show-sorted "cyclic rule"
        (d/q '[:find [?n ...] :in $ % ?start
               :where (reach ?start ?f) [?f :person/name ?n]]
             db
             '[[(reach ?x ?y) [?x :person/friend ?y]]
               [(reach ?x ?y) [?x :person/friend ?z] (reach ?z ?y)]]
             alice))
  (show-sorted "empty result" (d/q '[:find ?e :where [?e :person/name "Nobody"]] db))
  (show "empty scalar" (d/q '[:find ?e . :where [?e :person/name "Nobody"]] db)))

;; ---------------------------------------------------------------- changes
(def before-retract (d/db conn))
(def r3 (tx! conn [[:db/retract alice :person/nick :al]
                   [:db/add alice :person/age 35]]))
(show "retract and replace" (sort-by str (map (fn [dt] [(d/ident (:db-after r3) (:a dt)) (:v dt) (:added dt)])
                                              (filter #(= alice (:e %)) (:tx-data r3)))))
(tx! conn [[:db/retract carol :person/nick]])
(show "retract all values" (:person/nick (d/entity (d/db conn) carol)))
(def r4 (tx! conn [[:db/retractEntity alice]]))
(show "retractEntity datoms" (count (:tx-data r4)))
(let [db (d/db conn)]
  (show "entity gone" (d/q '[:find ?e . :where [?e :person/email "alice@example.com"]] db))
  (show "components gone" (d/q '[:find (count ?p) . :where [?p :phone/number]] db))
  (show "references gone" (d/q '[:find [?n ...] :where [?e :person/parent ?p] [?e :person/name ?n]] db)))

;; ---------------------------------------------------------------- time
(let [db (d/db conn)
      t-before (d/basis-t before-retract)]
  (show "as-of t" (d/q '[:find ?a . :in $ ?e :where [?e :person/age ?a]]
                       (d/as-of db t-before) alice))
  (show "as-of tx" (d/q '[:find ?a . :in $ ?e :where [?e :person/age ?a]]
                        (d/as-of db (d/t->tx t-before)) alice))
  (show "as-of-t" (= t-before (d/as-of-t (d/as-of db t-before))))
  (show-sorted "since" (d/q '[:find [?n ...] :where [_ :person/name ?n]] (d/since db (d/basis-t db1))))
  (show "history of age"
        (sort (d/q '[:find ?a ?added :in $ ?e :where [?e :person/age ?a _ ?added]]
                   (d/history db) alice)))
  (show "is-history" [(d/is-history (d/history db)) (d/is-history db)])
  (show "with" (let [r (d/with db [{:person/email "zed@example.com" :person/name "Zed"}])]
                 [(d/q '[:find ?n . :where [_ :person/email "zed@example.com"] [_ :person/name "Zed"] [(ground "ok") ?n]] (:db-after r))
                  (d/q '[:find ?e . :where [?e :person/name "Zed"]] (d/db conn))]))
  (show-sorted "filter" (d/q '[:find [?n ...] :where [_ :person/name ?n]]
                      (d/filter db (fn [_ datom] (not= "Bob" (:v datom)))))))

;; ---------------------------------------------------------------- indexes
(let [db (d/db conn)]
  (show "datoms eavt" (map (fn [dt] [(d/ident db (:a dt)) (:v dt)]) (d/datoms db :eavt bob)))
  (show "datoms aevt" (map :v (d/datoms db :aevt :person/name)))
  (show "datoms avet" (map :v (d/datoms db :avet :person/name)))
  (show "datoms avet prefix" (map :e (d/datoms db :avet :person/email "bob@example.com")))
  (show "datoms vaet" (map (fn [dt] (:person/name (d/entity db (:e dt)))) (d/datoms db :vaet carol)))
  (show "seek-datoms" (take 2 (map :v (d/seek-datoms db :avet :person/name "C"))))
  (show "index-range" (map :v (d/index-range db :person/name "B" "D")))
  (show "datom seq" (let [dt (first (d/datoms db :eavt bob :person/name))]
                      [(nth dt 2) (:v dt) (= bob (:e dt))])))

;; ---------------------------------------------------------------- log
(let [txs (vec (seq (d/tx-range (d/log conn) nil nil)))]
  (show "log size" (count txs))
  (show "log t" (= (map :t txs) (sort (map :t txs))))
  (show "log range" (count (seq (d/tx-range (d/log conn) (:t (second txs)) (:t (nth txs 3))))))
  (show "log data" (count (:data (first txs)))))

;; ---------------------------------------------------------------- misc
(show "tempid parts" (map :part [(d/tempid :db.part/user) (d/tempid :db.part/db -5)]))
(show "tempid idx" (:idx (d/tempid :db.part/user -42)))
(show "squuid" (let [u (d/squuid)]
                 (< (Math/abs (- (d/squuid-time-millis u) (System/currentTimeMillis))) 5000)))
(show "entid-at" (d/entid-at (d/db conn) :db.part/user 1000))
(show "part" [(d/part bob) (d/part (d/t->tx 5))])
(show "database names" (some #{"features"} (d/get-database-names "datomic:mem://*")))
(show "delete" (d/delete-database uri))
(attempt "connect deleted" (d/connect uri))

(System/exit 0)
