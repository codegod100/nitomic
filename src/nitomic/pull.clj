;; The pull API: hierarchical selections of entity attributes.
(ns nitomic.pull
  (:require [nitomic.db :as db]))

(def ^:private default-limit 1000)

(defn- wildcard? [x] (or (= x '*) (= x "*")))

(defn- attr-spec
  "{:key k :attr id :reverse? bool :as name :limit n :default v :has-default bool}
  for a pattern element naming one attribute, with or without options."
  [db x]
  (let [[k opts] (cond
                   (keyword? x) [x {}]
                   (and (sequential? x) (#{'limit "limit"} (first x)))
                   [(second x) {:limit (nth x 2)}]
                   (and (sequential? x) (#{'default "default"} (first x)))
                   [(second x) {:default (nth x 2)}]
                   (sequential? x) [(first x) (apply hash-map (rest x))]
                   :else (throw (ex-info (str "Invalid pull attribute: " (pr-str x))
                                         {:db/error :db.error/invalid-pull})))]
    (if (= k :db/id)
      {:key k :db-id? true :as (get opts :as k)}
      (let [rev? (db/reverse-attr? k)
            a (db/attr-id db (if rev? (db/forward-attr k) k))]
        {:key k :attr a :reverse? rev?
         :as (get opts :as k)
         :limit (if (contains? opts :limit) (get opts :limit) default-limit)
         :has-default (contains? opts :default)
         :default (get opts :default)}))))

(declare pull-entity)

(defn- limited [limit xs] (if limit (take limit xs) xs))

(defn- ref-value
  "A referenced entity: pulled with the subpattern when there is one,
  recursively in full when it is a component, else just its id."
  [db spec sub e seen depth]
  (cond
    (some? sub)
    (if (contains? seen e)
      {:db/id e}
      (pull-entity db sub e seen depth))
    (and (not (:reverse? spec)) (db/component? db (:attr spec)))
    (pull-entity db ['*] e seen depth)
    :else {:db/id e}))

(defn- sorted-vals [vs]
  (sort-by identity (fn [a b] (try (compare a b) (catch Exception _ 0))) vs))

(defn- attr-value
  "The value an attribute contributes to an entity's pull result, or ::none."
  [db spec sub e seen depth]
  (let [a (:attr spec)]
    (if (:reverse? spec)
      (let [sources (sort (keys (get-in db [:vaet e a])))]
        (if (empty? sources)
          ::none
          (if (db/component? db a)
            (ref-value db spec sub (first sources) seen depth)
            (vec (map #(ref-value db spec sub % seen depth)
                      (limited (:limit spec) sources))))))
      (let [vs (sorted-vals (keys (get-in db [:eavt e a])))]
        (cond
          (empty? vs) ::none
          (db/ref-attr? db a)
          (if (db/many? db a)
            (vec (map #(ref-value db spec sub % seen depth) (limited (:limit spec) vs)))
            (ref-value db spec sub (first vs) seen depth))
          (db/many? db a) (vec (limited (:limit spec) vs))
          :else (first vs))))))

(defn- recursion
  "The subpattern for a map entry value: a pattern, ... or a depth limit."
  [pattern sub depth k]
  (cond
    (= sub '...) [pattern (update depth k (fnil inc 0)) true]
    (integer? sub)
    (if (>= (get depth k 0) sub)
      [nil depth false]
      [pattern (update depth k (fnil inc 0)) true])
    :else [sub depth false]))

(defn pull-entity [db pattern e seen depth]
  (let [seen (conj seen e)]
    (reduce
     (fn [m el]
       (cond
         (wildcard? el)
         (let [own (sort (keys (get-in db [:eavt e])))
               m (assoc m :db/id e)]
           (reduce (fn [m a]
                     (let [spec {:key (db/ident-of db a) :attr a :limit nil}
                           v (attr-value db spec nil e seen depth)]
                       (if (or (= v ::none) (contains? m (:key spec)))
                         m
                         (assoc m (:key spec) v))))
                   m own))
         (map? el)
         (reduce (fn [m [k sub]]
                   (let [spec (attr-spec db k)
                         [sub depth recursive?] (recursion pattern sub depth (:key spec))]
                     (if (and (nil? sub) (integer? (get el k)))
                       m
                       (let [v (attr-value db spec sub e seen depth)]
                         (if (= v ::none)
                           (if (:has-default spec) (assoc m (:as spec) (:default spec)) m)
                           (assoc m (:as spec) v))))))
                 m el)
         :else
         (let [spec (attr-spec db el)]
           (if (:db-id? spec)
             (assoc m (:as spec) e)
             (let [v (attr-value db spec nil e seen depth)]
               (if (= v ::none)
                 (if (:has-default spec) (assoc m (:as spec) (:default spec)) m)
                 (assoc m (:as spec) v)))))))
     {} pattern)))

(defn pull
  "Pull a pattern for one entity (id, ident or lookup ref). Nil when the
  entity has none of the pattern's attributes."
  [db pattern eid]
  (let [pattern (if (string? pattern) (read-string pattern) pattern)
        e (db/entid db eid)]
    (when e
      (let [m (pull-entity db pattern e #{} {})]
        (when (seq m) m)))))

(defn pull-many [db pattern eids]
  (mapv #(pull db pattern %) eids))
