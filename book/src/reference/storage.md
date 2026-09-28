# Durable storage

A `datomic:sql` URI with a JDBC URL makes a database durable, in SQLite or
in PostgreSQL:

```clojure
;; one machine: a SQLite file
(def uri "datomic:sql://hello?jdbc:sqlite:/var/lib/app/datomic.db")
;; any number of machines: a PostgreSQL database, from any provider
(def uri (str "datomic:sql://hello?jdbc:postgresql://db.example.com:5432/app"
              "?user=app&password=" (System/getenv "PGPASSWORD") "&sslmode=require"))

(d/create-database uri)
(def conn (d/connect uri))
```

The storage is created on first use and can hold any number of databases.
`create-database`, `delete-database`, `rename-database` and
`get-database-names` (with `datomic:sql://*?<jdbc-url>`) act on its catalog.
A PostgreSQL URL is handed to libpq without its `jdbc:` prefix, so anything
libpq accepts works, TLS options included.

- **What is stored.** Each transaction is stored as one log row: the datoms
  it produced, its tempids and the id counters after it. `connect` replays
  the log to rebuild the database, so every id and value comes back as the
  transaction made it. The transaction logic never runs twice.
- **Several processes.** Any number of processes can share a storage.
  `transact` takes the storage's write lock, applies what others have
  committed since it last looked, transacts, and stores the result before
  releasing the lock. The lock is `BEGIN IMMEDIATE` on SQLite and a
  transaction-scoped advisory lock on PostgreSQL. So each process acts as
  its own transactor, one at a time.
- **Seeing other writers.** `d/db`, `sync` and reading a `tx-report-queue`
  pick up other writers' transactions, with their `:tempids`.
- **Push.** On PostgreSQL every commit sends a `NOTIFY`, so a process
  waiting for a transaction wakes as soon as it lands. That covers
  dereferencing a queued transaction and a `tx-report-queue`'s `.take`,
  which blocks until the next transaction. SQLite has no way to tell other
  processes, so there waiting means looking again every couple of
  milliseconds, and `.take` returns nil when the queue is empty.
- **Transaction functions.** A `:db/fn` holds a Clojure fn, which can't be
  stored. Installing one in a stored database fails with
  `:db.error/not-storable`.
- **Releasing.** `release` drops a stored connection from the cache, so the
  next `connect` rebuilds it from storage.
- **Transactor.** Running a transactor makes it the only process that
  writes the log (see [Running a transactor](#running-a-transactor)).
- **Requirements.** Storage uses clonim's `clonim.sqlite` and
  `clonim.postgres`. They load `libsqlite3` or `libpq` when a stored
  database is first used.

## Running a transactor

```bash
clonim build script/transactor.clj --source-path src -o nitomic-transactor
./nitomic-transactor 'jdbc:postgresql://host/app?user=app&password=...'
./nitomic-transactor /var/lib/app/datomic.db   # SQLite
NITOMIC_STORAGE='jdbc:postgresql://...' ./nitomic-transactor
```

`nitomic.transactor` serves every database in a storage. It claims the
storage while it runs:

- **The claim.** On PostgreSQL the claim is a session advisory lock, which
  the server drops the moment the transactor's connection goes, even if it
  is killed. SQLite has nothing like that, so there the transactor records
  a heartbeat every second instead.
- **Queued transactions.** While a transactor has the storage,
  `d/transact` doesn't write the log. It queues the transaction data and
  returns a future at once. The transactor takes queued transactions in
  order. For each one, in a single transaction, it runs it, appends it to
  the log, and records the outcome. Dereferencing the future waits for that
  outcome, then returns the report or throws the transactor's error.
- **Waiting.** On PostgreSQL the transactor sleeps until a peer's `NOTIFY`
  says something was queued, and peers sleep until its `NOTIFY` says their
  transaction is done.
- **Clock.** The transactor stamps `:db/txInstant` with its own clock, as
  Datomic's does.
- **Only one at a time.** A second transactor refuses to start
  (`:db.error/transactor-running`).
- **When it stops.** Peers go back to writing the log themselves, so the
  databases stay writable. On PostgreSQL that happens at once; on SQLite,
  after three missed heartbeats. A transaction still waiting in the queue at
  that point is withdrawn and fails with `:db.error/transactor-unavailable`.
- **What can be queued.** Transaction data crosses processes as EDN, with
  tempids and datoms encoded. A fn can't be sent and fails with
  `:db.error/not-storable`.
- **Programmatic use.** `nitomic.transactor/run` serves a storage until its
  `:stop?` fn returns true. `start`, `step!` and `stop!` drive it one batch at
  a time.

## Deploying on Modal

On [Modal](https://modal.com) every container is its own machine, and
containers come and go, so use PostgreSQL storage from any provider (Neon,
Supabase, RDS, your own). A Modal Volume can't hold a shared SQLite file:
its changes only reach other containers through `commit()` and `reload()`,
which SQLite's locking knows nothing about.

- **Build.** Build each program with `clonim build`, and copy the binaries
  into an image that has `libpq5` and `libpcre3` installed.
- **Credentials.** Keep the connection URL in a Modal Secret and read it
  with `System/getenv`.
- **Direct connections.** Use the provider's direct connection string, not
  a transaction-mode pooler (PgBouncer, Neon's `-pooler` host, Supabase's
  port 6543). A pooler hands each transaction to a different server
  session, which breaks `LISTEN` and the transactor's session lock.
- **Peers.** Run your app as usual (for example behind
  `@modal.web_server`). Every container is a peer: it connects, replays the
  log once, and then catches up.
- **Transactor (optional).** Run `nitomic-transactor` as a single
  always-on function (`min_containers=1`, `max_containers=1`). If Modal
  restarts it, peers write the log themselves until it is back.
