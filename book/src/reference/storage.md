# Durable storage

A `datomic:sql` URI naming a SQLite file makes a database durable:

```clojure
(def uri "datomic:sql://hello?jdbc:sqlite:/var/lib/app/datomic.db")
(d/create-database uri)
(def conn (d/connect uri))
```

The file is created on first use and can hold any number of databases.
`create-database`, `delete-database`, `rename-database` and
`get-database-names` (with `datomic:sql://*?jdbc:sqlite:<path>`) act on the
catalog in the file.

- **What is stored.** Each transaction is stored as one log row: the datoms
  it produced and the id counters after it. `connect` replays the log to
  rebuild the database, so every id and value comes back as the transaction
  made it. The transaction logic never runs twice.
- **Several processes.** Any number of processes can share a file.
  `transact` takes SQLite's write lock, applies what other processes have
  committed since it last looked, transacts, and stores the result before
  releasing the lock. So each process acts as its own transactor, one at a
  time.
- **Seeing other writers.** `d/db`, `sync` and reading a `tx-report-queue`
  pick up other writers' transactions. Those transactions reach the queue as
  reports with empty `:tempids`.
- **Transaction functions.** A `:db/fn` holds a Clojure fn, which can't be
  written to a file. Installing one in a stored database fails with
  `:db.error/not-storable`.
- **Releasing.** `release` drops a stored connection from the cache, so the
  next `connect` rebuilds it from the file.
- **Requirements.** Storage uses clonim's `clonim.sqlite`, which loads
  `libsqlite3` when a stored database is first used.

