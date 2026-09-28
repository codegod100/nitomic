# The Seattle data model

The schema in `examples/seattle/seattle-schema.edn` describes three kinds of
entity, linked by references:

```text
community ──:community/neighborhood──▶ neighborhood ──:neighborhood/district──▶ district
    │                                                                              │
    ├─ :community/type     ─▶ enum (:community.type/...)                          └─ :district/region ─▶ enum (:region/...)
    └─ :community/orgtype  ─▶ enum (:community.orgtype/...)
```

## Attributes

| attribute | type | cardinality | notes |
|---|---|---|---|
| `:community/name` | string | one | fulltext |
| `:community/url` | string | one | |
| `:community/neighborhood` | ref | one | a neighborhood |
| `:community/category` | string | **many** | fulltext |
| `:community/orgtype` | ref | one | an orgtype enum |
| `:community/type` | ref | **many** | type enums |
| `:neighborhood/name` | string | one | `:db.unique/identity` |
| `:neighborhood/district` | ref | one | a district |
| `:district/name` | string | one | `:db.unique/identity` |
| `:district/region` | ref | one | a region enum |

Each attribute is installed with a plain map:

```clojure
{:db/ident :community/name
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/fulltext true
 :db/doc "A community's name"}
```

Datomic (and nitomic) install an attribute implicitly when a transaction
asserts `:db/ident`, `:db/valueType` and `:db/cardinality` for a new entity;
no `:db.install/_attribute` is needed.

### Unique identities

`:neighborhood/name` and `:district/name` are `:db.unique/identity`. When a
transaction asserts one of those values for a tempid, and an entity already
has that value, the tempid resolves to the existing entity instead of
creating a new one. This is called *upsert*. It is what lets the second batch
of data (`seattle-data1.edn`) mention "Beacon Hill" again without creating a
second Beacon Hill.

### Fulltext

`:community/name` and `:community/category` are `:db/fulltext`, which makes
them searchable with the `fulltext` query function (see
[Queries](queries.md#fulltext-search)).

## Enums

Enumerated values are entities with nothing but a `:db/ident`. Refs to them
can be written as the keyword:

```clojure
[:db/add #db/id[:db.part/user] :db/ident :community.type/twitter]
```

| enum | values |
|---|---|
| `:community.orgtype/...` | `community` `commercial` `nonprofit` `personal` |
| `:community.type/...` | `email-list` `twitter` `facebook-page` `blog` `website` `wiki` `myspace` `ning` |
| `:region/...` | `n` `ne` `e` `se` `s` `sw` `w` `nw` |

In an entity, a ref to an enum reads back as its keyword. In a query, you
join through `:db/ident` to get the keyword, or pass the keyword as an
input.

## The data

The data files are vectors of maps. Each map carries a `#db/id` tempid, and
references between new entities use the same tempid:

```clojure
{:district/region :region/e, :db/id #db/id[:db.part/user -1000001], :district/name "East"}
{:db/id #db/id[:db.part/user -1000002], :neighborhood/name "Capitol Hill",
 :neighborhood/district #db/id[:db.part/user -1000001]}
{:community/category ["15th avenue residents"],
 :community/orgtype :community.orgtype/community,
 :community/type :community.type/email-list,
 :db/id #db/id[:db.part/user -1000003],
 :community/name "15th Ave Community",
 :community/url "http://groups.yahoo.com/group/15thAve_Community/",
 :community/neighborhood #db/id[:db.part/user -1000002]}
```

- `seattle-data0.edn` holds 150 communities with their neighborhoods and
  districts.
- `seattle-data1.edn` holds 108 more, added in [Time travel](time.md).
