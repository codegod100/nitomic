# nitomic on Modal

`app.py` deploys nitomic to [Modal](https://modal.com) as two pieces, backed by
PostgreSQL from any provider:

- **`web`**: an HTTP API (`api.py`). Each container runs one long-lived
  nitomic peer (`script/peer_server.clj`), which keeps its databases in
  memory and catches up from PostgreSQL, woken by `NOTIFY`. Containers
  scale with traffic.
- **`Transactor`**: one always-on container running `nitomic-transactor`,
  restarted if it exits. It's optional: without it, each peer writes the
  log itself under PostgreSQL's advisory lock.

## Deploying

1. Create a PostgreSQL database and copy its **direct** connection string,
   for example `postgresql://user:pass@host:5432/db?sslmode=require`. Don't
   use a transaction-mode pooler (PgBouncer, Neon's `-pooler` host,
   Supabase's port 6543): it breaks `LISTEN` and the transactor's session
   lock.
2. Store it as a Modal Secret named `nitomic-postgres`, under the key
   `DATABASE_URL`:

   ```bash
   modal secret create nitomic-postgres DATABASE_URL='postgresql://...'
   ```

3. Deploy from the repository root:

   ```bash
   pip install modal
   modal deploy deploy/modal/app.py
   ```

   The first deploy builds the image: Nim, clonim (`CLONIM_REF`, default
   `main`), and the `nitomic-peer` and `nitomic-transactor` binaries.
   Set `NITOMIC_TRANSACTOR=0` to deploy without the transactor.

Modal prints the API's URL.

## The API

Bodies and answers are EDN (`application/edn`). Errors are HTTP 400 with
`{:error "..." :data {...}}`.

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
