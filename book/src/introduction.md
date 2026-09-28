# Introduction

**nitomic** is Datomic's peer API (`datomic.api`) ported to
[clonim](https://github.com/codegod100/clonim), a Clojure compiler that
targets Nim. Datomic programs written against `datomic.api` compile to native
binaries, with no JVM involved.

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

## A clean-room port, checked against the real thing

Datomic Pro 1.0.7705 ships its peer library as AOT-compiled JVM classes, with
no Clojure source. nitomic is therefore a clean-room reimplementation of the
peer API in Clojure that clonim can compile.

It is checked against Datomic itself. The same test programs run on the JVM
against Datomic Pro and natively against nitomic, and their outputs must
match line for line. The main one of those programs is Datomic's own
getting-started walkthrough, which is what the
[Getting started](getting-started/overview.md) part of this book explains
step by step.

The bootstrap database is also taken from Datomic. The system partitions,
value types and attributes, with Datomic's own entity ids, docs and
transaction ids, were dumped from a fresh `datomic:mem` database. New entity
ids are allocated the way Datomic allocates them:

- transaction ids are `t` in partition 3;
- other ids share the `t` counter in their partition;
- attributes get their own counter in the db partition.

So the ids a program sees are the ids Datomic would give it.

## How to read this book

- [Installation](installation.md) and [Your first program](first-program.md)
  get a program compiled and running.
- [Getting started](getting-started/overview.md) walks through the Seattle
  example in `examples/seattle/`, explaining each step and showing the output
  it produces.
- The [Reference](reference/support.md) part lists what is supported, where
  nitomic differs from Datomic, and how the port is tested.
