# Source layout

| file | what it does |
|---|---|
| `src/datomic/api.clj` | the public API |
| `src/datomic/db.clj` | `id-literal`, the `#db/id` reader function |
| `src/nitomic/db.clj` | database values: covering indexes as nested persistent maps, the schema cache, as-of/since/history views |
| `src/nitomic/tx.clj` | transactions: expansion, tempid resolution and upsert, id allocation, index updates, schema installation |
| `src/nitomic/query.clj` | Datalog, rules and aggregates |
| `src/nitomic/pull.clj` | the pull API |
| `src/nitomic/entity.clj` | lazy entities (a `deftype` over `ILookup`/`Seqable`) |
| `src/nitomic/storage.clj` | durable storage: the SQLite catalog and transaction log, replay |
| `src/nitomic/types.clj` | datoms and tempids |
| `src/nitomic/bootstrap.clj` | Datomic's bootstrap datoms |

A database is an immutable map, so every database value stays valid. Its
covering indexes (`{e {a {v tx}}}`, `{a {e {v tx}}}`, `{a {v {e tx}}}` and
`{v {a {e tx}}}` for refs) make every bound prefix a hash lookup. The sorted
orders that `d/datoms` promises are produced on demand. As in Datomic, query
clauses run in the order written.
