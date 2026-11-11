#!/usr/bin/env bash
# alloc.sh dir [extra jvm flags]: bytes allocated per request over a 30 s hold, and the top types
d=$1; shift
FEED_JVM_FLAGS="-Xms256m -Xmx256m -Xmn128m -Xlog:gc:file=/data/gc.log:uptime -XX:StartFlightRecording=delay=20s,duration=20s,filename=/data/x.jfr,settings=profile $*" LINES_OUT=2 bash /work/bench.sh $d 40000 8 15 30 2>&1 | grep -E "TOTAL|SERVER" | sed -E 's/(p99.9|file-reads).*//'
sleep 3
awk -F'[][s]' '/Pause Young/ {t=$2+0; if (t > 17 && t < 47) n++} END {printf "young collections in the hold: %d (~%.0f bytes/request at 33.6k req/s, eden 102 MB)\n", n, n*102e6/(30*33600)}' /data/gc.log
grep Pause /data/gc.log | tail -2
J=$(dirname $(readlink -f $d/bin/server))
$J/jfr print --events jdk.ObjectAllocationSample /data/x.jfr | grep -E "objectClass|weight" | paste - - | sed -E "s/.*objectClass = ([^ ]+).*weight = ([0-9.]+) ([kMG]?B).*/\1 \2 \3/" | awk '{w=$2; if ($3=="kB") w*=1e3; if ($3=="MB") w*=1e6; if ($3=="GB") w*=1e9; s[$1]+=w; t+=w} END {for (k in s) printf "%5.1f%% %s\n", 100*s[k]/t, k}' | sort -rn | head -${TOP:-8}
