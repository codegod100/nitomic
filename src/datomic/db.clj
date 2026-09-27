;; The reader function behind #db/id literals, as in Datomic's
;; data_readers.clj: bind it in *data-readers* (or pass it to
;; clojure.edn/read-string's :readers) to read Datomic edn files.
(ns datomic.db
  (:require [nitomic.types :as types]))

(defn id-literal
  "#db/id[:db.part/user] is a new tempid; #db/id[:db.part/user -100] names one."
  [[part n]]
  (if n (types/tempid part n) (types/tempid part)))
