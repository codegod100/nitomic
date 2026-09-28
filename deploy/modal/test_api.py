"""The HTTP API over a real nitomic peer and storage.

    NITOMIC_PEER=path/to/nitomic-peer \\
    NITOMIC_STORAGE='jdbc:postgresql://localhost/test?user=postgres&password=postgres' \\
      pytest deploy/modal/test_api.py

Uses a fresh database name each run, so it doesn't disturb others in the
storage.
"""

import os
import uuid

import pytest
from fastapi.testclient import TestClient

import api


@pytest.fixture(scope="module")
def peer():
    env = {**os.environ, "NITOMIC_STORAGE": api.storage_from_env()}
    p = api.Peer(os.environ["NITOMIC_PEER"], env)
    yield p
    p.close()


@pytest.fixture(scope="module")
def client(peer):
    return TestClient(api.create_app(peer))


@pytest.fixture(scope="module")
def db(client):
    name = "t" + uuid.uuid4().hex[:12]
    assert client.post(f"/db/{name}").text == "true"
    assert client.post(f"/db/{name}").text == "false"
    r = client.post(f"/db/{name}/transact", content="""
        [{:db/ident :person/name :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
         {:db/ident :person/bio :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one}]""")
    assert r.status_code == 200
    return name


def test_health(client):
    r = client.get("/health")
    assert (r.status_code, r.text) == (200, ":pong")
    assert r.headers["content-type"] == "application/edn"


def test_transact_and_query(client, db):
    r = client.post(f"/db/{db}/transact",
                    content='[{:db/id "ada" :person/name "Ada"} {:db/id #db/id[:db.part/user -1] :person/name "Grace"}]')
    assert r.status_code == 200
    assert '"ada" 17592186045' in r.text and "-1 17592186045" in r.text
    r = client.post(f"/db/{db}/q", content="[:find [?n ...] :where [_ :person/name ?n]]")
    assert sorted(r.text.strip("[]").split()) == ['"Ada"', '"Grace"']


def test_multiline_strings_and_the_end_marker_inside_them(client, db):
    bio = "line one\\nline two %%end%% not alone"
    client.post(f"/db/{db}/transact", content=f'[{{:person/name "Hedy" :person/bio "{bio}"}}]')
    r = client.post(f"/db/{db}/q", params={"arg": '"Hedy"'},
                    content="[:find ?b . :in $ ?n :where [?e :person/name ?n] [?e :person/bio ?b]]")
    assert r.text == f'"{bio}"'


def test_pull(client, db):
    client.post(f"/db/{db}/transact", content='[{:person/name "Barbara"}]')
    r = client.post(f"/db/{db}/pull", content='{:pattern [:person/name] :eid [:person/name "Barbara"]}')
    assert r.text == '{:person/name "Barbara"}'


def test_errors(client, db):
    r = client.post(f"/db/{db}/transact", content='[[:db/add "x" :nope 1]]')
    assert r.status_code == 400 and ":db.error/not-an-entity" in r.text
    assert client.post("/db/bad name!/q", content="[:find ?e]").status_code in (400, 404)
    assert client.post(f"/db/{db}/transact", content="[\n%%end%%\n]").status_code == 400
    assert client.post(f"/db/{db}/q", content="  ").status_code == 400


def test_databases(client, db):
    assert f'"{db}"' in client.get("/databases").text


def test_peer_restarts_and_replays(client, peer, db):
    peer.proc.kill()
    peer.proc.wait()
    assert client.get("/health").text == ":pong"
    r = client.post(f"/db/{db}/q", content='[:find ?e . :where [?e :person/name "Ada"]]')
    assert r.status_code == 200 and r.text.startswith("17592186045")


def test_storage_from_env(monkeypatch):
    monkeypatch.delenv("NITOMIC_STORAGE", raising=False)
    monkeypatch.setenv("DATABASE_URL", "postgres://u:p@h:5432/d?sslmode=require")
    assert api.storage_from_env() == "jdbc:postgresql://u:p@h:5432/d?sslmode=require"
    monkeypatch.setenv("DATABASE_URL", "mysql://nope")
    with pytest.raises(RuntimeError):
        api.storage_from_env()
