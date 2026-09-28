# Rules

A rule is a named group of `:where` clauses. You pass a set of rules to a
query as the input named `%`, and call a rule like a clause.

## A simple rule

```clojure
(let [rules '[[[twitter ?c]
               [?c :community/type :community.type/twitter]]]]
  (d/q '[:find [?n ...]
         :in $ %
         :where
         [?c :community/name ?n]
         (twitter ?c)]
       (d/db conn)
       rules))
```

```text
rule: twitter: ("Columbia Citizens" "Discover SLU" "Fremont Universe" "Magnolia Voice" "Maple Leaf Life" "MyWallingford")
```

The rule set is a vector of rules. Each rule is a vector whose first element
is the head, `[name ?args...]`, followed by the body clauses.

## Rules with arguments

A rule packages up a join so queries don't have to repeat it. The `region`
rule relates a community to the keyword of its region:

```clojure
(let [rules '[[[region ?c ?r]
               [?c :community/neighborhood ?n]
               [?n :neighborhood/district ?d]
               [?d :district/region ?re]
               [?re :db/ident ?r]]]]
  (d/q '[:find [?n ...]
         :in $ %
         :where
         [?c :community/name ?n]
         [region ?c :region/ne]]
       (d/db conn)
       rules))
```

```text
rule: NE: ("Aurora Seattle" "Hawthorne Hills Community Website" ... "Maple Leaf Life")
rule: SW: ("Admiral Neighborhood Association" "Alki News" ... "Nature Consortium")
```

A rule call may be written in brackets, `[region ?c :region/ne]`, or in
parentheses, `(region ?c :region/ne)`; both mean the same. An argument can
be a variable or a constant.

## Or, by defining a rule more than once

Several rules with the same head are alternatives: an entity matches if any
of them matches. Rules can also call other rules. This set builds
`social-media`, `northern` and `southern` on top of `region`:

```clojure
(let [rules '[[[region ?c ?r]
               [?c :community/neighborhood ?n]
               [?n :neighborhood/district ?d]
               [?d :district/region ?re]
               [?re :db/ident ?r]]
              [[social-media ?c]
               [?c :community/type :community.type/twitter]]
              [[social-media ?c]
               [?c :community/type :community.type/facebook-page]]
              [[northern ?c]
               (region ?c :region/ne)]
              [[northern ?c]
               (region ?c :region/n)]
              [[northern ?c]
               (region ?c :region/nw)]
              [[southern ?c]
               (region ?c :region/sw)]
              [[southern ?c]
               (region ?c :region/s)]
              [[southern ?c]
               (region ?c :region/se)]]]
  (d/q '[:find [?n ...]
         :in $ %
         :where
         [?c :community/name ?n]
         (southern ?c)
         (social-media ?c)]
       (d/db conn)
       rules))
```

```text
rule: southern social media: ("Blogging Georgetown" "Columbia Citizens" "Fauntleroy Community Association" "MyWallingford")
```

nitomic also supports recursive rules, including over cyclic data, as well
as `or`, `or-join`, `and`, `not` and `not-join` clauses inside queries and
rules.
