;; Extensions a native program needs where the JVM would compile code at
;; runtime: transaction functions and query functions are Clojure fns.
;; Expected output is nitomic's own (test/expected/native.out).
(require '[datomic.api :as d])

(d/create-database "datomic:mem://native")
(def conn (d/connect "datomic:mem://native"))
@(d/transact conn [{:db/ident :account/id :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                   {:db/ident :account/balance :db/valueType :db.type/long
                    :db/cardinality :db.cardinality/one}])

(defn transfer [db from to amount]
  (let [balance (fn [id] (:account/balance (d/entity db [:account/id id])))]
    (when (< (balance from) amount)
      (throw (ex-info "Insufficient funds" {:db/error :bank/insufficient-funds})))
    [[:db/add [:account/id from] :account/balance (- (balance from) amount)]
     [:db/add [:account/id to] :account/balance (+ (balance to) amount)]]))

@(d/transact conn [{:db/ident :bank/transfer :db/fn (d/function transfer)}
                   {:account/id "a" :account/balance 100}
                   {:account/id "b" :account/balance 5}])
@(d/transact conn [[:bank/transfer "a" "b" 30]])
(prn (d/q '[:find ?id ?b :where [?e :account/id ?id] [?e :account/balance ?b]] (d/db conn)))
(prn (try @(d/transact conn [[:bank/transfer "b" "a" 1000]])
          (catch Exception e (:db/error (ex-data e)))))
(prn (d/invoke (d/db conn) :bank/transfer (d/db conn) "a" "b" 1))

(d/register-fn! 'bank/rich? (fn [b] (> b 50)))
(prn (d/q '[:find [?id ...] :where [?e :account/balance ?b] [(bank/rich? ?b)] [?e :account/id ?id]]
          (d/db conn)))
(prn (d/q '[:find ?x . :in $ ?f :where [(?f 20) ?x]] (d/db conn) (fn [n] (* 2 n))))
