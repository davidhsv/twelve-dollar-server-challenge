#!/usr/bin/env bash
# runs the challenge's test suite against /b/mine on a fresh seed copy
pkill -9 -x server 2>/dev/null; sleep 0.2
rm -rf /data/t && mkdir -p /data/t && cp /data/feed.db /data/t/feed.db
export SQLITE_PATH=/data/t/feed.db JWT_SECRET=twelve-dollar-challenge HOST=127.0.0.1 PORT=3000
( ulimit -n 65535; exec bash /b/mine/start.sh ) > /data/t/server.log 2>&1 &
for _ in $(seq 50); do curl -sf http://127.0.0.1:3000/health >/dev/null && break; sleep 0.1; done
bash /repo/test/test.sh http://127.0.0.1:3000 > /data/t/test.out 2>&1; rc=$?
grep -E "FAIL|want|got|passed=" /data/t/test.out | head -${1:-40}
cat /data/t/server.log | head -20
pkill -x server
exit $rc
