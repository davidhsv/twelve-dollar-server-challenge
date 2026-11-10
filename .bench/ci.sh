#!/usr/bin/env bash
# Fork-only: runs inside an ubuntu:24.04 container on a GitHub runner (the host's kernel is what
# is being tested). Builds the three servers and runs the comparisons named in $STEPS.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
echo "== kernel $(uname -r), $(nproc) cpus, $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2)"
apt-get update -qq && apt-get install -y -qq sqlite3 jq curl openssl nodejs time valgrind procps iproute2 git util-linux >/dev/null
cd /repo
[ -f seed/feed.db ] || bash seed/make-seed.sh | tail -1
bash submissions/goose-iouring-davidhsv/install.sh > /tmp/install.log 2>&1 || { tail -20 /tmp/install.log; exit 1; }
bash test/run.sh submissions/goose-iouring-davidhsv 2>&1 | grep -E "trained|passed=|FAIL" || true
mkdir -p /b /data && ln -sfn /repo/.bench /work && cp seed/feed.db /data/feed.db
gcc -O2 -pthread -o /b/loadgen .bench/loadgen/loadgen.c
sysctl -w net.ipv4.ip_local_port_range="1024 65535" net.core.somaxconn=4096 >/dev/null
sysctl -w net.netfilter.nf_conntrack_max=4000000 2>/dev/null || true
cat /sys/kernel/mm/transparent_hugepage/enabled || true
bash .bench/sync.sh 2>&1 | grep -E "trained|error" || true
if [[ " $STEPS " != *" solo "* ]]; then
  bash submissions/rust-iouring-kensac/install.sh > /dev/null 2>&1
  cp -r submissions/rust-iouring-kensac /b/kensac && (cd /b/kensac && bash build.sh 2>&1 | tail -1)
  cp -r submissions/goose-iouring-ahmadsh2007 /b/goose-ref && (cd /b/goose-ref && bash build.sh 2>&1 | tail -1)
fi
export LG_CPUS=1-3 LG_THREADS=3
for step in $STEPS; do
  echo "== $step"
  case $step in
    one33)   bash .bench/cmp.sh 40000 8 15 30 2 /b/kensac /b/goose-ref /b/mine ;;
    five33)  NOFILE=8192 bash .bench/cmp.sh 40000 8 15 30 2 /b/kensac /b/goose-ref /b/mine ;;
    five54)  NOFILE=8192 bash .bench/cmp.sh 40000 13 15 30 2 /b/kensac /b/goose-ref /b/mine ;;
    real300) MEMINFO=1 bash .bench/cmp.sh 300000 1 45 45 1 /b/kensac /b/goose-ref /b/mine ;;
    real400) MEMINFO=1 bash .bench/cmp.sh 400000 1 50 45 1 /b/kensac /b/mine ;;
    variants) MEMINFO=1 bash .bench/cmp.sh 300000 1 45 45 ${ROUNDS:-2} $VARIANTS ;;
    variants40) bash .bench/cmp.sh 40000 8 15 30 ${ROUNDS:-3} $VARIANTS ;;
    solo) ;;
  esac
done
