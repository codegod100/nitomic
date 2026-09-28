# Overview

Datomic Pro ships a getting-started tutorial built around a small data set
about Seattle's neighborhood communities: blogs, mailing lists, Twitter
feeds, chambers of commerce and so on. The tutorial lives in the Datomic
distribution as `samples/seattle/getting-started.clj`, a file of forms meant
to be evaluated one at a time in a REPL.

nitomic carries that tutorial as a single program:

```text
examples/seattle/
├── getting_started.clj   # the walkthrough, as one program
├── seattle-schema.edn    # the schema: 10 attributes and their enums
├── seattle-data0.edn     # the initial data: 150 communities
└── seattle-data1.edn     # more data, added later: 108 more communities
```

The data files come from the Datomic Pro distribution, which is licensed
under the Apache License 2.0.

## Running it

From the root of a nitomic checkout:

```bash
nimble example
# or, equivalently
clonim run examples/seattle/getting_started.clj --source-path src
```

The program reads the `.edn` files by relative path, so run it from the
repository root.

## Why it prints the way it does

The program runs unchanged on the JVM against Datomic Pro and natively
against nitomic, and the two outputs are compared line by line (see
[How it is tested](../reference/testing.md)). Query results are sets, whose
iteration order differs between the two implementations, and instants differ
from run to run. So instead of printing results directly, the program prints
them through two small helpers that produce a canonical form:

```clojure
(defn- canon-str [x]
  (cond
    (map? x) (str "{" (str/join ", " (sort (map (fn [[k v]] (str (canon-str k) " " (canon-str v))) x))) "}")
    (set? x) (str "#{" (str/join " " (sort (map canon-str x))) "}")
    (vector? x) (str "[" (str/join " " (map canon-str x)) "]")
    (seq? x) (str "(" (str/join " " (map canon-str x)) ")")
    (inst? x) "#inst"
    :else (pr-str x)))

(defn show
  "Print a labelled result; an unordered collection of results is sorted."
  [label x]
  (println (str label ":") (canon-str x)))

(defn show-sorted [label xs]
  (println (str label ":") (str "(" (str/join " " (sort (map canon-str xs))) ")")))
```

- `show` prints a label and a value. Map keys and set members are sorted, and
  every instant prints as `#inst`.
- `show-sorted` prints a collection of results as a sorted list.

Every output line quoted in the following chapters is taken from
`test/expected/getting_started.out`, which was recorded on the JVM against
Datomic Pro 1.0.7705. nitomic must reproduce it exactly.

## The chapters

| chapter | covers |
|---|---|
| [The Seattle data model](data-model.md) | the schema: communities, neighborhoods, districts, enums |
| [Creating a database and loading data](setup.md) | `create-database`, `connect`, `#db/id` tempids, `transact` |
| [Entities and pull](entities-and-pull.md) | `entity`, navigation and reverse navigation, `pull` in queries |
| [Queries](queries.md) | find specs, joins, `:in` parameters, predicates, `fulltext` |
| [Rules](rules.md) | named, reusable, composable query clauses |
| [Time travel](time.md) | `as-of`, `since`, `with` |
| [Changing data](changes.md) | partitions, adding and retracting values, `retractEntity`, the tx report queue |
