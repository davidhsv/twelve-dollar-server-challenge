#!/usr/bin/env bash
# gccheck.sh dir: the one-ring load with a 4 MB young generation and GC logging; prints every
# collection with its time, so any allocation on the request path would show up as many of them.
d=${1:-/b/java}
FEED_JVM_FLAGS="-Xmn4m -Xlog:gc:file=/data/gc.log:uptime" LINES_OUT=2 bash /work/bench.sh $d 40000 8 15 30 2>&1 | grep -E "TOTAL|SERVER"
sleep 3
echo "collections: $(grep -c Pause /data/gc.log)"; grep Pause /data/gc.log | tail -5
