#!/usr/bin/env bash
# bench.sh <dir-with-start.sh> <users> <scale> [ramp] [hold] -- runs inside the dev container
set -u
DIR=$1; USERS=$2; SCALE=$3; RAMP=${4:-20}; HOLD=${5:-30}
pkill -9 -x server 2>/dev/null; pkill -9 -x feed-server 2>/dev/null; pkill -9 -x java 2>/dev/null; sleep 0.3
RUN=${RUN_DIR:-/data/run}; rm -rf $RUN && mkdir -p $RUN && cp /data/feed.db $RUN/feed.db
export SQLITE_PATH=$RUN/feed.db JWT_SECRET=twelve-dollar-challenge HOST=127.0.0.1 PORT=3000
# the box gives LimitNOFILE=65535
# MEMCG=bytes: the server alone in a cgroup with that much memory (page cache and socket memory
# included), as on the box where the kernel's sockets take most of the 2 GB
if [ -n "${MEMCG:-}" ]; then
  C=/sys/fs/cgroup
  if [ ! -d $C/srv ]; then
    mkdir -p $C/init; for p in $(cat $C/cgroup.procs); do echo $p > $C/init/cgroup.procs 2>/dev/null; done
    echo +memory > $C/cgroup.subtree_control; mkdir -p $C/srv
  fi
  echo $MEMCG > $C/srv/memory.max; echo 0 > $C/srv/memory.swap.max 2>/dev/null
fi
( [ -n "${MEMCG:-}" ] && echo $BASHPID > /sys/fs/cgroup/srv/cgroup.procs; ulimit -n ${NOFILE:-65535}; exec taskset -c 0 bash "$DIR/start.sh" ) > $RUN/server.log 2>&1 &
for _ in $(seq 100); do curl -sf http://127.0.0.1:3000/health >/dev/null && break; sleep 0.1; done
PID=$( (pgrep -r R,S,D -x feed-server; pgrep -r R,S,D -x server; pgrep -r R,S,D -x java) | head -1)
# WARM=seconds: an unmeasured run of the same load first (the scored runs on the box follow a warm-up too)
if [ -n "${WARM:-}" ]; then LG_QUIET=1 taskset -c ${LG_CPUS:-2-4} /b/loadgen -H 127.0.0.1 -p 3000 -u "$USERS" -r "$RAMP" -d "$WARM" -s "$SCALE" -t ${LG_THREADS:-3} -T /repo/seed/tokens.json -P "$PID" > /dev/null 2>&1; sleep 2; fi
if [ -n "${PERF:-}" ]; then ( sleep $((RAMP + 6)); ${PERFBIN:-/usr/lib/linux-tools-6.8.0-149/perf} record -e cpu-clock -F 4999 -p $PID -o /data/perf.data -- sleep ${PERF} >/dev/null 2>&1 ) & fi
if [ -n "${MEMCG:-}" ]; then ( sleep $(awk "BEGIN{print int($RAMP + $HOLD * 0.7)}"); echo "MEMCG $(cat /sys/fs/cgroup/srv/memory.current) current; $(grep -E '^(anon|file|sock|slab) ' /sys/fs/cgroup/srv/memory.stat | tr '\n' ' ') events: $(tr '\n' ' ' < /sys/fs/cgroup/srv/memory.events)" > $RUN/memcg.txt ) & fi
if [ -n "${MEMINFO:-}" ]; then ( sleep $(awk "BEGIN{print int($RAMP + 5 / $SCALE + $HOLD * 0.7)}"); echo "KERNEL $(grep -E '^TCP:' /proc/net/sockstat) | $(awk '$1=="TCP"||$1=="sock_inode_cache"||$1=="filp"{printf "%s objs=%s size=%s; ", $1, $2, $4}' /proc/slabinfo 2>/dev/null) $(grep -E 'MemAvailable|Slab:' /proc/meminfo | tr -s ' \n' ' ') server: $(grep -E 'VmRSS|AnonHuge' /proc/$PID/status /proc/$PID/smaps_rollup 2>/dev/null | sed 's/.*://' | tr -s ' \t\n' ' ')" > $RUN/kernel.txt ) & fi
if [ -n "${LG_PROGRESS:-}" ]; then unset LG_QUIET; else export LG_QUIET=${LG_QUIET-1}; fi
taskset -c ${LG_CPUS:-2-4} /b/loadgen -H 127.0.0.1 -p 3000 -u "$USERS" -r "$RAMP" -d "$HOLD" -s "$SCALE" -t ${LG_THREADS:-3} -T /repo/seed/tokens.json -P "$PID" | tail -${LINES_OUT:-2}
[ -f $RUN/kernel.txt ] && cat $RUN/kernel.txt
[ -f $RUN/memcg.txt ] && cat $RUN/memcg.txt && echo "MEMCG peak $(cat /sys/fs/cgroup/srv/memory.peak 2>/dev/null) events: $(tr '\n' ' ' < /sys/fs/cgroup/srv/memory.events)"
grep -E "VmHWM|Threads" /proc/$PID/status | tr '\n' ' '; echo; ls -la $RUN | awk '/wal/{print "wal", $5}'
if [ -n "${SHOW_STATS:-}" ]; then grep -E "^stats:" $RUN/server.log | sed 's/^/STAT /'; fi
tail -3 $RUN/server.log
kill $PID 2>/dev/null
