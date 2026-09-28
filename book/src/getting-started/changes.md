# Changing data

The last part of the walkthrough makes smaller changes: it creates a
partition, adds and retracts values, retracts a whole entity, and watches
transactions through the report queue.

## Creating a partition

Partitions group entities, which in Datomic affects how their datoms sort
together. A partition is an entity with a `:db/ident`, installed through
`:db.install/_partition`:

```clojure
(def partition-report
  @(d/transact conn [{:db/id (d/tempid :db.part/db)
                      :db/ident :communities
                      :db.install/_partition :db.part/db}]))
(show "partition installed" (contains? (set (map :v (:tx-data partition-report)))
                                       :communities))
```

```text
partition installed: true
```

`d/tempid` creates a tempid in a partition, like a `#db/id` literal does.
`:db.install/_partition :db.part/db` is a reverse attribute in a map: it
asserts `[:db.part/db :db.install/partition <new entity>]`.

## Creating an entity in the partition

```clojure
(def easton-report
  @(d/transact conn [{:db/id (d/tempid :communities)
                      :community/name "Easton"}]))
(def easton-created (d/q '[:find ?id . :where [?id :community/name "Easton"]] (d/db conn)))
(show "Easton partition is :communities"
      (= (d/part easton-created) (d/entid (d/db conn) :communities)))
```

```text
Easton partition is :communities: true
```

`d/part` returns the partition of an entity id, and `d/entid` turns an ident
into its entity id.

## Adding a value

To add to an existing entity, transact a map with its `:db/id`:

```clojure
(def belltown-id (d/q '[:find ?id .
                        :where
                        [?id :community/name "belltown"]]
                      (d/db conn)))

@(d/transact conn [{:db/id belltown-id
                    :community/category "free stuff"}])
(:community/category (d/entity (d/db conn) belltown-id))
```

```text
belltown categories after add: ("events" "free stuff" "news")
```

`:community/category` is cardinality many, so the new value joins the
existing ones. For a cardinality-one attribute, the new value would replace
the old one (Datomic retracts the old value for you).

## Retracting a value

The list form `[:db/retract e a v]` retracts one value:

```clojure
@(d/transact conn [[:db/retract belltown-id :community/category "free stuff"]])
(:community/category (d/entity (d/db conn) belltown-id))
```

```text
belltown categories after retract: ("events" "news")
```

The list form `[:db/add e a v]` is the counterpart for assertions.

## Retracting an entity

`:db.fn/retractEntity` (also spelled `:db/retractEntity`) retracts every
attribute of an entity, and every reference to it:

```clojure
(def easton-id (d/q '[:find ?id .
                      :where
                      [?id :community/name "Easton"]]
                    (d/db conn)))

@(d/transact conn [[:db.fn/retractEntity easton-id]])
(d/q '[:find ?id . :where [?id :community/name "Easton"]] (d/db conn))
```

```text
Easton after retractEntity: nil
```

A retraction is a new fact, not a deletion: the history still records that
Easton existed, and `d/as-of` an earlier time still finds it.

## Watching transactions: the tx report queue

`d/tx-report-queue` returns a queue that receives the report of every
transaction committed on the connection after it was created:

```clojure
(def queue (d/tx-report-queue conn))

@(d/transact conn [{:db/id (d/tempid :communities)
                    :community/name "Easton"}])
```

`.poll` takes the next report, or returns `nil` if there is none. The report
has the same keys as a `transact` result. The walkthrough queries its
`:tx-data` directly: a collection of datoms can be a query input, bound here
as a relation of `[e a v tx added]`:

```clojure
(when-let [report (.poll queue)]
  (show-sorted "tx report from queue"
               (map (fn [[e aname v added]]
                      [(if (= 3 (d/part e)) :tx (d/part e)) aname
                       (if (inst? v) :inst v) added])
                    (d/q '[:find ?e ?aname ?v ?added
                           :in $ [[?e ?a ?v _ ?added]]
                           :where
                           [?e ?a ?v _ ?added]
                           [?a :db/ident ?aname]]
                         (:db-after report)
                         (:tx-data report)))))
(show "queue empty after poll" (.poll queue))
```

```text
tx report from queue: ([83 :community/name "Easton" true] [:tx :db/txInstant :inst true])
queue empty after poll: nil
```

The transaction produced two datoms: the new community's name, on an entity
in partition 83 (the `:communities` partition), and the transaction's own
`:db/txInstant`, on the transaction entity in partition 3. After one poll the
queue is empty.

nitomic's queue also supports `.take`, `.peek`, `.isEmpty` and `.size`, and
`d/remove-tx-report-queue` detaches it.

## That's the walkthrough

The program ends with `(System/exit 0)`. On the JVM this shuts down
Datomic's background threads; natively it simply exits.

From here, the [What works](../reference/support.md) page lists the rest of
the API, and `test/features.clj` in the repository exercises it, including
the error cases.
