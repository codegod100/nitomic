;; The nitomic transactor, as a program:
;;
;;   clonim build script/transactor.clj --source-path src -o nitomic-transactor
;;   ./nitomic-transactor /var/lib/app/datomic.db
;;
;; The argument is a SQLite storage file, or a URI naming one
;; (datomic:sql://*?jdbc:sqlite:<path>). It serves every database in the
;; file until it is killed; peers go back to writing the log themselves
;; three heartbeats (3 s) after it stops.
(ns transactor
  (:require [clojure.string :as str]
            [nitomic.transactor :as transactor]))

(defn- storage-path [arg]
  (if-let [i (str/index-of arg "jdbc:sqlite:")]
    (subs arg (+ i (count "jdbc:sqlite:")))
    arg))

(defn -main [& args]
  (when (not= 1 (count args))
    (println "usage: nitomic-transactor <storage.db | datomic:sql://*?jdbc:sqlite:<path>>")
    (System/exit 2))
  (let [path (storage-path (first args))]
    (println "nitomic transactor serving" path)
    (transactor/run path)))
