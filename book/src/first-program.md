# Your first program

Save this as `hello.clj`:

```clojure
(require '[datomic.api :as d])

(d/create-database "datomic:mem://hello")
(def conn (d/connect "datomic:mem://hello"))

;; Install one attribute.
@(d/transact conn [{:db/ident :person/name
                    :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one}])

;; Add two people.
@(d/transact conn [{:person/name "Ada"} {:person/name "Grace"}])

(prn (sort (d/q '[:find [?n ...] :where [_ :person/name ?n]] (d/db conn))))
```

## Run it

```bash
clonim run hello.clj --source-path path/to/nitomic/src
```

`clonim run` compiles the program through Nim and runs it. It prints:

```text
("Ada" "Grace")
```

The query returns its results in no particular order, so the program sorts
them before printing.

## Build a binary

```bash
clonim build hello.clj --source-path path/to/nitomic/src
```

`clonim build` produces a standalone native executable instead of running
the program. Release builds (`-d:release`) are what you want for speed: built
this way, the full Seattle walkthrough runs in about 1.3 seconds.

## What just happened

The program uses nothing but `datomic.api`, so the same file also runs on the
JVM against Datomic Pro. A few things to notice:

- **`datomic:mem://hello`** names an in-memory database. In nitomic every URI
  protocol (`mem`, `dev`, `sql`, ...) names an in-process, in-memory
  database; nothing is persisted.
- **`d/transact` returns a future.** Dereferencing it with `@` waits for the
  result, a map with `:db-before`, `:db-after`, `:tx-data` and `:tempids`. A
  failed transaction throws when you dereference it.
- **Maps without `:db/id` get implicit tempids.** Each map in the second
  transaction becomes a new entity.
- **`[?n ...]`** is a collection find spec: the query returns a vector of
  names instead of a set of tuples.

The [Getting started](getting-started/overview.md) walkthrough covers all of
this, and much more, on a realistic data set.
