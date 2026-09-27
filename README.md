# nitomic

Datomic's peer API (`datomic.api`), ported to
[clonim](https://github.com/codegod100/clonim) so Datomic programs compile to
native binaries through Nim: no JVM.

```clojure
(require '[datomic.api :as d])

(d/create-database "datomic:mem://hello")
(def conn (d/connect "datomic:mem://hello"))

@(d/transact conn [{:db/ident :person/name
                    :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one}])
@(d/transact conn [{:person/name "Ada"} {:person/name "Grace"}])

(d/q '[:find [?n ...] :where [_ :person/name ?n]] (d/db conn))
;=> ["Ada" "Grace"]
```

```bash
clonim run   hello.clj --source-path path/to/nitomic/src   # compile and run
clonim build hello.clj --source-path path/to/nitomic/src   # native binary
```

## Installing with Nimble

nitomic is a Nimble package. Installing it also installs clonim:

```bash
nimble install https://github.com/codegod100/nitomic
clonim run hello.clj --source-path "$(nimble path nitomic)"
```

From a checkout, `nimble install` installs the working tree, and `nimble test`
runs the test suite (see [Testing](#testing)) with the `clonim` on `PATH`.

## What "port" means here

The Datomic Pro 1.0.7705 distribution is compiled JVM bytecode: the peer
library ships as AOT-compiled classes with no Clojure source. So this is a
clean-room reimplementation of the peer API in Clojure that clonim can
compile. It is checked against the real thing. The same test programs run on
the JVM against Datomic Pro and natively against nitomic, and their outputs
must match line for line (see [Testing](#testing)).

The bootstrap database is taken from Datomic itself. The system partitions,
value types and attributes, with Datomic's own entity ids, docs and
transaction ids, were dumped from a fresh `datomic:mem` database into
`src/nitomic/bootstrap.clj`. New entity ids are allocated the way Datomic
allocates them:

- transaction ids are `t` in partition 3;
- other ids share the `t` counter in their partition;
- attributes get their own counter in the db partition.

So the ids a program sees are the ids Datomic would give it.

## What works

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

## Differences from Datomic

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

## Layout

| file | what it does |
|---|---|
| `src/datomic/api.clj` | the public API |
| `src/datomic/db.clj` | `id-literal`, the `#db/id` reader function |
| `src/nitomic/db.clj` | database values: covering indexes as nested persistent maps, the schema cache, as-of/since/history views |
| `src/nitomic/tx.clj` | transactions: expansion, tempid resolution and upsert, id allocation, index updates, schema installation |
| `src/nitomic/query.clj` | Datalog, rules and aggregates |
| `src/nitomic/pull.clj` | the pull API |
| `src/nitomic/entity.clj` | lazy entities (a `deftype` over `ILookup`/`Seqable`) |
| `src/nitomic/types.clj` | datoms and tempids |
| `src/nitomic/bootstrap.clj` | Datomic's bootstrap datoms |

A database is an immutable map, so every database value stays valid. Its
covering indexes (`{e {a {v tx}}}`, `{a {e {v tx}}}`, `{a {v {e tx}}}` and
`{v {a {e tx}}}` for refs) make every bound prefix a hash lookup. The sorted
orders that `d/datoms` promises are produced on demand. As in Datomic, query
clauses run in the order written.

## clonim requirements

nitomic uses Clojure features that clonim gained for this port:

- `reify`/`deftype`/`defprotocol` with method dispatch;
- `#inst`/`#uuid`, `read-string`/`clojure.edn` with `*data-readers*`;
- `compare` and comparator sorts;
- `for`/`doseq` modifiers;
- `ex-data`;
- a set of collection functions.

They are in clonim `main` as of
[codegod100/clonim#1](https://github.com/codegod100/clonim/pull/1).

## Testing

```bash
CLONIM=path/to/clonim/bin/clonim test/run.sh
```

`test/run.sh` runs the test programs under clonim and diffs their output
against `test/expected/`:

- `examples/seattle/getting_started.clj` is Datomic's own getting-started
  walkthrough over the Seattle sample data;
- `test/features.clj` goes through the rest of the API, including error cases;
- `test/native.clj` covers the native-only extensions.

The expected outputs of the first two were recorded on the JVM against Datomic
Pro 1.0.7705 by `script/reference.sh`. Re-record them from any distribution
with:

```bash
curl -O https://datomic-pro-downloads.s3.amazonaws.com/1.0.7705/datomic-pro-1.0.7705.zip
unzip datomic-pro-1.0.7705.zip
script/reference.sh datomic-pro-1.0.7705
```

Built with `clonim build` (`-d:release`), the walkthrough runs in about 1.3 s
natively.

The Seattle sample data in `examples/seattle/` comes from the Datomic Pro
distribution, which is licensed under the Apache License 2.0.
