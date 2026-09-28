# Queries

`d/q` takes a query and its inputs, and the first input is usually a
database. A query is data: a vector (or a map, or a string) with `:find`,
optional `:in` and `:with`, and `:where`.

```clojure
(d/q '[:find ?c :where [?c :community/name]] (d/db conn))
```

The `:where` clauses are data patterns `[entity attribute value]`. A symbol
starting with `?` is a variable, `_` matches anything, and a trailing
position can be left out. Clauses run in the order written, in nitomic as in
Datomic, so put the most selective clause first.

## Find specs

The shape of the result is set by the find spec:

| find spec | returns | example |
|---|---|---|
| `:find ?a ?b` | a set of tuples | `#{[1 "x"] [2 "y"]}` |
| `:find [?a ...]` | a collection of values | `["x" "y"]` |
| `:find [?a ?b]` | a single tuple | `[1 "x"]` |
| `:find ?a .` | a single value | `"x"` |

The collection form is handy for lists of names:

```clojure
(d/q '[:find [?n ...] :where [_ :community/name ?n]] (d/db conn))
```

```text
community names, coll find: ("15th Ave Community" "Admiral Neighborhood Association" ...)
```

Note the result has no duplicates: several communities share a name (there
are three "Magnolia Voice" entries, one per medium), but the collection find
returns each name once. The entity-based "community names" listing in the
previous chapter showed all 150.

## Constants in patterns

A pattern can fix any position to a constant:

```clojure
(d/q '[:find [?c ...]
       :where
       [?e :community/name "belltown"]
       [?e :community/category ?c]]
     (d/db conn))
```

```text
belltown categories: ("events" "news")
```

An enum can be given by its keyword in the value position of a ref
attribute:

```clojure
(d/q '[:find [?n ...]
       :where
       [?c :community/name ?n]
       [?c :community/type :community.type/twitter]]
     (d/db conn))
```

```text
twitter feeds: ("Columbia Citizens" "Discover SLU" "Fremont Universe" "Magnolia Voice" "Maple Leaf Life" "MyWallingford")
```

## Joins

When a variable appears in several clauses, the clauses join on it. This
query walks from community to neighborhood to district to region:

```clojure
(d/q '[:find [?c_name ...]
       :where
       [?c :community/name ?c_name]
       [?c :community/neighborhood ?n]
       [?n :neighborhood/district ?d]
       [?d :district/region :region/ne]]
     (d/db conn))
```

```text
NE region: ("Aurora Seattle" "Hawthorne Hills Community Website" "KOMO Communities - U-District" "KOMO Communities - View Ridge" "Laurelhurst Community Club" "Magnuson Community Garden" "Magnuson Environmental Stewardship Alliance" "Maple Leaf Community Council" "Maple Leaf Life")
```

To get an enum back as a keyword, join through `:db/ident`:

```clojure
(d/q '[:find ?c_name ?r_name
       :where
       [?c :community/name ?c_name]
       [?c :community/neighborhood ?n]
       [?n :neighborhood/district ?d]
       [?d :district/region ?r]
       [?r :db/ident ?r_name]]
     (d/db conn))
```

```text
names and regions: (["15th Ave Community" :region/e] ["Admiral Neighborhood Association" :region/sw] ...)
```

## Parameters with `:in`

`:in` names the inputs. `$` is the database; other names bind the extra
arguments to `d/q`. A query with a parameter can be defined once and reused:

```clojure
(def query-by-type '[:find [?n ...]
                     :in $ ?t
                     :where
                     [?c :community/name ?n]
                     [?c :community/type ?t]])

(d/q query-by-type (d/db conn) :community.type/twitter)
(d/q query-by-type (d/db conn) :community.type/facebook-page)
```

```text
by type: twitter: ("Columbia Citizens" "Discover SLU" "Fremont Universe" "Magnolia Voice" "Maple Leaf Life" "MyWallingford")
by type: facebook: ("Blogging Georgetown" "Columbia Citizens" "Discover SLU" "Eastlake Community Council" "Fauntleroy Community Association" "Fremont Universe" "Magnolia Voice" "Maple Leaf Life" "MyWallingford")
```

The same works with a pull in the find spec:

```clojure
(def query-by-type-with-pull '[:find (pull ?c [:community/name])
                               :in $ ?t
                               :where
                               [?c :community/type ?t]])
```

```text
by type with pull: twitter: ([{:community/name "Columbia Citizens"}] [{:community/name "Discover SLU"}] ...)
```

### Binding forms

An input can be destructured:

| binding | binds | input |
|---|---|---|
| `?x` | a scalar | `:community.type/twitter` |
| `[?x ?y]` | a tuple | `[:a :b]` |
| `[?x ...]` | a collection, one match per element | `[:a :b :c]` |
| `[[?x ?y]]` | a relation, one match per tuple | `[[:a 1] [:b 2]]` |

A collection binding acts as an "or" over its values:

```clojure
(d/q '[:find ?n ?t
       :in $ [?t ...]
       :where
       [?c :community/name ?n]
       [?c :community/type ?t]]
     (d/db conn)
     [:community.type/facebook-page :community.type/twitter])
```

```text
collection input: (["Blogging Georgetown" :community.type/facebook-page] ["Columbia Citizens" :community.type/facebook-page] ["Columbia Citizens" :community.type/twitter] ...)
```

A relation binding matches whole tuples, here pairs of type and orgtype:

```clojure
(d/q '[:find ?n ?t ?ot
       :in $ [[?t ?ot]]
       :where
       [?c :community/name ?n]
       [?c :community/type ?t]
       [?c :community/orgtype ?ot]]
     (d/db conn)
     [[:community.type/email-list :community.orgtype/community]
      [:community.type/website :community.orgtype/commercial]])
```

```text
relation input: (["15th Ave Community" :community.type/email-list :community.orgtype/community] ... ["Discover SLU" :community.type/website :community.orgtype/commercial] ...)
```

## Predicates and functions

A clause of the form `[(f args...)]` is a predicate: it keeps the matches
for which it returns true. A clause `[(f args...) ?out]` is a function call
whose result is bound to `?out`. Here, `.compareTo` computes an ordering
and `<` filters on it:

```clojure
(d/q '[:find [?n ...]
       :where
       [?c :community/name ?n]
       [(.compareTo ?n "C") ?res]
       [(< ?res 0)]]
     (d/db conn))
```

```text
names before C: ("15th Ave Community" "Admiral Neighborhood Association" ... "Blogging Georgetown" "Broadview Community Council")
```

> **nitomic:** there is no runtime code loading, so query functions come
> from a built-in table of `clojure.core` and string functions (including
> `.compareTo`, `.startsWith` and the like). You can also pass a function as
> a query input, or register one by name with `d/register-fn!`. See
> [Differences from Datomic](../reference/differences.md).

## Fulltext search

`fulltext` searches an attribute declared with `:db/fulltext true`. It
takes the database, the attribute and the search string, and binds a
relation of `[entity value]` (Datomic also offers `tx` and `score`):

```clojure
(d/q '[:find ?n .
       :where
       [(fulltext $ :community/name "Wallingford") [[?e ?n]]]]
     (d/db conn))
```

```text
fulltext Wallingford: "KOMO Communities - Wallingford"
```

Fulltext combines with ordinary clauses and inputs:

```clojure
(d/q '[:find ?name ?cat
       :in $ ?type ?search
       :where
       [?c :community/name ?name]
       [?c :community/type ?type]
       [(fulltext $ :community/category ?search) [[?c ?cat]]]]
     (d/db conn)
     :community.type/website
     "food")
```

```text
fulltext food websites: (["Community Harvest of Southwest Seattle" "sustainable food"] ["InBallard" "food"])
```

> **nitomic:** fulltext tokenizes on letters and digits, lower-cases, drops
> English stop words, and matches any query term (`term*` matches a prefix).
> On typical text that matches Lucene's default analyzer, but it is not
> Lucene's full query syntax, and every score is 1.0.
