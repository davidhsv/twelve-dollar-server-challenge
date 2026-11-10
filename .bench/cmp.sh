#!/usr/bin/env bash
# cmp.sh <users> <scale> <ramp> <hold> <rounds> dir[@VAR=value]...  -- interleaved rounds, one line per run
U=$1; S=$2; R=$3; H=$4; N=$5; shift 5
for i in $(seq $N); do
  for spec in "$@"; do
    d=${spec%%@*}; e=""; [ "$spec" != "$d" ] && e=${spec#*@}
    out=$(env $e LINES_OUT=3 /work/bench.sh $d $U $S $R $H 2>&1)
    t=$(echo "$out" | grep ^TOTAL | sed -E 's/.*rps=([0-9]+).*err=([0-9]+).*p50=([0-9.]+) p95=([0-9.]+) p99=([0-9.]+).*/rps=\1 err=\2 p50=\3 p95=\4 p99=\5/')
    c=$(echo "$out" | grep ^SERVER | sed -E 's/SERVER //')
    m=$(echo "$out" | grep -o "VmHWM:[^k]*kB" | tr -s '\t ' ' ')
    printf "%-26s %s | %s | %s\n" "$(basename $d)${e:+@$e}" "$t" "$c" "$m"
    echo "$out" | grep ^KERNEL | cut -c1-330
  done
done
