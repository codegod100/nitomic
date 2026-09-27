;; Datoms and temporary ids, the two small value types of the peer API.
(ns nitomic.types)

(deftype Datom [e a v tx added]
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [this k nf]
    (case k
      :e e
      :a a
      :v v
      :tx tx
      :added added
      nf))
  clojure.lang.Seqable
  (seq [this] (list e a v tx added))
  clojure.lang.Counted
  (count [this] 5)
  Object
  (equals [this o]
    (and (instance? Datom o)
         (= e (:e o)) (= a (:a o)) (= v (:v o)) (= tx (:tx o)) (= added (:added o))))
  (hashCode [this] (hash [e a v tx added]))
  (toString [this] (str "#datom" (pr-str [e a v tx added]))))

(defn datom
  ([[e a v tx added]] (->Datom e a v tx added))
  ([e a v tx added] (->Datom e a v tx added)))

(defn datom? [x] (instance? Datom x))

(deftype TempId [part idx]
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [this k nf]
    (case k
      :part part
      :idx idx
      nf))
  Object
  (equals [this o]
    (and (instance? TempId o) (= part (:part o)) (= idx (:idx o))))
  (hashCode [this] (hash [:tempid part idx]))
  (toString [this] (str "#db/id" (pr-str [part idx]))))

(defn tempid? [x] (instance? TempId x))

(def ^:private tempid-counter (atom -1000000))

(defn tempid
  "A new temporary id in a partition, or the one numbered n."
  ([part] (->TempId part (swap! tempid-counter dec)))
  ([part n] (->TempId part n)))
