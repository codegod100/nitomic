;; Where the storage tests keep their data. NITOMIC_TEST_STORAGE names a
;; storage as a JDBC URL (jdbc:postgresql://... to test PostgreSQL); without
;; it they use a SQLite file in the temp directory. Both give the same
;; output.
(ns test.support
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clonim.postgres :as pg]))

(defn storage [test-name]
  (or (System/getenv "NITOMIC_TEST_STORAGE")
      (str "jdbc:sqlite:" (System/getProperty "java.io.tmpdir") "/nitomic-" test-name ".db")))

(defn uri [storage db-name] (str "datomic:sql://" db-name "?" storage))

(defn clean!
  "Remove everything a test stored."
  [storage]
  (if (str/starts-with? storage "jdbc:postgresql:")
    (let [c (pg/connect (subs storage (count "jdbc:")))]
      (pg/execute! c "drop table if exists nitomic_meta, nitomic_databases,
                      nitomic_log, nitomic_queue")
      (pg/close c))
    (let [path (subs storage (count "jdbc:sqlite:"))]
      (doseq [f [path (str path "-wal") (str path "-shm")]] (io/delete-file f true)))))
