"""nitomic on Modal: an HTTP API of peers, and an optional transactor.

    modal deploy deploy/modal/app.py        # from the repository root

Needs a Modal Secret named nitomic-postgres holding DATABASE_URL, a direct
(not pooled) PostgreSQL connection string, such as a Neon project's
postgresql://user:pass@ep-....aws.neon.tech/neondb?sslmode=require (see
README.md in this directory).

- `api` serves deploy/modal/api.py. Each container runs one nitomic peer
  process, which keeps its databases in memory and catches up from
  PostgreSQL, woken by NOTIFY. Containers scale with traffic.
- `Transactor` keeps one container running nitomic-transactor. While it
  runs, peers queue their transactions for it; if Modal restarts it, peers
  write the log themselves meanwhile. Set NITOMIC_TRANSACTOR=0 when
  deploying to leave it out.

The image builds clonim (CLONIM_REF, default main) and compiles the two
nitomic programs from this checkout.
"""

import os
import subprocess
import threading
import time

import modal

CLONIM_REF = os.environ.get("CLONIM_REF", "main")
# Read when deploying and baked into the image, so containers agree.
TRANSACTOR = os.environ.get("NITOMIC_TRANSACTOR", "1") != "0"
NIM_VERSION = "2.2.12"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))

image = (
    modal.Image.from_registry("ubuntu:24.04", add_python="3.12")
    .apt_install("build-essential", "curl", "git", "ca-certificates", "xz-utils",
                 "libpcre3", "libsqlite3-0", "libpq5")
    .run_commands(
        "curl -sSf https://nim-lang.org/choosenim/init.sh -o /tmp/choosenim.sh",
        f"CHOOSENIM_CHOOSE_VERSION={NIM_VERSION} sh /tmp/choosenim.sh -y",
    )
    .env({"PATH": "/root/.nimble/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"})
    .run_commands(
        f"git clone --depth 1 --branch {CLONIM_REF} https://github.com/codegod100/clonim /opt/clonim",
        "cd /opt/clonim && nim c -d:release --hints:off --warnings:off -o:bin/clonim src/clonim.nim",
    )
    .pip_install("fastapi[standard]")
    .add_local_dir(os.path.join(REPO, "src"), "/opt/nitomic/src", copy=True)
    .add_local_dir(os.path.join(REPO, "script"), "/opt/nitomic/script", copy=True)
    .run_commands(
        "cd /opt/nitomic && /opt/clonim/bin/clonim build script/peer_server.clj"
        " --source-path src -o /usr/local/bin/nitomic-peer",
        "cd /opt/nitomic && /opt/clonim/bin/clonim build script/transactor.clj"
        " --source-path src -o /usr/local/bin/nitomic-transactor",
    )
    .env({"NITOMIC_TRANSACTOR": "1" if TRANSACTOR else "0"})
    .add_local_python_source("api")
)

app = modal.App("nitomic", image=image)
secret = modal.Secret.from_name("nitomic-postgres")


def nitomic_env() -> dict:
    import api

    return {**os.environ, "NITOMIC_STORAGE": api.storage_from_env()}


@app.function(secrets=[secret])
@modal.concurrent(max_inputs=32)
@modal.asgi_app()
def web():
    import api

    # the peer reconnects after its database drops the connection (Neon
    # does when its compute scales to zero); nitomic_env is read each time
    return api.create_app(api.Peer("/usr/local/bin/nitomic-peer", nitomic_env))


if TRANSACTOR:

    @app.cls(secrets=[secret], min_containers=1, max_containers=1, timeout=24 * 60 * 60)
    class Transactor:
        """One always-on container running nitomic-transactor, restarted if
        it exits. PostgreSQL drops its claim the moment it goes, so peers
        never wait on a dead transactor."""

        @modal.enter()
        def start(self):
            self.stopping = False
            self.proc = None
            threading.Thread(target=self._supervise, daemon=True).start()

        def _supervise(self):
            while not self.stopping:
                try:
                    env = nitomic_env()
                except Exception as e:
                    print(f"nitomic-transactor: no database configured: {e}")
                    time.sleep(5)
                    continue
                # it exits when it loses its connection, and reconnects
                self.proc = subprocess.Popen(["/usr/local/bin/nitomic-transactor"], env=env)
                self.proc.wait()
                if not self.stopping:
                    print(f"nitomic-transactor exited with {self.proc.returncode}; restarting")
                    time.sleep(1)

        @modal.method()
        def status(self) -> str:
            alive = self.proc is not None and self.proc.poll() is None
            return "running" if alive else "restarting"

        @modal.exit()
        def stop(self):
            self.stopping = True
            if self.proc is not None and self.proc.poll() is None:
                self.proc.terminate()
                self.proc.wait(timeout=10)
