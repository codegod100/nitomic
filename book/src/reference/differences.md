# Differences from Datomic

- **Storage is memory.** Every URI protocol (`mem`, `dev`, `sql`, …) names an
  in-process database. Nothing is persisted, and there is no transactor.
- **No runtime code compilation.** Database functions can't be Clojure source
  strings. `:db/fn` holds a Clojure fn, which `d/function` passes through.
  Query functions are found in a built-in table of `clojure.core` and string
  functions (including `.compareTo`, `.startsWith`, and the like). You can
  also pass them as inputs or register them with `d/register-fn!`. The JVM
  would resolve any qualified symbol instead. See `test/native.clj`.
- **Fulltext** tokenizes on letters and digits, lower-cases, drops English
  stop words, and matches any query term (with `term*` prefixes). That matches
  the default Lucene analyzer on typical text. It is not Lucene's full query
  syntax, and scores are all 1.0.
- **Not implemented:** composite tuple attributes (`:db/tupleAttrs`),
  `:db/ensure` and entity specs, excision, `d/index-pull`, and database
  stats beyond counts.
- **Printing.** Query results are Clojure sets and vectors rather than Java
  collections. An entity prints as `{:db/id n}`, and a touched entity prints
  as its attribute map.
