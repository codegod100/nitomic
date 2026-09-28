# Entities and pull

There are two ways to get at the attributes of an entity: the entity API,
which gives a lazy, navigable map-like object, and pull, which gives plain
data in the shape you ask for.

## Finding the communities

```clojure
(def results (d/q '[:find ?c :where [?c :community/name]] (d/db conn)))
(show "communities" (count results))
```

```text
communities: 150
```

`(d/db conn)` returns the current database value, an immutable snapshot.
The query finds every entity `?c` that has a `:community/name`; the value
position of the pattern is left out, so it matches any value. The result is
a set of one-element tuples.

## Entities

```clojure
(def id (ffirst (sort-by first results)))
(def entity (-> conn d/db (d/entity id)))
(show "entity keys" (set (keys entity)))
(show "entity name" (:community/name entity))
```

```text
entity keys: #{:community/category :community/name :community/neighborhood :community/orgtype :community/type :community/url}
entity name: "15th Ave Community"
```

`d/entity` returns an entity for an id. Attributes are fetched lazily when
you look them up with a keyword or `get`. `keys` lists the attributes the
entity has.

The walkthrough sorts the results and takes the smallest id, rather than
using `ffirst` directly, so that both platforms pick the same community.

### Navigating references

A ref attribute returns another entity, so you can walk the graph:

```clojure
(let [db (d/db conn)]
  (show-sorted "names and neighborhoods"
               (map #(let [entity (d/entity db (first %))]
                       [(:community/name entity)
                        (-> entity :community/neighborhood :neighborhood/name)])
                    results)))
```

```text
names and neighborhoods: (["15th Ave Community" "Capitol Hill"] ["Admiral Neighborhood Association" "Admiral (West Seattle)"] ...)
```

### Reverse navigation

Prefixing the attribute name with `_` follows a reference backwards. From a
neighborhood, `:community/_neighborhood` returns every community that points
at it, as a set of entities:

```clojure
(def community (d/entity (d/db conn) (ffirst (sort-by first results))))
(def neighborhood (:community/neighborhood community))
(def communities (:community/_neighborhood neighborhood))
(show-sorted "communities in the same neighborhood" (map :community/name communities))
```

```text
communities in the same neighborhood: ("15th Ave Community" "CHS Capitol Hill Seattle Blog" "Capitol Hill Community Council" "Capitol Hill Housing" "Capitol Hill Triangle" "KOMO Communities - Captol Hill")
```

Other entity behaviour worth knowing:

- a cardinality-many attribute returns a set;
- a ref to an enum returns the enum's keyword;
- `d/touch` loads every attribute, and a touched entity prints as its
  attribute map;
- in nitomic, an untouched entity prints as `{:db/id n}`.

## Pull in a query

Put a `pull` expression in `:find` to get maps instead of ids:

```clojure
(def pull-results (d/q '[:find (pull ?c [*]) :where [?c :community/name]] (d/db conn)))
(show "pull results" (count pull-results))
```

```text
pull results: 150
```

The pattern `[*]` pulls every attribute. Refs come back as nested maps
holding `:db/id`, and cardinality-many attributes come back as vectors. Here
is the belltown community with its refs reduced to their keys:

```text
a pulled community: {:community/category ["events" "news"], :community/name "belltown", :community/neighborhood (:db/id), :community/orgtype (:db/id), :community/type ((:db/id)), :community/url "http://www.belltownpeople.com/"}
```

A pull pattern can name just the attributes you want, and a `:find` can mix
pulls with plain variables:

```clojure
(d/q '[:find ?n (pull ?c [:community/url])
       :where [?c :community/name ?n]]
     (d/db conn))
```

```text
names with urls: (["15th Ave Community" {:community/url "http://groups.yahoo.com/group/15thAve_Community/"}] ...)
```

Outside queries, `d/pull` and `d/pull-many` take a database, a pattern and
an entity id (or ids). Patterns support nested maps for refs, reverse
attributes, recursion, and the `:as`, `:limit` and `:default` options.
