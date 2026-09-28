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
import queue
import re
import subprocess
import threading

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import Response

END = "%%end%%"
NAME = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
EDN = "application/edn"


# Answers that mean the peer lost its database connection (the server
# restarted, or a Neon compute scaled to zero), rather than a problem with
# the request: SQLSTATE class 08, admin shutdown (57P01-57P03), or libpq's
# own messages for a dropped connection.
CONNECTION_LOST = re.compile(
    r':pg/sqlstate "(08|57P0)|server closed the connection|closed unexpectedly'
    r'|no connection to the server|connection is closed|Cannot connect|could not connect'
    r'|terminating connection')


class Peer:
    """One nitomic-peer process, restarted if it dies or loses its database.

    env is the process environment, or a function returning it, called each
    time the process starts."""

    def __init__(self, binary: str, env, timeout: float = None):
        self.binary = binary
        self.env = env
        # how long one request may take before the peer is presumed stuck
        self.timeout = timeout or float(os.environ.get("NITOMIC_PEER_TIMEOUT", "30"))
        self.proc = None
        self.lines = None
        self.lock = threading.Lock()

    def _ensure(self):
        if self.proc is None or self.proc.poll() is not None:
            env = self.env() if callable(self.env) else self.env
            self.proc = subprocess.Popen(
                [self.binary],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                env=env,
                text=True,
                encoding="utf-8",
            )
            # a thread reads the answers, so waiting for one can time out
            self.lines = queue.Queue()
            threading.Thread(target=self._read, args=(self.proc, self.lines), daemon=True).start()

    @staticmethod
    def _read(proc, lines):
        for line in proc.stdout:
            lines.put(line)
        lines.put("")  # the peer exited

    def _roundtrip(self, request: str) -> str:
        self._ensure()
        try:
            self.proc.stdin.write(request + "\n" + END + "\n")
            self.proc.stdin.flush()
            line = self.lines.get(timeout=self.timeout)
        except (BrokenPipeError, OSError):
            line = ""
        except queue.Empty:
            self._kill()
            raise HTTPException(504, f"nitomic peer didn't answer within {self.timeout:g} s; "
                                     "a transaction may or may not have been committed")
        if not line:
            self._kill()
            raise HTTPException(503, "nitomic peer exited")
        return line.rstrip("\n")

    def call(self, request: str, retry: bool = True) -> str:
        """The peer's answer. If it lost its database connection, the peer
        is restarted (reconnecting, at the database's current address) and,
        when retry is true, the request is sent once more. Pass retry=False
        for writes: a connection can drop after the server committed, and a
        retry would apply the transaction twice."""
        with self.lock:
            answer = self._roundtrip(request)
            if not answer.startswith("{:ok ") and CONNECTION_LOST.search(answer):
                self._kill()
                if not retry:
                    raise HTTPException(
                        503, "lost the database connection; the transaction may or "
                             "may not have been committed")
                answer = self._roundtrip(request)
            return answer

    def _kill(self):
        if self.proc is not None:
            if self.proc.poll() is None:
                self.proc.kill()
                self.proc.wait()
            self.proc = None

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
        return answer(await run_in_threadpool(
            peer.call, "{:op :transact :db %s :tx-data\n%s\n}" % (db_name(name), tx), False))

    @app.post("/db/{name}/q")
    async def q(name: str, request: Request, arg: list[str] = Query(default=[])):
        query = await body(request)
        args = "[" + "\n".join(arg) + "]"
        return answer(await run_in_threadpool(
            peer.call, "{:op :q :db %s :args %s :query\n%s\n}" % (db_name(name), args, query)))

    @app.post("/db/{name}/pull")
    async def pull(name: str, request: Request):
        spec = await body(request)
        return answer(await run_in_threadpool(
            peer.call, "{:op :pull :db %s :spec\n%s\n}" % (db_name(name), spec)))

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
    # Notice a connection whose far end went away (a Neon compute scaling to
    # zero, say) within a minute rather than hanging on it, and don't wait
    # forever for a server that is waking up.
    extra = [p for p in ["connect_timeout=15", "keepalives=1", "keepalives_idle=30",
                         "keepalives_interval=10", "keepalives_count=3"]
             if p.split("=")[0] + "=" not in url]
    if extra:
        url += ("&" if "?" in url else "?") + "&".join(extra)
    return "jdbc:" + url
