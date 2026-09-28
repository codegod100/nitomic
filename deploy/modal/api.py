"""An HTTP API over a nitomic peer process (script/peer_server.clj).

Plain FastAPI, so it runs anywhere; deploy/modal/app.py serves it on Modal.
Each process keeps one long-lived peer, which holds its databases in memory
and catches up with other writers as a Datomic peer does. Requests to it
are serialized. Bodies and answers are EDN text:

    GET  /databases                      -> ["app" ...]
    POST /db/{name}                      -> true if created, false if it existed
    POST /db/{name}/transact  [tx-data]  -> {:t n :tempids {...} :tx-data [...]}
    POST /db/{name}/q         [query]    -> the result; ?arg=<edn>&arg=... are inputs
    POST /db/{name}/pull      {:pattern [...] :eid ...}  -> the entity map

Errors come back as HTTP 400 with {:error "..." :data {...}}.
"""

import os
import re
import subprocess
import threading

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import Response

END = "%%end%%"
NAME = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
EDN = "application/edn"


class Peer:
    """One nitomic-peer process, restarted if it dies."""

    def __init__(self, binary: str, env: dict):
        self.binary = binary
        self.env = env
        self.proc = None
        self.lock = threading.Lock()

    def _ensure(self):
        if self.proc is None or self.proc.poll() is not None:
            self.proc = subprocess.Popen(
                [self.binary],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                env=self.env,
                text=True,
                encoding="utf-8",
            )

    def call(self, request: str) -> str:
        with self.lock:
            self._ensure()
            self.proc.stdin.write(request + "\n" + END + "\n")
            self.proc.stdin.flush()
            line = self.proc.stdout.readline()
            if not line:
                self.proc = None
                raise HTTPException(503, "nitomic peer exited")
            return line.rstrip("\n")

    def close(self):
        if self.proc is not None and self.proc.poll() is None:
            self.proc.stdin.close()
            self.proc.wait(timeout=10)


def edn_string(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def answer(text: str) -> Response:
    # The peer answers {:ok ...} or {:error ...}; unwrap the first.
    if text.startswith("{:ok "):
        return Response(text[len("{:ok "):-1], media_type=EDN)
    return Response(text, status_code=400, media_type=EDN)


def create_app(peer: Peer) -> FastAPI:
    app = FastAPI(title="nitomic")

    def db_name(name: str) -> str:
        if not NAME.match(name):
            raise HTTPException(400, "database names are 1-64 letters, digits, - or _")
        return edn_string(name)

    async def body(request: Request) -> str:
        text = (await request.body()).decode("utf-8")
        if not text.strip():
            raise HTTPException(400, "empty body")
        if any(line.strip() == END for line in text.splitlines()):
            raise HTTPException(400, "body contains the end marker")
        return text

    @app.get("/databases")
    def databases():
        return answer(peer.call("{:op :databases}"))

    @app.post("/db/{name}")
    def create(name: str):
        return answer(peer.call("{:op :create :db %s}" % db_name(name)))

    @app.post("/db/{name}/transact")
    async def transact(name: str, request: Request):
        tx = await body(request)
        return answer(peer.call("{:op :transact :db %s :tx-data\n%s\n}" % (db_name(name), tx)))

    @app.post("/db/{name}/q")
    async def q(name: str, request: Request, arg: list[str] = Query(default=[])):
        query = await body(request)
        args = "[" + "\n".join(arg) + "]"
        return answer(peer.call("{:op :q :db %s :args %s :query\n%s\n}" % (db_name(name), args, query)))

    @app.post("/db/{name}/pull")
    async def pull(name: str, request: Request):
        spec = await body(request)
        return answer(peer.call("{:op :pull :db %s :spec\n%s\n}" % (db_name(name), spec)))

    @app.get("/health")
    def health():
        return answer(peer.call("{:op :ping}"))

    return app


def storage_from_env() -> str:
    """NITOMIC_STORAGE, or a JDBC URL made from DATABASE_URL."""
    if os.environ.get("NITOMIC_STORAGE"):
        return os.environ["NITOMIC_STORAGE"]
    url = os.environ.get("DATABASE_URL", "")
    if url.startswith("postgres://"):
        url = "postgresql://" + url[len("postgres://"):]
    if not url.startswith("postgresql://"):
        raise RuntimeError("set DATABASE_URL (postgresql://...) or NITOMIC_STORAGE")
    return "jdbc:" + url
