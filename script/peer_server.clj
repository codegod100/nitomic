;; A nitomic peer as a long-lived process speaking EDN over stdin/stdout, for
;; a front end in another language (deploy/modal/app.py) to put an API on:
;;
;;   clonim build script/peer_server.clj --source-path src -o nitomic-peer
;;   NITOMIC_STORAGE='jdbc:postgresql://...' ./nitomic-peer
;;
;; Each request is EDN, possibly over several lines, followed by a line
;; holding only %%end%%; the answer is one line of EDN:
;;
;;   {:op :create :db "app"}                          => {:ok true}
;;   {:op :transact :db "app" :tx-data [...]}         => {:ok {:t :tempids :tx-data}}
;;   {:op :q :db "app" :query [...] :args [...]}      => {:ok result}
;;   {:op :pull :db "app" :spec {:pattern [...] :eid ...}} => {:ok map}
;;   {:op :databases}                                 => {:ok ["app" ...]}
;;   anything that fails                              => {:error "msg" :data {...}}
;;
;; :args are the query inputs after the database. #db/id literals work in
;; :tx-data. Connections stay open between requests, so each database is
;; replayed from storage once and then caught up, as a Datomic peer does.
(ns peer-server
  (:require [datomic.api :as d]
            [datomic.db]
            [nitomic.storage :as storage]))

(def storage-spec (System/getenv "NITOMIC_STORAGE"))

(defn- uri [db-name] (str "datomic:sql://" db-name "?" storage-spec))

(defn- report [{:keys [db-after tempids tx-data]}]
  {:t (d/basis-t db-after)
   :tempids tempids
   :tx-data (mapv (fn [dt] [(:e dt) (:a dt) (:v dt) (:tx dt) (:added dt)]) tx-data)})

(defn- handle [{:keys [op db tx-data query args spec]}]
  (case op
    :ping :pong
    :databases (vec (d/get-database-names (uri "*")))
    :create (d/create-database (uri db))
    :transact (report @(d/transact (d/connect (uri db)) tx-data))
    :q (apply d/q query (d/db (d/connect (uri db))) args)
    :pull (d/pull (d/db (d/connect (uri db))) (:pattern spec) (:eid spec))
    (throw (ex-info (str "Unknown op: " (pr-str op)) {:op op}))))

(defn- respond [line]
  (try
    (let [req (binding [*data-readers* {'db/id datomic.db/id-literal}]
                (read-string line))]
      {:ok (handle req)})
    (catch Exception e
      {:error (or (ex-message e) (str e))
       :data (storage/plain (ex-data e))})))

(def ^:private end-marker "%%end%%")

(defn- read-request
  "The next request's text, or nil at the end of input."
  []
  (loop [lines []]
    (let [line (read-line)]
      (cond
        (nil? line) (when (seq lines) (clojure.string/join "\n" lines))
        (= line end-marker) (clojure.string/join "\n" lines)
        :else (recur (conj lines line))))))

(defn -main [& _]
  (when-not storage-spec
    (binding [*out* *err*]
      (println "nitomic-peer: set NITOMIC_STORAGE to a JDBC URL or SQLite path"))
    (System/exit 2))
  (loop []
    (when-let [text (read-request)]
      ;; pr-str escapes newlines inside strings, so an answer is one line
      (println (pr-str (storage/plain (respond text))))
      (flush)
      (recur))))
