# nitomic on Modal

`app.py` deploys nitomic to [Modal](https://modal.com) as two pieces, backed by
PostgreSQL. [Neon](https://neon.com) works well: its storage outlives any
compute, and the compute scales to zero when idle.

- **`web`**: an HTTP API (`api.py`). Each container runs one long-lived
  nitomic peer (`script/peer_server.clj`), which keeps its databases in
  memory and catches up from PostgreSQL, woken by `NOTIFY`. Containers
  scale with traffic.
- **`Transactor`**: one always-on container running `nitomic-transactor`,
  restarted if it exits. It's optional: without it, each peer writes the
  log itself under PostgreSQL's advisory lock.

## Deploying with Neon

1. **Create a Neon project** in the region nearest Modal's, `aws-us-east-1`.
   Writes cross the network twice, so distance shows directly: from
   `us-west-2`, writes took about 0.55 s; from `us-east-1`, about 0.2 s.
   Use the console at [console.neon.tech](https://console.neon.tech),
   or the CLI (`npx neon@latest`, then `neon login` and `neon projects create`).
2. **Copy the direct connection string, not the pooled one.** In the console's
   *Connect* dialog, turn **Connection pooling off**; the host must not
   contain `-pooler`. It looks like
   `postgresql://neondb_owner:...@ep-cool-name-123456.us-east-2.aws.neon.tech/neondb?sslmode=require`.
   Neon's pooler runs PgBouncer in transaction mode, which doesn't support
   `LISTEN`/`NOTIFY` or session advisory locks. nitomic relies on both, for
   push and for the transactor's claim.
3. **Store it as a Modal Secret** named `nitomic-postgres`, under the key
   `DATABASE_URL`:

   ```bash
   modal secret create nitomic-postgres DATABASE_URL='postgresql://...'
   ```

4. **Deploy** from the repository root:

   ```bash
   pip install modal
   modal deploy deploy/modal/app.py                         # API and transactor
   NITOMIC_TRANSACTOR=0 modal deploy deploy/modal/app.py    # API only
   ```

   The first deploy builds the image: Nim, clonim (`CLONIM_REF`, default
   `main`), and the `nitomic-peer` and `nitomic-transactor` binaries.

Modal prints the API's URL. Any other PostgreSQL works the same way: give
`DATABASE_URL` its direct connection string.

### Neon and scale to zero

Neon suspends a compute after 5 minutes without activity, and wakes it on
the next connection.

- **Dropped connections.** A suspend drops open connections, and nitomic
  recovers from that:
  - The API's peer reconnects on its next request. A read is retried at
    once.
  - A transaction whose connection dropped answers 503 (it may or may not
    have committed); check, then resend.
  - The transactor exits and is restarted, reconnecting.
  - `api.py` adds TCP keepalives and a connect timeout to `DATABASE_URL` (unless
    it already sets them), so a dead connection is noticed within a minute.
- **The transactor keeps the compute awake.** It looks at its queue a
  couple of times a second, so while it runs the compute never suspends and
  bills around the clock. On Neon's free plan, or for a quiet app, deploy
  with `NITOMIC_TRANSACTOR=0`: peers then write the log themselves, and
  the compute sleeps between requests.
- **Scaling down.** Modal also scales idle API containers down. A peer's
  first request after that replays the log from Neon.

## The API

Bodies and answers are EDN (`application/edn`). Errors are HTTP 400 with
`{:error "..." :data {...}}`. A request the peer doesn't answer within
`NITOMIC_PEER_TIMEOUT` seconds (default 30) gets 504, and the peer is
replaced. For a transaction that means the outcome is unknown, as with 503.

| request | body | answer |
|---|---|---|
| `GET /health` | | `:pong` |
| `GET /databases` | | `["app" ...]` |
| `POST /db/{name}` | | `true` if created, `false` if it existed |
| `POST /db/{name}/transact` | tx-data | `{:t n :tempids {...} :tx-data [[e a v tx added] ...]}` |
| `POST /db/{name}/q?arg=<edn>...` | query | the result; each `arg` is an input after the database |
| `POST /db/{name}/pull` | `{:pattern [...] :eid ...}` | the entity map |

```bash
URL=https://<workspace>--nitomic-web.modal.run
curl -X POST $URL/db/app
curl -X POST $URL/db/app/transact --data-binary '[{:db/ident :person/name
  :db/valueType :db.type/string :db/cardinality :db.cardinality/one
  :db/unique :db.unique/identity}]'
curl -X POST $URL/db/app/transact --data-binary '[{:person/name "Ada"}]'
curl -X POST $URL/db/app/q --data-binary '[:find [?n ...] :where [_ :person/name ?n]]'
curl -X POST "$URL/db/app/q?arg=%22Ada%22" \
  --data-binary '[:find ?e . :in $ ?n :where [?e :person/name ?n]]'
```

The API has no authentication. Put it behind Modal's proxy auth
(`@modal.asgi_app(requires_proxy_auth=True)`) or your own before exposing
real data.

## Testing locally

`api.py` is plain FastAPI, and `test_api.py` runs it against a real peer
and storage:

```bash
clonim build script/peer_server.clj --source-path src -o nitomic-peer
cd deploy/modal
NITOMIC_PEER=../../nitomic-peer \
NITOMIC_STORAGE='jdbc:postgresql://localhost/test?user=postgres&password=postgres' \
  python -m pytest test_api.py
```

CI runs it against a PostgreSQL service container.
