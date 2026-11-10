#!/usr/bin/env bash
# prof.sh dir...: a perf profile of each server under the one-ring load, with the JVM's perf map
apt-get install -y -qq linux-tools-generic linux-tools-common >/dev/null 2>&1 || true
P=$(ls /usr/lib/linux-tools/*/perf 2>/dev/null | head -1)
echo "perf: $P"
for spec in "$@"; do
  d=${spec%%@*}; e=""; [ "$spec" != "$d" ] && e=${spec#*@}; e=${e//\~/ }
  rm -f /tmp/perf-*.map /data/perf.data
  echo "== prof $spec"
  env ${e:+"$e"} PERF=20 PERFBIN=$P LINES_OUT=2 bash /work/bench.sh $d 40000 8 15 30 2>&1 | grep -E "TOTAL|SERVER"
  sleep 4
  $P report -i /data/perf.data --no-children --sort dso -q 2>/dev/null | head -8
  $P report -i /data/perf.data --no-children --sort dso,sym -q 2>/dev/null | grep -v '\[k\]' | head -70
  $P report -i /data/perf.data --no-children --sort sym -q 2>/dev/null | grep '\[k\]' | head -12
  grep -cE "^ *[0-9]+ " /data/run/server.log | sed 's/^/compile log lines: /'
  awk '$1 ~ /^[0-9]+$/ && $1 > 16000' /data/run/server.log | head -30
done
