"""The API's handling of a peer that stops answering, with a stand-in peer
(no nitomic or database needed):

    pytest deploy/modal/test_peer.py
"""

import sys
import threading
import time

from fastapi.testclient import TestClient

import api

# Answers :ping at once and hangs on anything else.
FAKE_PEER = r'''
import sys
buf = []
for line in sys.stdin:
    if line.rstrip("\n") != "%%end%%":
        buf.append(line)
        continue
    request, buf = "".join(buf), []
    if ":ping" in request:
        print("{:ok :pong}", flush=True)
    else:
        import time; time.sleep(3600)
'''


def fake_peer(tmp_path):
    script = tmp_path / "peer.py"
    script.write_text(FAKE_PEER)
    exe = tmp_path / "peer"
    exe.write_text(f"#!/bin/sh\nexec {sys.executable} {script}\n")
    exe.chmod(0o755)
    return str(exe)


def test_a_stuck_peer_times_out_and_is_replaced(tmp_path):
    peer = api.Peer(fake_peer(tmp_path), {}, timeout=1)
    c = TestClient(api.create_app(peer))
    t0 = time.time()
    r = c.post("/db/app/q", content="[:find ?e :where [?e :a]]")
    assert r.status_code == 504 and "didn't answer within 1 s" in r.text
    assert time.time() - t0 < 5
    # the stuck process is gone; the next request gets a fresh one
    assert c.get("/health").text == ":pong"


def test_a_stuck_call_does_not_block_other_requests(tmp_path):
    peer = api.Peer(fake_peer(tmp_path), {}, timeout=5)
    c = TestClient(api.create_app(peer))
    hung = threading.Thread(target=lambda: c.post("/db/app/q", content="[:find ?e :where [?e :a]]"))
    hung.start()
    time.sleep(0.5)                         # the query is now stuck in the peer
    t0 = time.time()
    r = c.get("/docs")                      # served by the event loop
    assert r.status_code == 200 and time.time() - t0 < 1
    hung.join()
