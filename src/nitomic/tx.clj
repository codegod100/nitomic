;; Transactions: expand list and map forms into primitive operations, resolve
;; temporary ids (upserting through unique identities), apply the operations
;; to the indexes, and keep the schema caches in step with schema datoms.
(ns nitomic.tx
  (:require [clojure.string :as str]
            [nitomic.db :as db]
            [nitomic.types :as t]))

;; Datomic numbers implicit tempids (maps without :db/id) from here upward.
(def ^:private implicit-counter (atom -9223301668109598134))

(defn- implicit-tempid []
  (t/tempid :db.part/user (swap! implicit-counter inc)))

(defn- temp? [x] (or (t/tempid? x) (string? x)))

;; ------------------------------------------------------------ expansion
(defn- coll-value?
  "Whether a map value holds several values for a cardinality-many attribute,
  as opposed to one value that happens to be a vector (a lookup ref)."
  [db a v]
  (and (db/many? db a)
       (or (set? v)
           (and (sequential? v)
                (not (and (db/ref-attr? db a) (db/lookup-ref? v)))))))

(declare expand-map)

(defn- expand-value
  "A map value for a ref attribute can be a nested entity map. Returns
  [value ops] where ops asserts the nested entity."
  [db a v]
  (if (and (map? v) (db/ref-attr? db a))
    (let [nested (if (contains? v :db/id) v (assoc v :db/id (implicit-tempid)))]
      [(:db/id nested) (expand-map db nested)])
    [v []]))

(defn- expand-map [db m]
  (let [e (if (contains? m :db/id) (:db/id m) (implicit-tempid))]
    (reduce
     (fn [ops [k v]]
       (cond
         (= k :db/id) ops
         (db/reverse-attr? k)
         (let [fa (db/attr-id db (db/forward-attr k))
               targets (if (or (set? v) (and (sequential? v) (not (db/lookup-ref? v))))
                         v [v])]
           (reduce (fn [ops x]
                     (let [[x nested] (expand-value db fa x)]
                       (conj (into ops nested) [:add x fa e])))
                   ops targets))
         :else
         (let [a (db/attr-id db k)
               vs (if (coll-value? db a v) v [v])]
           (reduce (fn [ops x]
                     (let [[x nested] (expand-value db a x)]
                       (into (conj ops [:add e a x]) nested)))
                   ops vs))))
     [] m)))

(defn- expand-list [db [op & args]]
  (case op
    :db/add
    (let [[e a v] args] [[:add e (db/attr-id db a) v]])
    :db/retract
    (let [[e a v] args]
      (if (= 2 (count args))
        [[:retract-attr e (db/attr-id db a)]]
        [[:retract e (db/attr-id db a) v]]))
    (:db.fn/retractEntity :db/retractEntity)
    [[:retract-entity (first args)]]
    (:db.fn/cas :db/cas)
    (let [[e a old new] args] [[:cas e (db/attr-id db a) old new]])
    (db/error :db.error/not-a-data-function
              (str "Unable to resolve data function: " (pr-str op)) {:op op})))

(defn expand
  "Primitive operations for transaction data: [:add e a v], [:retract e a v],
  [:retract-attr e a], [:retract-entity e] and [:cas e a old new], with
  attributes resolved to ids and entities still as given."
  [db tx-data]
  (reduce (fn [ops form]
            (cond
              (map? form) (into ops (expand-map db form))
              (sequential? form) (into ops (expand-list db (vec form)))
              (t/datom? form)
              (conj ops [(if (:added form) :add :retract) (:e form) (:a form) (:v form)])
              :else (db/error :db.error/invalid-tx-form
                              (str "Invalid transaction form: " (pr-str form)))))
          [] tx-data))

;; ------------------------------------------------------------ values
(defn- type-error [a-ident v vt]
  (db/error :db.error/wrong-type-for-attribute
            (str "Value " (pr-str v) " is not a valid :" (name vt)
                 " for attribute " a-ident)
            {:attr a-ident :value v}))

(defn- coerce
  "Check a non-ref value against its attribute's type."
  [db a v]
  (let [{:keys [value-type ident]} (db/attr db a)]
    (case value-type
      :db.type/string (if (string? v) v (type-error ident v value-type))
      :db.type/long (if (integer? v) v (type-error ident v value-type))
      :db.type/bigint (if (integer? v) v (type-error ident v value-type))
      :db.type/keyword (if (keyword? v) v (type-error ident v value-type))
      :db.type/boolean (if (boolean? v) v (type-error ident v value-type))
      :db.type/double (if (number? v) (double v) (type-error ident v value-type))
      :db.type/float (if (number? v) (double v) (type-error ident v value-type))
      :db.type/bigdec (if (number? v) v (type-error ident v value-type))
      :db.type/instant (if (inst? v) v (type-error ident v value-type))
      :db.type/uuid (if (uuid? v) v (type-error ident v value-type))
      :db.type/symbol (if (symbol? v) v (type-error ident v value-type))
      :db.type/uri (if (string? v) v (type-error ident v value-type))
      :db.type/tuple (if (vector? v) v (type-error ident v value-type))
      v)))

;; ------------------------------------------------------------ tempids
(defn- tx-temp? [x]
  (or (= x "datomic.tx") (and (t/tempid? x) (= :db.part/tx (:part x)))))

(defn- temps-in-order
  "Every temporary id in the operations, in order of first appearance."
  [db ops]
  (let [add (fn [acc x] (if (and (temp? x) (not (some #{x} acc))) (conj acc x) acc))]
    (reduce (fn [acc [op e a v]]
              (let [acc (add acc e)]
                (if (and (#{:add :retract} op) (db/ref-attr? db a)) (add acc v) acc)))
            [] ops)))

(defn- resolve-id
  "An entity position: a resolved id, or the temp itself while unresolved."
  [db tempids x]
  (cond
    (temp? x) (get tempids x x)
    :else (db/entid-strict db x)))

(defn- upsert
  "Resolve tempids whose assertions name an existing entity through a
  :db.unique/identity attribute, until nothing more resolves."
  [db ops tempids]
  (let [step (reduce
              (fn [tids [op e a v]]
                (if (and (= op :add) (temp? e) (not (contains? tids e))
                         (= :db.unique/identity (:unique (db/attr db a))))
                  (let [v (if (db/ref-attr? db a)
                            (if (temp? v) (get tids v) (db/entid db v))
                            v)
                        existing (when (some? v) (first (keys (get-in db [:avet a v]))))]
                    (if existing (assoc tids e existing) tids))
                  tids))
              tempids ops)]
    (if (= step tempids) tempids (recur db ops step))))

(defn- partition-id [db p]
  (let [pid (db/entid db p)]
    (when-not (and pid (contains? (db/partitions db) pid))
      (db/error :db.error/not-a-partition (str "Not a partition: " (pr-str p))))
    pid))

(defn- unify-temps
  "Tempids asserting the same :db.unique/identity value within one
  transaction name the same entity: alias each to the first."
  [db ops tempids]
  (let [claims (reduce (fn [acc [op e a v]]
                         (if (and (= op :add) (temp? e) (not (contains? tempids e))
                                  (= :db.unique/identity (:unique (db/attr db a))))
                           (update acc [a v] (fn [es] (if (some #{e} es) es (conj (or es []) e))))
                           acc))
                       {} ops)]
    (reduce (fn [aliases es]
              (let [canonical (get aliases (first es) (first es))]
                (reduce #(assoc %1 %2 canonical) aliases (rest es))))
            {} (vals claims))))

(defn- attribute-temps
  "Implicit and string tempids of entities that define attributes; Datomic
  puts those in the db partition."
  [ops]
  (set (for [[op e a] ops
             :when (and (= op :add) (= a db/a-value-type)
                        (or (string? e) (and (t/tempid? e) (< (:idx e) -9000000000000000000))))]
         e)))

(defn- allocate
  "Give every unresolved tempid a new entity id. Ids in the db partition come
  from their own counter; all others share the t counter, which is how
  Datomic numbers them."
  [db temps tempids aliases attr-temps tx counter next-db-id]
  (let [[tids counter ndb]
        (reduce
         (fn [[tids counter ndb] x]
           (cond
             (contains? tids x) [tids counter ndb]
             (contains? aliases x) [tids counter ndb]
             (tx-temp? x) [(assoc tids x tx) counter ndb]
             :else
             (let [pid (cond (contains? attr-temps x) db/db-part
                             (string? x) db/user-part
                             :else (partition-id db (:part x)))]
               (if (= pid db/db-part)
                 [(assoc tids x ndb) counter (inc ndb)]
                 [(assoc tids x (db/entid-at pid counter)) (inc counter) ndb]))))
         [tempids counter next-db-id] temps)
        tids (reduce (fn [tids [x canonical]] (assoc tids x (get tids canonical))) tids aliases)]
    [tids counter ndb]))

;; ------------------------------------------------------------ applying
(defn- assert-datom [st e a v]
  (let [w (:db st)
        tx (:tx st)
        attr (db/attr w a)]
    (when (:unique attr)
      (let [owner (first (keys (get-in w [:avet a v])))]
        (when (and owner (not= owner e))
          (db/error :db.error/unique-conflict
                    (str "Unique conflict: " (:ident attr) ", value: " (pr-str v)
                         " already held by: " owner " asserted for: " e)
                    {:attr (:ident attr) :value v}))))
    (-> st
        (assoc :db (db/index-add w (db/ref-attr? w a) e a v tx))
        (update :out conj [e a v tx true])
        (update :touched conj e))))

(defn- retract-datom [st e a v]
  (let [w (:db st)]
    (-> st
        (assoc :db (db/index-remove w (db/ref-attr? w a) e a v))
        (update :out conj [e a v (:tx st) false])
        (update :touched conj e))))

(defn- add [st e a v]
  (let [w (:db st)
        current (keys (get-in w [:eavt e a]))]
    (cond
      (db/has-datom? w e a v) st
      (db/many? w a) (assert-datom st e a v)
      :else
      (do
        (when (contains? (:asserted st) [e a])
          (db/error :db.error/datoms-conflict
                    (str "Two datoms in the same transaction conflict: "
                         (pr-str [e (db/ident-of w a) v]))
                    {:entity e :attr (db/ident-of w a)}))
        (-> (reduce (fn [st old] (retract-datom st e a old)) st current)
            (assert-datom e a v)
            (update :asserted conj [e a]))))))

(defn- entity-datoms
  "[e a v] of every datom retracting an entity removes: its own attributes,
  references to it, and its components, recursively."
  [w e]
  (let [own (for [[a vs] (get-in w [:eavt e]) [v _] vs] [e a v])
        refs (for [[a es] (get-in w [:vaet e]) [re _] es] [re a e])
        comps (for [[a v] own :when (db/component? w a)] v)]
    (concat own refs (mapcat #(entity-datoms w %) comps))))

(defn- apply-op [st [op e a v nv]]
  (let [w (:db st)]
    (case op
      :add (add st e a v)
      :retract (if (db/has-datom? w e a v) (retract-datom st e a v) st)
      :retract-attr
      (reduce (fn [st old] (retract-datom st e a old)) st (keys (get-in w [:eavt e a])))
      :retract-entity
      (reduce (fn [st [e a v]]
                (if (db/has-datom? (:db st) e a v) (retract-datom st e a v) st))
              st (distinct (entity-datoms w e)))
      :cas
      (let [current (first (keys (get-in w [:eavt e a])))]
        (when-not (= current v)
          (db/error :db.error/cas-failed
                    (str "Compare failed: " (pr-str v) " " (pr-str current))
                    {:expected v :actual current}))
        (add st e a nv)))))

(defn- refresh-schema
  "Recompute idents and attributes for entities whose schema datoms changed,
  installing new attributes as Datomic does implicitly."
  [st]
  (let [schema-es (distinct (for [[e a _ _ _] (:out st)
                                  :when (contains? db/schema-attr-ids a)]
                              e))]
    (reduce
     (fn [st e]
       (let [was-attr (db/attr (:db st) e)
             w (db/refresh-entity (:db st) e)
             now-attr (db/attr w e)
             st (assoc st :db w)]
         (when (and (not now-attr) (get-in w [:eavt e db/a-value-type]))
           (db/error :db.error/incomplete-install-attribute
                     (str "Attribute " e " requires :db/ident, :db/valueType"
                          " and :db/cardinality")))
         (if (and now-attr (not was-attr)
                  (not (db/has-datom? w 0 db/a-install-attribute e)))
           (assert-datom st 0 db/a-install-attribute e)
           st)))
     st schema-es)))

(defn- resolve-op
  "Replace temporary ids and entity identifiers with ids, and check values."
  [db tempids [op e a v nv]]
  (let [e (resolve-id db tempids e)
        refv (fn [x] (if (and (some? x) (db/ref-attr? db a)) (resolve-id db tempids x) x))]
    (case op
      (:add :retract) [op e a (if (db/ref-attr? db a) (refv v) (coerce db a v))]
      :cas [op e a (refv v) (if (db/ref-attr? db a) (refv nv) (coerce db a nv))]
      [op e a v])))

(defn transact
  "Apply transaction data to a database at instant now. Returns the
  transaction report, or throws an ex-info carrying :db/error."
  [db tx-data now]
  (when (or (:as-of-t db) (:since-t db) (:history db) (:filtered db))
    (db/error :db.error/transact-on-view "Cannot transact against a filtered database"))
  (let [t (:next-t db)
        tx (db/t->tx t)
        ops (expand db tx-data)
        temps (temps-in-order db ops)
        upserted (upsert db ops {})
        aliases (unify-temps db ops upserted)
        [tempids counter next-db-id] (allocate db temps upserted aliases (attribute-temps ops)
                                               tx (inc t) (:next-db-id db))
        ops (mapv #(resolve-op db tempids %) ops)
        user-inst (some (fn [[op e a v]] (when (and (= op :add) (= e tx) (= a db/a-tx-instant)) v))
                        ops)
        inst (or user-inst
                 (java.util.Date. (max (inst-ms now) (:last-inst db))))
        st {:db db :tx tx :out [] :asserted #{} :touched #{}}
        st (if user-inst st (assert-datom st tx db/a-tx-instant inst))
        st (reduce apply-op st ops)
        st (refresh-schema st)
        out (:out st)
        after (assoc (:db st)
                     :basis-t t
                     :next-t counter
                     :next-db-id next-db-id
                     :last-inst (inst-ms inst)
                     :log (conj (:log db) {:t t :tx tx :inst inst :data out}))
        report-tempids (into {} (map (fn [[k v]] [(if (t/tempid? k) (:idx k) k) v])
                                     (filter (fn [[k _]] (not (tx-temp? k))) tempids)))]
    {:db-before db
     :db-after after
     :tx-data (mapv t/datom out)
     :tempids report-tempids}))
