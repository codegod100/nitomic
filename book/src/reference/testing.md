# How it is tested

nitomic is tested by comparison with Datomic itself. Each test program uses
only `datomic.api`, runs unchanged on the JVM against Datomic Pro and
natively against nitomic, and prints its results in a canonical order. The
native output must match the recorded JVM output line for line.

## The test programs

| program | what it covers | expected output |
|---|---|---|
| `examples/seattle/getting_started.clj` | Datomic's getting-started walkthrough over the Seattle data (the [Getting started](../getting-started/overview.md) part of this book) | recorded on Datomic Pro |
| `test/features.clj` | the rest of the API, including error cases | recorded on Datomic Pro |
| `test/native.clj` | nitomic-only extensions, such as `d/register-fn!` | written for nitomic |
| `test/storage.clj` | durable storage (SQLite, or PostgreSQL via `NITOMIC_TEST_STORAGE`): replay, two connections sharing a storage, the catalog | written for nitomic |
| `test/transactor.clj` | the transactor: queued transactions, errors, tempids, stopping and withdrawal | written for nitomic |

## Running the tests

```bash
CLONIM=path/to/clonim/bin/clonim test/run.sh
# or, with clonim on PATH
nimble test
```

`test/run.sh` runs each program with `clonim run ... --source-path src`,
diffs its output against `test/expected/<name>.out`, and prints `ok` or
`FAIL` with the first lines of the diff:

```text
ok   getting_started
ok   features
ok   native
ok   storage
ok   transactor
```

The storage and transactor tests use a SQLite file unless
`NITOMIC_TEST_STORAGE` names another storage. Given a PostgreSQL URL, they
must print exactly the same output.

CI (`.github/workflows/test.yml`) builds clonim with Nim 2.2.12 and runs the
same script on every push and pull request twice: once as is, and once
against a PostgreSQL service container.

## Re-recording the expected output

The expected outputs of the first two programs were recorded by
`script/reference.sh` on the JVM against Datomic Pro 1.0.7705. To re-record
them from a distribution:

```bash
curl -O https://datomic-pro-downloads.s3.amazonaws.com/1.0.7705/datomic-pro-1.0.7705.zip
unzip datomic-pro-1.0.7705.zip
script/reference.sh datomic-pro-1.0.7705
```

The script runs each program with `clojure.main` on the peer jar, and drops
the JVM's log lines and reflection warnings so that only program output is
kept.

## Writing a comparable program

To make a new program comparable across the two platforms, follow the
walkthrough's conventions:

- print results through a canonical printer (see the `show` helpers in the
  [Overview](../getting-started/overview.md)), so that set order doesn't
  matter;
- print instants as a placeholder and leave tempids out, since they differ
  between runs and platforms;
- end with `(System/exit 0)` so the JVM run terminates.
