#!/usr/bin/env bash
# gcship.sh dir: the one-ring load with the flags start.sh ships (only GC logging added); prints
# the young collections inside the hold, their pauses, and bytes allocated per request worked out
# from the log itself (heap before a collection minus heap after the one before it).
d=$1
FEED_JVM_FLAGS="-Xlog:gc:file=/data/gc.log:uptime" LINES_OUT=2 bash /work/bench.sh $d 40000 8 15 30 2>&1 | grep -E "TOTAL|SERVER" | sed -E 's/(p99.9|file-reads).*//'
sleep 3
grep Pause /data/gc.log | sed -E 's/^\[([0-9.]+)s\].*Pause ([A-Za-z]+) .* ([0-9]+)M->([0-9]+)M\(([0-9]+)M\) ([0-9.]+)ms/\1 \2 \3 \4 \5 \6/' | awk '
  { t=$1; if (t > 17 && t < 47) { if (have) { mb += $3 - prev; span = t - t0 } else { t0 = t; have = 1 }
      n++; s += $6; if ($6 > mx) mx = $6; if ($2 != "Young") full++ }
    if (t <= 17) startup++
    prev = $4 }
  END { printf "collections: %d before the hold, %d in it (%d not young); pause mean %.3f ms, max %.3f ms\n", startup, n, full, n ? s/n : 0, mx
        if (span > 0) printf "allocated: %d MB in %.1f s = %.2f MB/s = ~%.0f bytes/request at 33.6k req/s\n", mb, span, mb/span, mb*1048576/span/33600
        else print "allocated: fewer than two collections in the hold, no rate" }'
grep Pause /data/gc.log | tail -4
