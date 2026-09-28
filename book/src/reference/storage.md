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
  pick up other writers' transactions. Their reports include `:tempids`,
  which are stored with each transaction.
- **Transaction functions.** A `:db/fn` holds a Clojure fn, which can't be
  written to a file. Installing one in a stored database fails with
  `:db.error/not-storable`.
- **Releasing.** `release` drops a stored connection from the cache, so the
  next `connect` rebuilds it from the file.
- **Transactor.** Running a transactor makes it the only process that
  writes the log (see [Running a transactor](#running-a-transactor)).
- **Requirements.** Storage uses clonim's `clonim.sqlite`, which loads
  `libsqlite3` when a stored database is first used.

## Running a transactor

```bash
clonim build script/transactor.clj --source-path src -o nitomic-transactor
./nitomic-transactor /var/lib/app/datomic.db     # or datomic:sql://*?jdbc:sqlite:<path>
```

`nitomic.transactor` serves every database in a storage file. While it
runs, it records a heartbeat in the file every second.

- **Queued transactions.** While a transactor's heartbeat is fresh,
  `d/transact` doesn't write the log. It queues the transaction data in the
  file and returns a future at once. The transactor takes queued
  transactions in order. For each one, in a single SQLite transaction, it
  runs it, appends it to the log, and records the outcome. Dereferencing the
  future waits for that outcome, then returns the report or throws the
  transactor's error.
- **Clock.** The transactor stamps `:db/txInstant` with its own clock, as
  Datomic's does.
- **Only one at a time.** A second transactor refuses to start while one is
  alive (`:db.error/transactor-running`).
- **When it stops.** After three missed heartbeats (3 s), peers go back to
  writing the log themselves, so the databases stay writable. A transaction
  still waiting in the queue at that point is withdrawn and fails with
  `:db.error/transactor-unavailable`.
- **What can be queued.** Transaction data crosses processes as EDN, with
  tempids and datoms encoded. A fn can't be sent and fails with
  `:db.error/not-storable`.
- **Programmatic use.** `nitomic.transactor/run` serves a file until its
  `:stop?` fn returns true. `start`, `step!` and `stop!` drive it one batch at
  a time.

