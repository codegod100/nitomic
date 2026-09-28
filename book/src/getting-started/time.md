# Time travel

A Datomic database is an accumulation of facts, and a database value is
immutable. Every transaction is itself an entity, with a `:db/txInstant`
recording when it happened. That makes it possible to look at the database
as it was at any point, or at only what changed since then.

## Finding transaction times

```clojure
(def tx-instants (reverse (sort (d/q '[:find [?when ...] :where [_ :db/txInstant ?when]]
                                     (d/db conn)))))
(show "transaction instants" (count tx-instants))

(def data-tx-date (first tx-instants))
(def schema-tx-date (second tx-instants))
```

```text
transaction instants: 3
```

There are three transactions: the bootstrap transaction that every database
starts with, the schema, and the data. Sorted newest first, the first
instant is the data transaction and the second is the schema transaction.

The rest of this chapter runs one query against different views of the
database:

```clojure
(def communities-query '[:find [?c ...] :where [?c :community/name]])
```

## `as-of`: the database at a point in time

`d/as-of` returns the database as it was at a time, which can be given as a
`t`, a transaction id or an instant:

```clojure
(let [db-asof-schema (-> conn d/db (d/as-of schema-tx-date))]
  (count (d/q communities-query db-asof-schema)))

(let [db-asof-data (-> conn d/db (d/as-of data-tx-date))]
  (count (d/q communities-query db-asof-data)))
```

```text
as of schema: 0
as of data: 150
```

Right after the schema transaction there were no communities yet; right
after the data transaction there were 150.

## `since`: only what changed after a point

`d/since` returns a database that contains only the facts added after a
time:

```clojure
(let [db-since-data (-> conn d/db (d/since schema-tx-date))]
  (count (d/q communities-query db-since-data)))

(let [db-since-data (-> conn d/db (d/since data-tx-date))]
  (count (d/q communities-query db-since-data)))
```

```text
since schema: 150
since data: 0
```

## `with`: what if?

`d/with` applies a transaction to a database value without committing it. It
returns the same kind of report as `transact`, and its `:db-after` is a
database you can query. The connection is untouched:

```clojure
(def new-data-tx (read-edn "examples/seattle/seattle-data1.edn"))

(let [db-if-new-data (-> conn d/db (d/with new-data-tx) :db-after)]
  (count (d/q communities-query db-if-new-data)))

(count (d/q communities-query (d/db conn)))
```

```text
with new data: 258
current: 150
```

This is useful for trying a transaction out, validating it, or computing a
speculative result.

## Committing the new data

Now transact it for real:

```clojure
@(d/transact conn new-data-tx)
(count (d/q communities-query (d/db conn)))

(let [db-since-data (-> conn d/db (d/since data-tx-date))]
  (count (d/q communities-query db-since-data)))
```

```text
after new data: 258
since first data: 108
```

The database now has 258 communities, and `since` shows exactly the 108 that
the new transaction added.

Note that `seattle-data1.edn` mentions neighborhoods and districts that
already exist, such as "Beacon Hill". Because their names are unique
identities, those tempids upsert onto the existing entities instead of
creating duplicates.

## More time functions

nitomic also supports `d/history` (a database of every assertion and
retraction ever made), `d/as-of-t`, `d/since-t`, `d/basis-t`, `d/next-t`,
`d/is-history`, `d/filter` and `d/is-filtered`, and the transaction log via
`d/log` and `d/tx-range`.
