;; Datomic's getting-started walkthrough (samples/seattle/getting-started.clj
;; in the Datomic Pro distribution), as one program. It runs unchanged on the
;; JVM against Datomic Pro and natively on clonim against nitomic; results are
;; printed in a canonical order so the two outputs can be compared line by
;; line (see test/run.sh).
(require '[datomic.api :as d]
         '[datomic.db]
         '[clojure.string :as str])

;; ---------------------------------------------------------------- output

(defn- canon-str [x]
  (cond
    (map? x) (str "{" (str/join ", " (sort (map (fn [[k v]] (str (canon-str k) " " (canon-str v))) x))) "}")
    (set? x) (str "#{" (str/join " " (sort (map canon-str x))) "}")
    (vector? x) (str "[" (str/join " " (map canon-str x)) "]")
    (seq? x) (str "(" (str/join " " (map canon-str x)) ")")
    (inst? x) "#inst"
    :else (pr-str x)))

(defn show
  "Print a labelled result; an unordered collection of results is sorted."
  [label x]
  (println (str label ":") (canon-str x)))

(defn show-sorted [label xs]
  (println (str label ":") (str "(" (str/join " " (sort (map canon-str xs))) ")")))

(defn read-edn [path]
  (binding [*data-readers* {'db/id datomic.db/id-literal}]
    (read-string (slurp path))))

;; ---------------------------------------------------------------- setup
(def uri "datomic:mem://seattle")
(show "create-database" (d/create-database uri))
(def conn (d/connect uri))

(def schema-tx (read-edn "examples/seattle/seattle-schema.edn"))
(show "first schema statement" (first schema-tx))
(def schema-report @(d/transact conn schema-tx))
(show "schema tx-data count" (count (:tx-data schema-report)))

(def data-tx (read-edn "examples/seattle/seattle-data0.edn"))
(show "first data statement" (dissoc (first data-tx) :db/id))
(show "second data statement" (dissoc (second data-tx) :db/id :neighborhood/district))
(def data-report @(d/transact conn data-tx))
(show "data tx-data count" (count (:tx-data data-report)))

;; ---------------------------------------------------------------- queries
(def results (d/q '[:find ?c :where [?c :community/name]] (d/db conn)))
(show "communities" (count results))

(def id (ffirst (sort-by first results)))
(def entity (-> conn d/db (d/entity id)))
(show "entity keys" (set (keys entity)))
(show "entity name" (:community/name entity))

(def pull-results (d/q '[:find (pull ?c [*]) :where [?c :community/name]] (d/db conn)))
(show "pull results" (count pull-results))
(show "a pulled community"
      (let [m (ffirst (filter #(= "belltown" (:community/name (first %))) pull-results))]
        (-> m (dissoc :db/id) (update :community/neighborhood keys)
            (update :community/orgtype keys) (update :community/type #(map keys %)))))

(let [db (d/db conn)]
  (show-sorted "community names" (map #(:community/name (d/entity db (first %))) results)))

(let [db (d/db conn)]
  (show-sorted "names and neighborhoods"
               (map #(let [entity (d/entity db (first %))]
                       [(:community/name entity)
                        (-> entity :community/neighborhood :neighborhood/name)])
                    results)))

(def community (d/entity (d/db conn) (ffirst (sort-by first results))))
(def neighborhood (:community/neighborhood community))
(def communities (:community/_neighborhood neighborhood))
(show-sorted "communities in the same neighborhood" (map :community/name communities))

(show-sorted "community names, coll find"
             (d/q '[:find [?n ...] :where [_ :community/name ?n]] (d/db conn)))

(show-sorted "names with urls"
             (d/q '[:find ?n (pull ?c [:community/url])
                    :where [?c :community/name ?n]]
                  (d/db conn)))

(show-sorted "belltown categories"
             (d/q '[:find [?c ...]
                    :where
                    [?e :community/name "belltown"]
                    [?e :community/category ?c]]
                  (d/db conn)))

(show-sorted "twitter feeds"
             (d/q '[:find [?n ...]
                    :where
                    [?c :community/name ?n]
                    [?c :community/type :community.type/twitter]]
                  (d/db conn)))

(show-sorted "NE region"
             (d/q '[:find [?c_name ...]
                    :where
                    [?c :community/name ?c_name]
                    [?c :community/neighborhood ?n]
                    [?n :neighborhood/district ?d]
                    [?d :district/region :region/ne]]
                  (d/db conn)))

(show-sorted "names and regions"
             (d/q '[:find ?c_name ?r_name
                    :where
                    [?c :community/name ?c_name]
                    [?c :community/neighborhood ?n]
                    [?n :neighborhood/district ?d]
                    [?d :district/region ?r]
                    [?r :db/ident ?r_name]]
                  (d/db conn)))

(def query-by-type '[:find [?n ...]
                     :in $ ?t
                     :where
                     [?c :community/name ?n]
                     [?c :community/type ?t]])

(def query-by-type-with-pull '[:find (pull ?c [:community/name])
                               :in $ ?t
                               :where
                               [?c :community/type ?t]])

(show-sorted "by type: twitter" (d/q query-by-type (d/db conn) :community.type/twitter))
(show-sorted "by type: facebook" (d/q query-by-type (d/db conn) :community.type/facebook-page))
(show-sorted "by type with pull: twitter"
             (d/q query-by-type-with-pull (d/db conn) :community.type/twitter))
(show-sorted "by type with pull: facebook"
             (d/q query-by-type-with-pull (d/db conn) :community.type/facebook-page))

(show-sorted "collection input"
             (d/q '[:find ?n ?t
                    :in $ [?t ...]
                    :where
                    [?c :community/name ?n]
                    [?c :community/type ?t]]
                  (d/db conn)
                  [:community.type/facebook-page :community.type/twitter]))

(show-sorted "relation input"
             (d/q '[:find ?n ?t ?ot
                    :in $ [[?t ?ot]]
                    :where
                    [?c :community/name ?n]
                    [?c :community/type ?t]
                    [?c :community/orgtype ?ot]]
                  (d/db conn)
                  [[:community.type/email-list :community.orgtype/community]
                   [:community.type/website :community.orgtype/commercial]]))

(show-sorted "names before C"
             (d/q '[:find [?n ...]
                    :where
                    [?c :community/name ?n]
                    [(.compareTo ?n "C") ?res]
                    [(< ?res 0)]]
                  (d/db conn)))

(show "fulltext Wallingford"
      (d/q '[:find ?n .
             :where
             [(fulltext $ :community/name "Wallingford") [[?e ?n]]]]
           (d/db conn)))

(show-sorted "fulltext food websites"
             (d/q '[:find ?name ?cat
                    :in $ ?type ?search
                    :where
                    [?c :community/name ?name]
                    [?c :community/type ?type]
                    [(fulltext $ :community/category ?search) [[?c ?cat]]]]
                  (d/db conn)
                  :community.type/website
                  "food"))

;; ---------------------------------------------------------------- rules
(let [rules '[[[twitter ?c]
               [?c :community/type :community.type/twitter]]]]
  (show-sorted "rule: twitter"
               (d/q '[:find [?n ...]
                      :in $ %
                      :where
                      [?c :community/name ?n]
                      (twitter ?c)]
                    (d/db conn)
                    rules)))

(let [rules '[[[region ?c ?r]
               [?c :community/neighborhood ?n]
               [?n :neighborhood/district ?d]
               [?d :district/region ?re]
               [?re :db/ident ?r]]]]
  (show-sorted "rule: NE"
               (d/q '[:find [?n ...]
                      :in $ %
                      :where
                      [?c :community/name ?n]
                      [region ?c :region/ne]]
                    (d/db conn)
                    rules))
  (show-sorted "rule: SW"
               (d/q '[:find [?n ...]
                      :in $ %
                      :where
                      [?c :community/name ?n]
                      [region ?c :region/sw]]
                    (d/db conn)
                    rules)))

(let [rules '[[[region ?c ?r]
               [?c :community/neighborhood ?n]
               [?n :neighborhood/district ?d]
               [?d :district/region ?re]
               [?re :db/ident ?r]]
              [[social-media ?c]
               [?c :community/type :community.type/twitter]]
              [[social-media ?c]
               [?c :community/type :community.type/facebook-page]]
              [[northern ?c]
               (region ?c :region/ne)]
              [[northern ?c]
               (region ?c :region/n)]
              [[northern ?c]
               (region ?c :region/nw)]
              [[southern ?c]
               (region ?c :region/sw)]
              [[southern ?c]
               (region ?c :region/s)]
              [[southern ?c]
               (region ?c :region/se)]]]
  (show-sorted "rule: southern social media"
               (d/q '[:find [?n ...]
                      :in $ %
                      :where
                      [?c :community/name ?n]
                      (southern ?c)
                      (social-media ?c)]
                    (d/db conn)
                    rules)))

;; ---------------------------------------------------------------- time
(def tx-instants (reverse (sort (d/q '[:find [?when ...] :where [_ :db/txInstant ?when]]
                                     (d/db conn)))))
(show "transaction instants" (count tx-instants))

(def data-tx-date (first tx-instants))
(def schema-tx-date (second tx-instants))

(def communities-query '[:find [?c ...] :where [?c :community/name]])

(let [db-asof-schema (-> conn d/db (d/as-of schema-tx-date))]
  (show "as of schema" (count (d/q communities-query db-asof-schema))))

(let [db-asof-data (-> conn d/db (d/as-of data-tx-date))]
  (show "as of data" (count (d/q communities-query db-asof-data))))

(let [db-since-data (-> conn d/db (d/since schema-tx-date))]
  (show "since schema" (count (d/q communities-query db-since-data))))

(let [db-since-data (-> conn d/db (d/since data-tx-date))]
  (show "since data" (count (d/q communities-query db-since-data))))

(def new-data-tx (read-edn "examples/seattle/seattle-data1.edn"))

(let [db-if-new-data (-> conn d/db (d/with new-data-tx) :db-after)]
  (show "with new data" (count (d/q communities-query db-if-new-data))))

(show "current" (count (d/q communities-query (d/db conn))))

@(d/transact conn new-data-tx)

(show "after new data" (count (d/q communities-query (d/db conn))))

(let [db-since-data (-> conn d/db (d/since data-tx-date))]
  (show "since first data" (count (d/q communities-query db-since-data))))

;; ---------------------------------------------------------------- changes
(def partition-report
  @(d/transact conn [{:db/id (d/tempid :db.part/db)
                      :db/ident :communities
                      :db.install/_partition :db.part/db}]))
(show "partition installed" (contains? (set (map :v (:tx-data partition-report)))
                                       :communities))

(def easton-report
  @(d/transact conn [{:db/id (d/tempid :communities)
                      :community/name "Easton"}]))
(def easton-created (d/q '[:find ?id . :where [?id :community/name "Easton"]] (d/db conn)))
(show "Easton partition is :communities"
      (= (d/part easton-created) (d/entid (d/db conn) :communities)))

(def belltown-id (d/q '[:find ?id .
                        :where
                        [?id :community/name "belltown"]]
                      (d/db conn)))

@(d/transact conn [{:db/id belltown-id
                    :community/category "free stuff"}])
(show-sorted "belltown categories after add"
             (:community/category (d/entity (d/db conn) belltown-id)))

@(d/transact conn [[:db/retract belltown-id :community/category "free stuff"]])
(show-sorted "belltown categories after retract"
             (:community/category (d/entity (d/db conn) belltown-id)))

(def easton-id (d/q '[:find ?id .
                      :where
                      [?id :community/name "Easton"]]
                    (d/db conn)))

@(d/transact conn [[:db.fn/retractEntity easton-id]])
(show "Easton after retractEntity"
      (d/q '[:find ?id . :where [?id :community/name "Easton"]] (d/db conn)))

(def queue (d/tx-report-queue conn))

@(d/transact conn [{:db/id (d/tempid :communities)
                    :community/name "Easton"}])

(when-let [report (.poll queue)]
  (show-sorted "tx report from queue"
               (map (fn [[e aname v added]]
                      [(if (= 3 (d/part e)) :tx (d/part e)) aname
                       (if (inst? v) :inst v) added])
                    (d/q '[:find ?e ?aname ?v ?added
                           :in $ [[?e ?a ?v _ ?added]]
                           :where
                           [?e ?a ?v _ ?added]
                           [?a :db/ident ?aname]]
                         (:db-after report)
                         (:tx-data report)))))
(show "queue empty after poll" (.poll queue))

(System/exit 0)
