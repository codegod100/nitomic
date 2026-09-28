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

## Documentation

The [nitomic book](https://codegod100.github.io/nitomic/) ([source](book/)) covers installation and walks through Datomic's
getting-started example step by step. Build it with
[mdBook](https://rust-lang.github.io/mdBook/):

```bash
mdbook serve book     # or: mdbook build book
```

## Installing with Nimble

nitomic is a Nimble package. Installing it also installs clonim:

```bash
nimble install https://github.com/codegod100/nitomic
clonim run hello.clj --source-path "$(nimble path nitomic)"
```

From a checkout, `nimble install` installs the working tree, and `nimble test`
runs the test suite (see [Testing](#testing)) with the `clonim` on `PATH`.
`nimble example` runs Datomic's getting-started walkthrough
(`examples/seattle/getting_started.clj`); set `CLONIM` to use another clonim.

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

- **Storage is memory or SQLite.** `datomic:sql://<name>?jdbc:sqlite:<path>`
  keeps databases in a SQLite file (see [Durable storage](#durable-storage)).
  Every other URI protocol (`mem`, `dev`, `ddb`, …) names an in-process
  database that is gone when the process exits. The transactor is optional:
  without one, each process takes SQLite's write lock to transact.
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

## Durable storage

A `datomic:sql` URI naming a SQLite file makes a database durable:

```clojure
(def uri "datomic:sql://hello?jdbc:sqlite:/var/lib/app/datomic.db")
(d/create-database uri)
(def conn (d/connect uri))
```

The file is created on first use and can hold any number of databases.
`create-database`, `delete-database`, `rename-database` and
`get-database-names` (with `datomic:sql://*?jdbc:sqlite:<path>`) act on the
catalog in the file.

- **What is stored.** Each transaction is stored as one log row: the datoms
  it produced and the id counters after it. `connect` replays the log to
  rebuild the database, so every id and value comes back as the transaction
  made it. The transaction logic never runs twice.
- **Several processes.** Any number of processes can share a file.
  `transact` takes SQLite's write lock, applies what other processes have
  committed since it last looked, transacts, and stores the result before
  releasing the lock. So each process acts as its own transactor, one at a
  time.
- **Seeing other writers.** `d/db`, `sync` and reading a `tx-report-queue`
  pick up other writers' transactions. Their reports include `:tempids`,
  which are stored with each transaction.
- **Transaction functions.** A `:db/fn` holds a Clojure fn, which can't be
  written to a file. Installing one in a stored database fails with
  `:db.error/not-storable`.
- **Releasing.** `release` drops a stored connection from the cache, so the
  next `connect` rebuilds it from the file.
- **Transactor.** Running a transactor makes it the only process that
  writes the log (see [Running a transactor](#running-a-transactor)).
- **Requirements.** Storage uses clonim's `clonim.sqlite`, which loads
  `libsqlite3` when a stored database is first used.

### Running a transactor

```bash
clonim build script/transactor.clj --source-path src -o nitomic-transactor
./nitomic-transactor /var/lib/app/datomic.db     # or datomic:sql://*?jdbc:sqlite:<path>
```

`nitomic.transactor` serves every database in a storage file. While it
runs, it records a heartbeat in the file every second.

- **Queued transactions.** While a transactor's heartbeat is fresh,
  `d/transact` doesn't write the log. It queues the transaction data in the
  file and returns a future at once. The transactor takes queued
  transactions in order. For each one, in a single SQLite transaction, it
  runs it, appends it to the log, and records the outcome. Dereferencing the
  future waits for that outcome, then returns the report or throws the
  transactor's error.
- **Clock.** The transactor stamps `:db/txInstant` with its own clock, as
  Datomic's does.
- **Only one at a time.** A second transactor refuses to start while one is
  alive (`:db.error/transactor-running`).
- **When it stops.** After three missed heartbeats (3 s), peers go back to
  writing the log themselves, so the databases stay writable. A transaction
  still waiting in the queue at that point is withdrawn and fails with
  `:db.error/transactor-unavailable`.
- **What can be queued.** Transaction data crosses processes as EDN, with
  tempids and datoms encoded. A fn can't be sent and fails with
  `:db.error/not-storable`.
- **Programmatic use.** `nitomic.transactor/run` serves a file until its
  `:stop?` fn returns true. `start`, `step!` and `stop!` drive it one batch at
  a time.

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
| `src/nitomic/storage.clj` | durable storage: the SQLite catalog, transaction log and queue, replay |
| `src/nitomic/transactor.clj` | the transactor: serves a storage's queue and writes its log |
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
[codegod100/clonim#1](https://github.com/codegod100/clonim/pull/1). Durable
storage and the transactor also need `clonim.sqlite` and `Thread/sleep`, from
[codegod100/clonim#18](https://github.com/codegod100/clonim/pull/18).

## Testing

```bash
CLONIM=path/to/clonim/bin/clonim test/run.sh
```

`test/run.sh` runs the test programs under clonim and diffs their output
against `test/expected/`:

- `examples/seattle/getting_started.clj` is Datomic's own getting-started
  walkthrough over the Seattle sample data;
- `test/features.clj` goes through the rest of the API, including error cases;
- `test/native.clj` covers the native-only extensions;
- `test/storage.clj` covers durable storage: replay, two connections sharing
  a file, and the catalog;
- `test/transactor.clj` drives the transactor step by step: queued
  transactions, errors, tempids, stopping and withdrawal.

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
