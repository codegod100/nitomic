;; The database value: covering indexes over the current datoms, the schema
;; derived from them, and the transaction log they were built from.
;;
;; Indexes are nested persistent maps rather than sorted sets:
;;
;;   :eavt {e {a {v tx}}}     every current datom, by entity
;;   :aevt {a {e {v tx}}}     by attribute
;;   :avet {a {v {e tx}}}     by attribute and value
;;   :vaet {v {a {e tx}}}     reference datoms only, by target
;;
;; Lookups by any bound prefix are hash lookups; the sorted views that
;; d/datoms promises are produced on demand. A database is an immutable map,
;; so every basis a program holds on to stays valid, as in Datomic.
(ns nitomic.db
  (:require [clojure.string :as str]
            [nitomic.bootstrap :as boot]))

;; ------------------------------------------------------------ entity ids
(def part-shift 4398046511104)          ; 2^42: ids are partition << 42 | t
(def tx-part 3)
(def user-part 4)
(def db-part 0)
(def tx0 (* tx-part part-shift))        ; 13194139533312, the tx id of t 0

(defn t->tx [t] (+ tx0 t))
(defn tx->t [tx] (mod tx part-shift))
(defn part [eid] (quot eid part-shift))
(defn entid-at [p t] (+ (* p part-shift) t))

;; Attribute ids of the system schema, fixed by the bootstrap database.
(def a-ident 10)
(def a-install-partition 11)
(def a-install-value-type 12)
(def a-install-attribute 13)
(def a-value-type 40)
(def a-cardinality 41)
(def a-unique 42)
(def a-is-component 43)
(def a-index 44)
(def a-no-history 45)
(def a-tx-instant 50)
(def a-fulltext 51)
(def a-doc 62)
(def a-tuple-type 65)
(def a-tuple-types 66)
(def a-tuple-attrs 67)

(def schema-attr-ids
  #{a-ident a-value-type a-cardinality a-unique a-is-component a-index
    a-no-history a-fulltext a-tuple-type a-tuple-types a-tuple-attrs})

(defn error
  "Throw a Datomic-shaped error: an ex-info whose data carries :db/error."
  ([code msg] (error code msg {}))
  ([code msg data]
   (throw (ex-info msg (assoc data :db/error code)))))

;; ------------------------------------------------------------ index maps
(defn- dissoc-path
  "Remove the innermost key of a nested map path, pruning maps it empties."
  [m ks]
  (let [k (first ks)]
    (if (next ks)
      (let [child (dissoc-path (get m k) (rest ks))]
        (if (empty? child) (dissoc m k) (assoc m k child)))
      (dissoc m k))))

(defn ref-attr? [db a]
  (= :db.type/ref (get-in db [:attrs a :value-type])))

(defn index-add
  "Add the assertion [e a v tx] to every index it belongs in."
  [idx ref? e a v tx]
  (let [idx (-> idx
                (assoc-in [:eavt e a v] tx)
                (assoc-in [:aevt a e v] tx)
                (assoc-in [:avet a v e] tx))]
    (if ref? (assoc-in idx [:vaet v a e] tx) idx)))

(defn index-remove [idx ref? e a v]
  (let [idx (-> idx
                (update :eavt dissoc-path [e a v])
                (update :aevt dissoc-path [a e v])
                (update :avet dissoc-path [a v e]))]
    (if ref? (update idx :vaet dissoc-path [v a e]) idx)))

(defn has-datom? [db e a v]
  (contains? (get-in db [:eavt e a]) v))

;; ------------------------------------------------------------ schema
(defn ident-of [db eid] (get-in db [:ident-of eid]))

(defn- one [db e a] (first (keys (get-in db [:eavt e a]))))

(defn attr-from-entity
  "The schema map for an attribute entity, or nil if it is not one."
  [db eid]
  (let [vt (one db eid a-value-type)
        card (one db eid a-cardinality)
        ident (one db eid a-ident)]
    (when (and vt card ident)
      (let [unique (one db eid a-unique)]
        {:id eid
         :ident ident
         :value-type (ident-of db vt)
         :cardinality (ident-of db card)
         :unique (when unique (ident-of db unique))
         :is-component (true? (one db eid a-is-component))
         :indexed (true? (one db eid a-index))
         :no-history (true? (one db eid a-no-history))
         :fulltext (true? (one db eid a-fulltext))
         :tuple-attrs (one db eid a-tuple-attrs)
         :tuple-type (one db eid a-tuple-type)
         :tuple-types (one db eid a-tuple-types)}))))

(defn refresh-entity
  "Bring the ident and attribute caches up to date for one entity."
  [db eid]
  (let [old-ident (get-in db [:ident-of eid])
        new-ident (one db eid a-ident)
        db (if (= old-ident new-ident)
             db
             (let [db (if old-ident
                        (-> db
                            (update :idents dissoc old-ident)
                            (update :ident-of dissoc eid))
                        db)]
               (if new-ident
                 (-> db
                     (assoc-in [:idents new-ident] eid)
                     (assoc-in [:ident-of eid] new-ident))
                 db)))
        attr (attr-from-entity db eid)]
    (if attr
      (assoc-in db [:attrs eid] attr)
      (update db :attrs dissoc eid))))

(defn attr [db a] (get-in db [:attrs a]))
(defn many? [db a] (= :db.cardinality/many (get-in db [:attrs a :cardinality])))
(defn component? [db a] (get-in db [:attrs a :is-component]))

(defn partitions [db]
  (set (keys (get-in db [:eavt 0 a-install-partition]))))

;; ------------------------------------------------------------ id resolution
(defn lookup-ref? [x]
  (and (vector? x) (= 2 (count x)) (or (keyword? (first x)) (integer? (first x)))))

(defn entid
  "Resolve an entity identifier (id, ident or lookup ref) to an id, or nil."
  [db x]
  (cond
    (integer? x) x
    (keyword? x) (get-in db [:idents x])
    (lookup-ref? x)
    (let [a (entid db (first x))]
      (when-not (get-in db [:attrs a :unique])
        (error :db.error/lookup-ref-attr-not-unique
               (str "Attribute values not unique: " (first x))))
      (first (keys (get-in db [:avet a (second x)]))))
    :else nil))

(defn entid-strict [db x]
  (let [e (entid db x)]
    (when (nil? e)
      (if (lookup-ref? x)
        (error :db.error/not-an-entity
               (str "Unable to resolve entity: " (pr-str x)) {:entity x})
        (error :db.error/not-an-entity
               (str "Unable to resolve entity: " (pr-str x)) {:entity x})))
    e))

(defn attr-id
  "Resolve an attribute identifier to the id of an installed attribute."
  [db a]
  (let [id (entid db a)]
    (cond
      (nil? id) (error :db.error/not-an-entity
                       (str "Unable to resolve entity: " (pr-str a)) {:entity a})
      (get-in db [:attrs id]) id
      :else (error :db.error/not-an-attribute
                   (str (pr-str a) " is not an attribute") {:attr a}))))

(defn reverse-attr?
  "True for :ns/_name, the reverse spelling of a reference attribute."
  [k]
  (and (keyword? k) (str/starts-with? (name k) "_")))

(defn forward-attr [k]
  (keyword (namespace k) (subs (name k) 1)))

;; ------------------------------------------------------------ building
(defn empty-db []
  {:eavt {} :aevt {} :avet {} :vaet {}
   :attrs {} :idents {} :ident-of {}
   :log [] :boot []
   :basis-t 0 :next-t 0 :next-db-id 0 :last-inst 0})

(defn- boot-db
  "The bootstrap database, rebuilt from the datoms Datomic itself starts with."
  []
  (let [ident-datoms (filter #(= :db/ident (nth % 1)) boot/datoms)
        idents (into {} (map (fn [[e _ v _]] [v e]) ident-datoms))
        ;; bootstrap values are ids for refs; everything else is literal
        datoms (mapv (fn [[e a v tx]] [e (get idents a) v tx]) boot/datoms)
        refs (set (map first
                       (filter (fn [[e a v _]] (and (= a a-value-type) (= v 20)))
                               datoms)))
        idx (reduce (fn [idx [e a v tx]] (index-add idx (contains? refs a) e a v tx))
                    {} datoms)
        db (merge (empty-db) idx)
        es (distinct (map first datoms))
        ;; twice: attribute maps name their value types by ident
        db (reduce refresh-entity (reduce refresh-entity db es) es)]
    (assoc db
           :boot datoms
           :basis-t boot/basis-t
           :next-t 1000
           :next-db-id (inc boot/basis-t))))

(def bootstrap-db (boot-db))

(defn new-db [id]
  (assoc bootstrap-db :id id))

;; ------------------------------------------------------------ replay
(defn apply-tx-record
  "Apply a transaction that was already made, as recorded in storage:
  {:t :tx :inst :data [[e a v tx added] ...] :next-t :next-db-id}. The
  datoms go into the indexes in the order the transaction produced them,
  with reference attributes judged by the schema before it (an attribute
  can't be used in the transaction that installs it), then the entities
  whose schema changed are refreshed, as the transaction itself did."
  [db {:keys [t tx inst data next-t next-db-id]}]
  (let [w (reduce (fn [w [e a v _ added]]
                    (if added
                      (index-add w (ref-attr? db a) e a v tx)
                      (index-remove w (ref-attr? db a) e a v)))
                  db data)
        schema-es (distinct (for [[e a] data :when (contains? schema-attr-ids a)] e))
        w (reduce refresh-entity w schema-es)]
    (assoc w
           :basis-t t
           :next-t next-t
           :next-db-id next-db-id
           :last-inst (inst-ms inst)
           :log (conj (:log db) {:t t :tx tx :inst inst :data data}))))

;; ------------------------------------------------------------ datoms
(defn current-datoms
  "Every current datom of a database as [e a v tx] vectors."
  [db]
  (for [[e avs] (:eavt db) [a vs] avs [v tx] vs] [e a v tx]))

(defn- rebuild
  "A database with the same schema and log but only the given current datoms."
  [db datoms]
  (let [idx (reduce (fn [idx [e a v tx]] (index-add idx (ref-attr? db a) e a v tx))
                    {:eavt {} :aevt {} :avet {} :vaet {}} datoms)]
    (merge db idx)))

(defn- t-of [x]
  ;; as-of/since accept a t, a transaction id, or an instant
  (cond
    (inst? x) x
    (>= x tx0) (tx->t x)
    :else x))

(defn- tx-t-at-inst
  "The last t whose transaction happened at or before the instant."
  [db inst]
  (let [ms (inst-ms inst)
        txs (filter (fn [rec] (<= (inst-ms (:inst rec)) ms)) (:log db))]
    (if (seq txs) (:t (last txs)) boot/basis-t)))

(defn resolve-t [db x]
  (let [t (t-of x)]
    (if (inst? t) (tx-t-at-inst db t) t)))

(defn- replay
  "Current datoms after applying the bootstrap and every logged transaction
  accepted by keep-tx?."
  [db keep-tx?]
  (let [state (reduce (fn [s [e a v tx]] (assoc s [e a v] tx)) {} (:boot db))
        state (reduce
               (fn [s rec]
                 (if (keep-tx? (:t rec))
                   (reduce (fn [s [e a v tx added]]
                             (if added (assoc s [e a v] tx) (dissoc s [e a v])))
                           s (:data rec))
                   s))
               state (:log db))]
    (map (fn [[[e a v] tx]] [e a v tx]) state)))

(defn as-of [db t]
  (let [t (resolve-t db t)
        ;; views compose: an as-of of a since db stays within the since window
        since (:since-t db)]
    (let [v (assoc (rebuild db (replay db (fn [tt] (<= tt t)))) :as-of-t t)]
      (if since
        (rebuild v (filter (fn [[_ _ _ tx]] (> (tx->t tx) since)) (current-datoms v)))
        v))))

(defn since [db t]
  (let [t (resolve-t db t)]
    (-> (rebuild db (filter (fn [[_ _ _ tx]] (> (tx->t tx) t)) (current-datoms db)))
        (assoc :since-t t))))

(defn history-datoms
  "Every assertion and retraction ever made, as [e a v tx added]."
  [db]
  (concat (map (fn [[e a v tx]] [e a v tx true]) (:boot db))
          (mapcat :data (:log db))))

(defn history [db]
  (let [limit (or (:as-of-t db) (:basis-t db))
        lower (:since-t db)
        ds (filter (fn [[_ _ _ tx _]]
                     (let [t (tx->t tx)]
                       (and (<= t limit) (or (nil? lower) (> t lower)))))
                   (history-datoms db))
        by-e (group-by first ds)
        by-a (group-by second ds)]
    (assoc db :history {:all (vec ds) :e by-e :a by-a})))
