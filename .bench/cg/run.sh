#!/usr/bin/env bash
# run.sh <name> <server-binary> [rounds] -- callgrind instruction counts for a fixed workload on a fresh seed copy
NAME=$1; BIN=$2; ROUNDS=${3:-150}; export USER=root LOGNAME=root
[ -f /data/cg/pid ] && kill -9 $(cat /data/cg/pid) 2>/dev/null; sleep 0.3
rm -rf /data/cg/feed.db* && mkdir -p /data/cg && cp /data/feed.db /data/cg/feed.db
export SQLITE_PATH=/data/cg/feed.db JWT_SECRET=twelve-dollar-challenge HOST=127.0.0.1 PORT=3222
rm -f /data/cg/$NAME.out*
valgrind --tool=callgrind --instr-atstart=no --callgrind-out-file=/data/cg/$NAME.out $BIN > /data/cg/log 2>&1 &
PID=$!; echo $PID > /data/cg/pid
for _ in $(seq 300); do curl -sf -m 2 http://127.0.0.1:3222/health >/dev/null && break; sleep 0.2; done
python3 /work/cg/drive.py 3222 3 64 ${PRIME:-prime} >/dev/null          # warm the caches
callgrind_control -i on $PID >/dev/null 2>&1
python3 /work/cg/drive.py 3222 $ROUNDS | tee /data/cg/served
callgrind_control -i off $PID >/dev/null 2>&1
callgrind_control -d $PID >/dev/null 2>&1
sleep 0.5
kill -9 $PID; sleep 0.3
OUT=$(ls -S /data/cg/$NAME.out* | head -1)
N=$(sed -E 's/served ([0-9]+).*/\1/' /data/cg/served)
TOTAL=$(callgrind_annotate $OUT 2>/dev/null | grep "PROGRAM TOTALS" | sed -E 's/[ ,]//g; s/\(.*//')
echo "$NAME: $TOTAL Ir / $N requests = $((TOTAL / N)) Ir/request"
cp $OUT /data/cg/$NAME.last
