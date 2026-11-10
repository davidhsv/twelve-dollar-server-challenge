#!/usr/bin/env python3
"""Deterministic workload for callgrind runs: drive.py <port> <rounds> [conns]"""
import json, random, sys, time
sys.path.insert(0, "/repo/submissions/goose-iouring-davidhsv/train")
import train
port, rounds = int(sys.argv[1]), int(sys.argv[2])
train.CONNECTIONS = int(sys.argv[3]) if len(sys.argv) > 3 else 64
train.ROUNDS = rounds
tokens = [t["token"] for t in json.load(open("/repo/seed/tokens.json"))[:2000]]
rnd = random.Random(5)
if len(sys.argv) > 4 and sys.argv[4] == "prime":
    # as in a load test: the newest 20 posts are new and have no likes yet
    c = train.Connection(port)
    for i in range(25):
        c.sock.sendall(train.request("POST", "/posts", "Bearer " + tokens[i], json.dumps({"body": "golden_ember_%d says hi at 2026-11-10T06:00:00.%03dZ (VU %d, iter %d)" % (i, i, 1000 + i, i % 7)}).encode()))
        c.response()
    c.sock.close()
# In a load test a post is liked ~10 s after it was in the feed, when ~2,400 newer posts have
# pushed it out: likes do not land on the feed's own 20 posts. Liking an older post keeps that.
_request = train.request
def request(method, path, auth=None, body=None):
    if path.endswith("/like"):
        path = "/posts/%d/like" % max(1, int(path.split("/")[2]) - 3000)
    return _request(method, path, auth, body)
train.request = request
t0 = time.time()
n = train.user_model(port, rnd, tokens)
print("served %d requests in %.1f s" % (n, time.time() - t0))
