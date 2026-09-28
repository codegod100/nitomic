# What works

The table below lists the parts of `datomic.api` that nitomic implements.
For what is missing or behaves differently, see
[Differences from Datomic](differences.md).

| Area | Supported |
|---|---|
| Connections | `create-database` `connect` `delete-database` `rename-database` `get-database-names` `db` `release` `shutdown` `sync` `request-index` |
| Transactions | `transact` `transact-async` `with`; map and list forms; nested maps; reverse attributes in maps; cardinality-many values; `:db/add` `:db/retract` (with or without a value) `:db/retractEntity` `:db/cas` (and their `:db.fn/` spellings); datoms as tx data |
| Ids | `tempid` (`#db/id` literals via `datomic.db/id-literal`), string tempids, implicit tempids, `resolve-tempid`, upsert through `:db.unique/identity` (including between tempids in one transaction), lookup refs everywhere, idents, `entid` `ident` `entid-at` `part` `t->tx` `tx->t` `squuid` `squuid-time-millis` |
| Schema | implicit attribute installation, `:db/unique` (value and identity), `:db/isComponent`, `:db/index`, `:db/fulltext`, `:db/noHistory`, enums, partitions (`:db.install/partition`), schema alteration, `attribute` |
| Values | string, long, double/float, boolean, keyword, symbol, ref, instant (`#inst`), uuid (`#uuid`), uri, bigint/bigdec (as numbers), tuple (as vectors), fn |
| Query | `q` `query`, list/map/string queries; find specs rel, `[?x ...]`, `[?a ?b]`, `?x .`; `pull` in `:find`; `:with`; `:in` scalars, tuples, collections, relations, several sources, rules (`%`); data patterns incl. tx and added positions; predicates and functions with all binding forms; `not` `not-join` `or` `or-join` `and`; rules, recursive and over cyclic data; aggregates `count` `count-distinct` `sum` `min` `max` `avg` `median` `distinct` `(min n ?x)` `(max n ?x)` `sample` `rand`; `get-else` `get-some` `missing?` `ground` `tuple` `untuple` `fulltext` |
| Pull | `pull` `pull-many`; `*`, `:db/id`, reverse attributes, nested maps, recursion (`...` and depth limits), `:as` `:limit` `:default` (vector or list syntax) and legacy `(limit ...)` `(default ...)`, component attributes pulled recursively |
| Entities | `entity` `touch` `entity-db`; lazy lookup, keyword/`get` access, `keys`, reverse navigation (`:ns/_attr`), enums as keywords, cardinality-many as sets |
| Time | `as-of` `since` `history` (by t, tx id or instant), `as-of-t` `since-t` `basis-t` `next-t` `is-history` `filter` `is-filtered` |
| Indexes | `datoms` `seek-datoms` `index-range` over `:eavt` `:aevt` `:avet` `:vaet`; datoms support `:e :a :v :tx :added` and `nth` |
| Log | `log` `tx-range` `tx-report-queue` (`.poll` `.take` `.peek` `.isEmpty` `.size`) `remove-tx-report-queue` |
| Errors | `ex-info` with Datomic's `:db/error` codes (`:db.error/unique-conflict`, `:db.error/cas-failed`, `:db.error/wrong-type-for-attribute`, `:db.error/not-an-entity`, `:db.error/datoms-conflict`, …); a failed `transact` throws when dereferenced |
