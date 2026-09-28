;; The nitomic transactor, as a program:
;;
;;   clonim build script/transactor.clj --source-path src -o nitomic-transactor
;;   ./nitomic-transactor 'jdbc:postgresql://host/db?user=u&password=p'
;;   ./nitomic-transactor /var/lib/app/datomic.db
;;
;; The argument names a storage: a JDBC URL (jdbc:postgresql:... or
;; jdbc:sqlite:...), a SQLite path, or a URI like datomic:sql://*?<jdbc-url>.
;; Without one, NITOMIC_STORAGE is used. It serves every database in the
;; storage until it is killed; peers go back to writing the log themselves
;; when it stops (on SQLite, after three missed heartbeats).
(ns transactor
  (:require [clojure.string :as str]
            [nitomic.transactor :as transactor]))

(defn- storage-spec [arg]
  (if-let [i (str/index-of arg "jdbc:")]
    (subs arg i)
    arg))

(defn -main [& args]
  (let [arg (or (first args) (System/getenv "NITOMIC_STORAGE"))]
    (when (or (nil? arg) (next args))
      (println "usage: nitomic-transactor <jdbc-url | sqlite-path | datomic:sql://*?<jdbc-url>>")
      (println "       (or set NITOMIC_STORAGE)")
      (System/exit 2))
    (let [spec (storage-spec arg)]
      ;; don't echo credentials
      (println "nitomic transactor serving"
               (-> spec (str/split "?") first (str/replace #"//[^/@]*@" "//")))
      (transactor/run spec))))
