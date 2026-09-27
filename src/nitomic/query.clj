;; Datalog. Clauses run in the order written, as in Datomic: each one extends
;; or filters a set of rows (maps from variables to values), and a data
;; pattern finds its datoms through whichever index its bound positions pick.
;; Rules are expanded top-down with a per-query table of calls in progress,
;; which is enough for recursive rules over acyclic and cyclic graphs alike.
(ns nitomic.query
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [nitomic.db :as db]
            [nitomic.pull :as pull]
            [nitomic.types :as t]))

;; ------------------------------------------------------------ syntax
(defn qvar? [x] (and (symbol? x) (str/starts-with? (name x) "?")))
(defn src-var? [x] (and (symbol? x) (str/starts-with? (name x) "$")))
(defn- blank? [x] (and (symbol? x) (= "_" (name x))))
(defn- rules-var? [x] (= x '%))

(defn- vars-in [form]
  (cond
    (qvar? form) #{form}
    (coll? form) (reduce (fn [acc x] (into acc (vars-in x))) #{} form)
    :else #{}))

(defn- parse-query
  "The map form of a query, from either the list or the map syntax."
  [q]
  (let [q (if (string? q) (read-string q) q)]
    (if (map? q)
      q
      (loop [m {} k nil xs (seq q)]
        (if (empty? xs)
          m
          (let [x (first xs)]
            (if (keyword? x)
              (recur (assoc m x []) x (rest xs))
              (recur (update m k conj x) k (rest xs)))))))))

(defn- parse-find-elem [x]
  (cond
    (qvar? x) {:kind :var :var x}
    (and (seq? x) (= 'pull (first x)))
    (let [[_ a b c] x]
      (if c
        {:kind :pull :src a :var b :pattern c}
        {:kind :pull :src '$ :var a :pattern b}))
    (seq? x) {:kind :agg :fn (first x) :args (vec (butlast (rest x))) :var (last x)}
    :else (throw (ex-info (str "Invalid find element: " (pr-str x)) {:db/error :db.error/invalid-find}))))

(defn- parse-find
  "[shape elements]: shape is :rel, :coll, :tuple or :scalar."
  [find]
  (let [find (vec find)]
    (cond
      (and (= 2 (count find)) (= '. (second find)))
      [:scalar [(parse-find-elem (first find))]]
      (and (= 1 (count find)) (vector? (first find)))
      (let [inner (first find)]
        (if (and (= 2 (count inner)) (= '... (second inner)))
          [:coll [(parse-find-elem (first inner))]]
          [:tuple (mapv parse-find-elem inner)]))
      :else [:rel (mapv parse-find-elem find)])))

;; ------------------------------------------------------------ functions
(defn- str-method [f] (fn [s & args] (apply f s args)))

(def builtins
  {'= = '== = 'not= not= '!= not= '< < '> > '<= <= '>= >=
   '+ + '- - '* * '/ / 'quot quot 'rem rem 'mod mod 'inc inc 'dec dec
   'max max 'min min 'identity identity 'str str 'subs subs 'count count
   'vector vector 'list list 'hash-map hash-map 'keyword keyword 'name name
   'namespace namespace 'even? even? 'odd? odd? 'zero? zero? 'pos? pos? 'neg? neg?
   'nil? nil? 'some? some? 'true? true? 'false? false? 'not not 'empty? empty?
   'contains? contains? 'get get 'first first 'second second 'last last
   'compare compare 're-find re-find 'range range 'abs abs 'double double 'long long
   'int int 'boolean boolean 'string? string? 'number? number? 'keyword? keyword?
   'inst? inst? 'uuid? uuid? 'inst-ms inst-ms
   'clojure.string/starts-with? str/starts-with? 'clojure.string/ends-with? str/ends-with?
   'clojure.string/includes? str/includes? 'clojure.string/lower-case str/lower-case
   'clojure.string/upper-case str/upper-case 'clojure.string/blank? str/blank?
   'clojure.core/= = 'clojure.core/< < 'clojure.core/> > 'clojure.core/str str
   '.compareTo compare
   '.startsWith (str-method str/starts-with?)
   '.endsWith (str-method str/ends-with?)
   '.contains (str-method str/includes?)
   '.toLowerCase (str-method str/lower-case)
   '.toUpperCase (str-method str/upper-case)
   '.length count
   '.getTime inst-ms
   'tuple vector
   'untuple identity
   'ground identity})

(def ^:private user-fns (atom {}))

(defn register-fn!
  "Make a function callable from query and rule clauses under a symbol. On
  the JVM Datomic resolves any namespace-qualified symbol; a native program
  has no runtime symbol table, so functions are registered by name."
  [sym f]
  (swap! user-fns assoc sym f)
  sym)

(defn- tokens
  "Lower-cased word tokens, the way a standard fulltext analyzer splits text."
  [s]
  (loop [cs (seq s) cur [] out []]
    (if (empty? cs)
      (if (seq cur) (conj out (apply str cur)) out)
      (let [c (first cs)]
        (if (or (Character/isLetter c) (Character/isDigit c))
          (recur (rest cs) (conj cur c) out)
          (recur (rest cs) [] (if (seq cur) (conj out (apply str cur)) out)))))))

(def ^:private stop-words
  #{"a" "an" "and" "are" "as" "at" "be" "but" "by" "for" "if" "in" "into" "is"
    "it" "no" "not" "of" "on" "or" "such" "that" "the" "their" "then" "there"
    "these" "they" "this" "to" "was" "will" "with"})

(defn- fulltext-match? [terms text]
  (let [words (map str/lower-case (tokens text))]
    (some (fn [term]
            (if (str/ends-with? term "*")
              (let [prefix (subs term 0 (dec (count term)))]
                (some #(str/starts-with? % prefix) words))
              (some #(= term %) words)))
          terms)))

(defn- fulltext [db attr query]
  (let [a (db/attr-id db attr)
        terms (remove stop-words
                      (map str/lower-case
                           (remove str/blank?
                                   (str/split (str/replace query "\"" " ") " "))))]
    (vec (for [[e vs] (get-in db [:aevt a])
               [v tx] vs
               :when (and (string? v) (fulltext-match? terms v))]
           [e v tx 1.0]))))

(defn- resolve-fn [ctx row f]
  (cond
    (qvar? f) (get row f)
    (fn? f) f
    (contains? builtins f) (get builtins f)
    (contains? @user-fns f) (get @user-fns f)
    :else (throw (ex-info (str "Unable to resolve symbol: " f " in this context")
                          {:db/error :db.error/invalid-call :fn f}))))

(defn- arg-value [ctx row x]
  (cond
    (qvar? x)
    (if (contains? row x)
      (get row x)
      (throw (ex-info (str "Insufficient binding of db clause: " x " would cause full scan")
                      {:db/error :db.error/insufficient-binding :var x})))
    (src-var? x) (get-in ctx [:sources x])
    :else x))

;; ------------------------------------------------------------ binding
(defn- unify
  "Bind a variable in a row, or check it against its existing binding."
  [row v x]
  (cond
    (or (nil? v) (blank? v)) row
    (contains? row v) (when (= (get row v) x) row)
    :else (assoc row v x)))

(defn- bind-form
  "Rows produced by binding a value to a binding form: ?x, [?a ?b],
  [?x ...] or [[?a ?b]]."
  [row form value]
  (cond
    (or (qvar? form) (blank? form))
    (let [r (unify row form value)] (if r [r] []))
    (and (vector? form) (= 2 (count form)) (= '... (second form)))
    (keep #(unify row (first form) %) value)
    (and (vector? form) (= 1 (count form)) (vector? (first form)))
    (mapcat #(bind-form row (first form) %) value)
    (vector? form)
    (let [vs (vec value)]
      (if (< (count vs) (count form))
        []
        (let [r (reduce (fn [r [f x]] (when r (unify r f x)))
                        row (map vector form vs))]
          (if r [r] []))))
    :else (throw (ex-info (str "Invalid binding form: " (pr-str form))
                          {:db/error :db.error/invalid-binding}))))

;; ------------------------------------------------------------ data patterns
(defn- search
  "[e a v tx added] of the datoms matching the bound positions."
  [db e a v tx]
  (if-let [h (:history db)]
    (let [candidates (cond (some? e) (get-in h [:e e])
                           (some? a) (get-in h [:a a])
                           :else (:all h))]
      (filter (fn [[de da dv dtx]]
                (and (or (nil? e) (= e de)) (or (nil? a) (= a da))
                     (or (nil? v) (= v dv)) (or (nil? tx) (= tx dtx))))
              candidates))
    (let [ds (cond
               (and (some? e) (some? a))
               (let [vs (get-in db [:eavt e a])]
                 (if (some? v)
                   (if (contains? vs v) [[e a v (get vs v)]] [])
                   (for [[v tx] vs] [e a v tx])))
               (some? e)
               (for [[a vs] (get-in db [:eavt e]) [dv tx] vs
                     :when (or (nil? v) (= v dv))]
                 [e a dv tx])
               (and (some? a) (some? v))
               (for [[de tx] (get-in db [:avet a v])] [de a v tx])
               (some? a)
               (for [[de vs] (get-in db [:aevt a]) [dv tx] vs] [de a dv tx])
               (some? v)
               (concat
                (for [[da es] (get-in db [:vaet v]) [de tx] es] [de da v tx])
                (for [[da vs] (:avet db) :when (not (db/ref-attr? db da))
                      [de tx] (get vs v)]
                  [de da v tx]))
               :else (db/current-datoms db))]
      (keep (fn [[e a v dtx]]
              (when (or (nil? tx) (= tx dtx)) [e a v dtx true]))
            ds))))

(defn- unify-some
  "Bind a pattern position's variable. Variables the row already bound were
  used to search, and may hold an ident where the datom holds an id, so they
  are left as they are."
  [row row0 v x]
  (cond
    (nil? row) nil
    (not (qvar? v)) row
    (contains? row0 v) row
    :else (unify row v x)))

(defn- resolve-e [db x]
  (cond
    (nil? x) nil
    (or (keyword? x) (vector? x)) (or (db/entid db x) -1)
    :else x))

(defn- match-db-pattern [ctx src rows pattern]
  (let [[pe pa pv ptx padded] pattern
        const (fn [row x] (cond (qvar? x) (get row x) (blank? x) nil (nil? x) nil :else x))]
    (mapcat
     (fn [row]
       (let [e (resolve-e src (const row pe))
             a0 (const row pa)
             a (cond (nil? a0) nil
                     (keyword? a0) (or (db/entid src a0)
                                       (throw (ex-info (str "Unable to resolve entity: " a0)
                                                       {:db/error :db.error/not-an-entity})))
                     :else a0)
             v0 (const row pv)
             v (if (and (some? v0) (some? a) (db/ref-attr? src a))
                 (resolve-e src v0)
                 v0)
             tx (const row ptx)
             added (const row padded)]
         (keep (fn [[de da dv dtx dadded]]
                 (when (or (nil? added) (= added dadded))
                   (-> row
                       (unify-some row pe de)
                       (unify-some row pa da)
                       (unify-some row pv dv)
                       (unify-some row ptx dtx)
                       (unify-some row padded dadded))))
               (search src e a v tx))))
     rows)))

(defn- match-coll-pattern
  "A pattern against a collection source: each element is a tuple."
  [rows coll pattern]
  (mapcat (fn [row]
            (keep (fn [tuple]
                    (let [tuple (vec tuple)]
                      (reduce (fn [r [p x]]
                                (cond (nil? r) nil
                                      (qvar? p) (unify r p x)
                                      (blank? p) r
                                      (= p x) r
                                      :else nil))
                              row (map vector pattern tuple))))
                  coll))
          rows))

;; ------------------------------------------------------------ clauses
(declare eval-clauses)

(defn- rule-call? [ctx clause]
  (and (sequential? clause) (symbol? (first clause))
       (contains? (:rules ctx) (first clause))))

(defn- call-rule
  "Rows extended by a rule's results. Each definition runs in its own scope
  seeded with the call's bound arguments; a call already in progress with the
  same bound arguments contributes nothing, which cuts recursive cycles."
  [ctx rows [rname & args]]
  (mapcat
   (fn [row]
     (let [bound (mapv (fn [x] (cond (qvar? x) (get row x ::unbound)
                                     (blank? x) ::unbound
                                     :else x))
                       args)
           key [rname bound]
           memo (:memo ctx)]
       (if (contains? @(:active ctx) key)
         []
         (let [results
               (if (contains? @memo key)
                 (get @memo key)
                 (do
                   (swap! (:active ctx) conj key)
                   (let [rs (vec (distinct
                                  (mapcat
                                   (fn [[head body]]
                                     (let [scope (reduce (fn [s [hv b]]
                                                           (if (= b ::unbound) s (assoc s hv b)))
                                                         {} (map vector head bound))]
                                       (map (fn [r] (mapv #(get r % ::unbound) head))
                                            (eval-clauses ctx [scope] body))))
                                   (get-in ctx [:rules rname]))))]
                     (swap! (:active ctx) disj key)
                     (swap! memo assoc key rs)
                     rs)))]
           (keep (fn [vals]
                   (reduce (fn [r [x val]]
                             (cond (nil? r) nil
                                   (= val ::unbound) r
                                   (qvar? x) (unify r x val)
                                   (blank? x) r
                                   (= x val) r
                                   :else nil))
                           row (map vector args vals)))
                 results)))))
   rows))

(defn- special-fn
  "The built-ins that read the database: [:val result], or nil."
  [f argv]
  (case f
    get-else
    (let [[src e a dflt] argv
          vs (keys (get-in src [:eavt (resolve-e src e) (db/attr-id src a)]))]
      [:val (if (seq vs) (first vs) dflt)])
    get-some
    (let [[src e & attrs] argv
          e (resolve-e src e)]
      [:val (some (fn [a]
                    (let [vs (keys (get-in src [:eavt e (db/attr-id src a)]))]
                      (when (seq vs) [a (first vs)])))
                  attrs)])
    missing?
    (let [[src e a] argv]
      [:val (empty? (get-in src [:eavt (resolve-e src e) (db/attr-id src a)]))])
    fulltext
    (let [[src attr q] argv]
      [:val (fulltext src attr q)])
    nil))

(defn- eval-fn-clause [ctx rows [[f & args] bform]]
  (mapcat
   (fn [row]
     (let [argv (mapv #(arg-value ctx row %) args)
           special (special-fn f argv)
           result (if special
                    (second special)
                    (apply (resolve-fn ctx row f) argv))]
       (cond
         (nil? bform) (if (and (some? result) (not= false result)) [row] [])
         (nil? result) []
         :else (bind-form row bform result))))
   rows))

(defn- branch-clauses [branch]
  (if (and (seq? branch) (= 'and (first branch))) (rest branch) [branch]))

(defn- eval-clause [ctx rows clause]
  (cond
    ;; [(f args) binding?]
    (and (vector? clause) (seq? (first clause)))
    (eval-fn-clause ctx rows clause)

    (rule-call? ctx clause) (call-rule ctx rows clause)

    (and (seq? clause) (= 'not (first clause)))
    (filter (fn [row] (empty? (eval-clauses ctx [row] (rest clause)))) rows)

    (and (seq? clause) (= 'not-join (first clause)))
    (let [[_ vars & body] clause]
      (filter (fn [row] (empty? (eval-clauses ctx [(select-keys row vars)] body))) rows))

    (and (seq? clause) (= 'or (first clause)))
    (let [branches (rest clause)
          common (apply set/intersection
                        (map #(vars-in (branch-clauses %)) branches))]
      (distinct
       (mapcat (fn [row]
                 (mapcat (fn [b]
                           (map #(merge row (select-keys % common))
                                (eval-clauses ctx [row] (branch-clauses b))))
                         branches))
               rows)))

    (and (seq? clause) (= 'or-join (first clause)))
    (let [[_ vars & branches] clause
          vars (vec (flatten vars))]
      (distinct
       (mapcat (fn [row]
                 (mapcat (fn [b]
                           (keep (fn [r]
                                   (reduce (fn [acc v] (when acc (unify acc v (get r v))))
                                           row (filter #(contains? r %) vars)))
                                 (eval-clauses ctx [(select-keys row vars)] (branch-clauses b))))
                         branches))
               rows)))

    (vector? clause)
    (let [[src pattern] (if (src-var? (first clause))
                          [(get-in ctx [:sources (first clause)]) (vec (rest clause))]
                          [(get-in ctx [:sources '$]) clause])]
      (cond
        (nil? src) (throw (ex-info "Query has no data source" {:db/error :db.error/no-source}))
        (map? src) (match-db-pattern ctx src rows pattern)
        :else (match-coll-pattern rows src pattern)))

    :else (throw (ex-info (str "Invalid clause: " (pr-str clause))
                          {:db/error :db.error/invalid-clause}))))

(defn eval-clauses [ctx rows clauses]
  (reduce (fn [rows clause] (if (empty? rows) rows (vec (eval-clause ctx rows clause))))
          rows clauses))

;; ------------------------------------------------------------ inputs
(defn- parse-rules [rules]
  (let [rules (if (string? rules) (read-string rules) rules)]
    (reduce (fn [acc [head & body]]
              (let [[rname & hvars] head]
                (update acc rname (fnil conj []) [(vec (flatten hvars)) (vec body)])))
            {} rules)))

(defn- bind-inputs [ins args]
  (when (not= (count ins) (count args))
    (throw (ex-info (str "Wrong number of inputs: expected " (count ins) ", got " (count args))
                    {:db/error :db.error/invalid-input})))
  (reduce (fn [[sources rules rows] [in arg]]
            (cond
              (src-var? in) [(assoc sources in arg) rules rows]
              (rules-var? in) [sources (parse-rules arg) rows]
              :else [sources rules (vec (mapcat #(bind-form % in arg) rows))]))
          [{} {} [{}]]
          (map vector ins args)))

;; ------------------------------------------------------------ results
(defn- rand-nth-of [xs] (nth (vec xs) (rand-int (count xs))))

(defn- aggregate [fname args vals]
  (case fname
    count (count vals)
    count-distinct (count (distinct vals))
    sum (reduce + 0 vals)
    min (if (= 1 (count args))
          (vec (take (first args) (sort (distinct vals))))
          (reduce (fn [a b] (if (neg? (compare b a)) b a)) vals))
    max (if (= 1 (count args))
          (vec (take (first args) (sort (fn [a b] (compare b a)) (distinct vals))))
          (reduce (fn [a b] (if (pos? (compare b a)) b a)) vals))
    avg (/ (double (reduce + 0 vals)) (count vals))
    median (let [s (vec (sort vals)) n (count s)]
             (if (odd? n)
               (nth s (quot n 2))
               (let [sum (+ (nth s (dec (quot n 2))) (nth s (quot n 2)))]
               (if (and (integer? sum) (even? sum)) (quot sum 2) (/ sum 2.0)))))
    distinct (set vals)
    rand (vec (repeatedly (first args) #(rand-nth-of vals)))
    sample (vec (take (first args) (distinct vals)))
    (let [f (get @user-fns fname)]
      (if f
        (apply f (concat args [vals]))
        (throw (ex-info (str "Unknown aggregate: " fname) {:db/error :db.error/invalid-aggregate}))))))

(defn- project
  "Result tuples for the find elements: distinct over find and :with vars,
  grouped when there are aggregates."
  [ctx elems with rows]
  (let [find-vars (mapv :var elems)
        all-vars (into find-vars with)
        tuples (distinct (map (fn [r] (mapv #(get r %) all-vars)) rows))
        aggs? (some #(= :agg (:kind %)) elems)]
    (if-not aggs?
      (distinct (map #(subvec % 0 (count find-vars)) tuples))
      (let [key-idx (keep-indexed (fn [i el] (when (not= :agg (:kind el)) i)) elems)
            groups (group-by (fn [tuple] (mapv #(nth tuple %) key-idx)) tuples)]
        (if (and (empty? tuples) (empty? key-idx))
          ;; an aggregate over nothing still answers, as (count) does
          []
          (map (fn [[_ group]]
                 (vec (map-indexed
                       (fn [i el]
                         (if (= :agg (:kind el))
                           (aggregate (:fn el) (:args el) (map #(nth % i) group))
                           (nth (first group) i)))
                       elems)))
               groups))))))

(defn- pull-elems [ctx elems tuples]
  (if-not (some #(= :pull (:kind %)) elems)
    tuples
    (map (fn [tuple]
           (vec (map-indexed
                 (fn [i el]
                   (if (= :pull (:kind el))
                     (let [src (get-in ctx [:sources (:src el)])
                           pattern (if (qvar? (:pattern el))
                                     (get-in ctx [:scalars (:pattern el)])
                                     (:pattern el))]
                       (pull/pull src pattern (nth tuple i)))
                     (nth tuple i)))
                 elems)))
         tuples)))

(defn q
  "Run a query against its inputs."
  [query & args]
  (let [qm (parse-query query)
        [shape elems] (parse-find (:find qm))
        ins (or (:in qm) ['$])
        [sources rules rows] (bind-inputs ins args)
        ctx {:sources sources
             :rules rules
             :memo (atom {})
             :active (atom #{})
             :scalars (first rows)}
        rows (eval-clauses ctx rows (:where qm))
        tuples (pull-elems ctx elems (project ctx elems (vec (:with qm)) rows))]
    (case shape
      :rel (set tuples)
      :coll (vec (distinct (map first tuples)))
      :tuple (first tuples)
      :scalar (ffirst tuples))))
