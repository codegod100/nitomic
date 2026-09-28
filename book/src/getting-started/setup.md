# Creating a database and loading data

## Requiring the API

```clojure
(require '[datomic.api :as d]
         '[datomic.db]
         '[clojure.string :as str])
```

`datomic.api` is the whole public API. `datomic.db` provides `id-literal`,
the reader function behind `#db/id[...]` tempid literals, which the data
files use.

## Creating and connecting

```clojure
(def uri "datomic:mem://seattle")
(show "create-database" (d/create-database uri))
(def conn (d/connect uri))
```

```text
create-database: true
```

`create-database` returns `true` when it creates a database, and `false` if
one with that name already exists. `connect` returns a connection, which is
what you transact against and take database values from.

> **nitomic:** `datomic:mem` and every other protocol except `sql` name an
> in-memory database in the same process. To keep a database on disk, use a
> SQLite URI such as `datomic:sql://seattle?jdbc:sqlite:seattle.db` (see
> [Durable storage](../reference/storage.md)).

## Reading EDN with tempid literals

The schema and data files contain `#db/id[:db.part/user]` and
`#db/id[:db.part/user -1000001]` forms. To read them, bind
`*data-readers*` so the `db/id` tag goes to `datomic.db/id-literal`:

```clojure
(defn read-edn [path]
  (binding [*data-readers* {'db/id datomic.db/id-literal}]
    (read-string (slurp path))))
```

Each literal becomes a tempid in the named partition. Two literals with the
same negative number are the same tempid, which is how the data files link
new entities to each other. A literal without a number is a fresh tempid.

## Transacting the schema

```clojure
(def schema-tx (read-edn "examples/seattle/seattle-schema.edn"))
(show "first schema statement" (first schema-tx))
(def schema-report @(d/transact conn schema-tx))
(show "schema tx-data count" (count (:tx-data schema-report)))
```

```text
first schema statement: {:db/cardinality :db.cardinality/one, :db/doc "A community's name", :db/fulltext true, :db/ident :community/name, :db/valueType :db.type/string}
schema tx-data count: 75
```

`d/transact` returns a future. Dereferencing it (`@`) waits for the
transaction and returns a report map:

| key | value |
|---|---|
| `:db-before` | the database value before the transaction |
| `:db-after` | the database value after it |
| `:tx-data` | the datoms the transaction asserted or retracted |
| `:tempids` | a map from tempids to the entity ids they resolved to |

The 75 datoms are the 44 attribute definition datoms, one
`:db.install/attribute` datom per attribute (10), the 20 enum idents, and
the transaction's own `:db/txInstant`. nitomic allocates the same entity ids as
Datomic, so the same 75 datoms come out.

If the transaction fails, dereferencing throws an `ex-info` whose data
carries a Datomic `:db/error` code, such as `:db.error/unique-conflict`.

## Transacting the data

```clojure
(def data-tx (read-edn "examples/seattle/seattle-data0.edn"))
(show "first data statement" (dissoc (first data-tx) :db/id))
(show "second data statement" (dissoc (second data-tx) :db/id :neighborhood/district))
(def data-report @(d/transact conn data-tx))
(show "data tx-data count" (count (:tx-data data-report)))
```

```text
first data statement: {:district/name "East", :district/region :region/e}
second data statement: {:neighborhood/name "Capitol Hill"}
data tx-data count: 1237
```

The `:db/id` keys are dropped before printing because a tempid prints
differently on the two platforms.

Some things this one transaction exercises:

- **Map form.** Each map asserts all its attributes for one entity.
- **Tempid references.** `:neighborhood/district #db/id[:db.part/user -1000001]`
  points at the district created earlier in the same transaction.
- **Enum keywords as ref values.** `:district/region :region/e` resolves the
  keyword to the enum entity.
- **Cardinality many.** `:community/category ["events" "news"]` asserts one
  datom per value.
- **Upsert.** A neighborhood that appears twice under two tempids resolves to
  one entity, because `:neighborhood/name` is a unique identity.

With the schema and data in place, the database holds 150 communities.
