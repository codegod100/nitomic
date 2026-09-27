;; Entities: lazy, associative views of one entity in one database value.
;; Attribute values are read on lookup, references come back as entities (or
;; as keywords when the target has a :db/ident), and :ns/_attr navigates
;; references backwards.
(ns nitomic.entity
  (:require [nitomic.db :as db]))

(declare ->Entity)

(defn- as-value [db a v]
  (if (db/ref-attr? db a)
    (or (db/ident-of db v) (->Entity db v false))
    v))

(defn- lookup [db eid k]
  (cond
    (= k :db/id) eid
    (not (keyword? k)) nil
    (db/reverse-attr? k)
    (let [a (db/entid db (db/forward-attr k))
          sources (keys (get-in db [:vaet eid a]))]
      (when (and a (seq sources))
        (if (db/component? db a)
          (->Entity db (first sources) false)
          (set (map #(->Entity db % false) sources)))))
    :else
    (let [a (db/entid db k)
          vs (keys (get-in db [:eavt eid a]))]
      (when (and a (seq vs))
        (if (db/many? db a)
          (set (map #(as-value db a %) vs))
          (as-value db a (first vs)))))))

(defn- entries [db eid]
  (for [a (sort (keys (get-in db [:eavt eid])))
        :let [k (db/ident-of db a)]
        :when k]
    [k (lookup db eid k)]))

(defn- touched-map
  "A touched entity prints its attributes, components included."
  [db eid]
  (into {:db/id eid}
        (map (fn [[k v]]
               (let [a (db/entid db k)]
                 [k (if (and (db/component? db a) (db/many? db a))
                      (set (map #(touched-map db (:db/id %)) v))
                      v)]))
             (entries db eid))))

(deftype Entity [db eid touched]
  clojure.lang.ILookup
  (valAt [this k] (lookup db eid k))
  (valAt [this k nf] (let [v (lookup db eid k)] (if (nil? v) nf v)))
  clojure.lang.Seqable
  (seq [this] (seq (entries db eid)))
  clojure.lang.Counted
  (count [this] (count (entries db eid)))
  clojure.lang.Associative
  (containsKey [this k] (some? (lookup db eid k)))
  datomic.Entity
  (db [this] db)
  (touch [this] (->Entity db eid true))
  (keySet [this] (set (map (comp str first) (entries db eid))))
  Object
  (equals [this o] (and (instance? Entity o) (= eid (:db/id o))))
  (hashCode [this] (hash eid))
  (toString [this]
    (pr-str (if touched (touched-map db eid) {:db/id eid}))))

(defn entity
  "The entity for an id, ident or lookup ref; nil only for an unresolvable
  ident or lookup ref, as in Datomic."
  [db x]
  (let [e (db/entid db x)]
    (when e (->Entity db e false))))

(defn entity? [x] (instance? Entity x))
