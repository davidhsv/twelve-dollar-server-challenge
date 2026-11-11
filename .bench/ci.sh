#!/usr/bin/env bash
# Fork-only: runs inside an ubuntu:24.04 container on a GitHub runner (the host's kernel is what
# is being tested). Builds the servers and runs the comparisons named in $STEPS.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
echo "== kernel $(uname -r), $(nproc) cpus, $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2)"
for attempt in 1 2 3 4 5 6; do  # the runners' DNS sometimes fails for a while
  apt-get update -qq && apt-get install -y -qq sqlite3 jq curl openssl nodejs time valgrind procps iproute2 git util-linux >/dev/null && break
  echo "apt failed (attempt $attempt)"; grep -v '^#' /etc/resolv.conf || true
  # the resolver the container was given does not answer: ask Azure's and a public one directly
  printf 'nameserver 168.63.129.16\nnameserver 1.1.1.1\n' > /etc/resolv.conf
  sleep 10
done
command -v node >/dev/null || { echo "apt never succeeded"; exit 1; }
cd /repo
[ -f seed/feed.db ] || bash seed/make-seed.sh | tail -1
bash submissions/goose-iouring-davidhsv/install.sh > /tmp/install.log 2>&1 || { tail -20 /tmp/install.log; exit 1; }
bash test/run.sh submissions/goose-iouring-davidhsv 2>&1 | grep -E "trained|passed=|FAIL" || true
bash submissions/java-iouring-davidhsv/install.sh > /tmp/install-java.log 2>&1 || { tail -20 /tmp/install-java.log; exit 1; }
echo "== java test/run.sh"
bash test/run.sh submissions/java-iouring-davidhsv 2>&1 | grep -E "trained|passed=|FAIL|failed" || true
mkdir -p /b /data && ln -sfn /repo/.bench /work && cp seed/feed.db /data/feed.db
gcc -O2 -pthread -o /b/loadgen .bench/loadgen/loadgen.c
sysctl -w net.ipv4.ip_local_port_range="1024 65535" net.core.somaxconn=4096 >/dev/null
sysctl -w net.netfilter.nf_conntrack_max=4000000 2>/dev/null || true
cat /sys/kernel/mm/transparent_hugepage/enabled || true
bash .bench/sync.sh 2>&1 | grep -E "trained|error" || true
bash .bench/sync-java.sh 2>&1 | grep -E "trained|error|failed" || true
for v in ${JAVA_BUILDS:-}; do  # name=ENV=value,ENV=value: extra Java builds
  name=${v%%=*}; envs=${v#*=}
  echo "== build /b/$name ($envs)"
  env ${envs//,/ } DST=/b/$name bash .bench/sync-java.sh 2>&1 | grep -E "trained|error|failed" || true
done
for d in .bench/jv/*/; do  # source trees of variants, each built with its own install.sh and build.sh
  n=$(basename $d)
  echo "== build /b/$n"
  bash $d/install.sh > /tmp/install-$n.log 2>&1 || tail -5 /tmp/install-$n.log
  rm -rf /b/$n && cp -r $d /b/$n && (cd /b/$n && bash build.sh 2>&1 | grep -E "trained|error|openjdk version") || echo "build of $n failed"
  B=/b/$n bash .bench/t-java.sh | head -1
done
if [[ " ${SERVERS:-} ${VARIANTS:-} " == *"/b/ni"* ]]; then
  apt-get install -y -qq zlib1g-dev >/dev/null
  U=https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.5.5.0.3/graalvm-community-jdk-25i5-25.0.5.0.3_linux-x64_bin.tar.gz
  curl -fsSL -o /tmp/g.tgz $U && echo "c6438dee6c3ade2220d40b7ccdcad24b512d30e233e61bb4fc70519522de34ef  /tmp/g.tgz" | sha256sum -c -
  mkdir -p /opt/graalvm && tar -xzf /tmp/g.tgz -C /opt/graalvm --strip-components=1
  echo "== native image"; /usr/bin/time -v bash .bench/ni-build.sh /opt/graalvm /b/ni 2>&1 | grep -E "Finished|rror|Peak RSS|Maximum resident|Elapsed" || true
  B=/b/ni bash .bench/t-java.sh | head -2 || true
fi
if [[ " $STEPS " != *" solo "* || -n "${SERVERS:-}" ]]; then
  bash submissions/rust-iouring-kensac/install.sh > /dev/null 2>&1
  cp -r submissions/rust-iouring-kensac /b/kensac && (cd /b/kensac && bash build.sh 2>&1 | tail -1)
  cp -r submissions/goose-iouring-ahmadsh2007 /b/goose-ref && (cd /b/goose-ref && bash build.sh 2>&1 | tail -1)
fi
if [[ " $STEPS " == *" stohirov"* || " ${SERVERS:-} " == *stohirov* ]]; then
  bash .bench/ext/java-nio-stohirov/install.sh > /tmp/install-st.log 2>&1 || tail -5 /tmp/install-st.log
  cp -r .bench/ext/java-nio-stohirov /b/stohirov && (cd /b/stohirov && bash build.sh 2>&1 | tail -2) || echo "stohirov build failed"
fi
export LG_CPUS=1-3 LG_THREADS=3
ALL=${SERVERS:-/b/kensac /b/goose-ref /b/mine /b/java}
for step in $STEPS; do
  echo "== $step"
  case $step in
    one33)   bash .bench/cmp.sh 40000 8 15 30 ${ROUNDS:-2} $ALL ;;
    one54)   bash .bench/cmp.sh 40000 13 15 30 ${ROUNDS:-2} $ALL ;;
    one33w)  WARM=60 bash .bench/cmp.sh 40000 8 15 30 ${ROUNDS:-2} $ALL ;;
    five33)  NOFILE=8192 bash .bench/cmp.sh 40000 8 15 30 ${ROUNDS:-2} $ALL ;;
    five54)  NOFILE=8192 bash .bench/cmp.sh 40000 13 15 30 ${ROUNDS:-2} $ALL ;;
    real300) MEMINFO=1 bash .bench/cmp.sh 300000 1 45 45 1 $ALL ;;
    variants) MEMINFO=1 bash .bench/cmp.sh 300000 1 45 45 ${ROUNDS:-2} $VARIANTS ;;
    variants40) bash .bench/cmp.sh 40000 8 15 30 ${ROUNDS:-3} $VARIANTS ;;
    variants54) bash .bench/cmp.sh 40000 13 15 30 ${ROUNDS:-2} $VARIANTS ;;
    prof) bash .bench/prof.sh $PROF ;;
    gccheck) bash .bench/gccheck.sh /b/java ;;
    gcship) for s in $ALL; do echo "== gcship $s"; bash .bench/gcship.sh $s; done ;;
    alloc) for s in $ALL; do echo "== alloc $s"; bash .bench/alloc.sh $s; done ;;
    solo) ;;
  esac
done
