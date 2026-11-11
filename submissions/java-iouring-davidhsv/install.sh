#!/usr/bin/env bash
# Runs once as root on a clean Ubuntu 24.04: the C toolchain, python3 (build.sh's profile
# training), and OpenJDK 28 early-access build 23 from jdk.java.net (for JEP 401 value
# classes, a preview feature), sha256-checked against the published checksums, unpacked into
# /opt/jdk-28-ea23. An experiment: early-access builds expire and their URLs may disappear.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends build-essential curl ca-certificates unzip python3

BASE=https://download.java.net/java/early_access/jdk28/23/GPL
case "$(uname -m)" in
  x86_64)  ARCH=x64;     SHA256=8a8723ee41ada67f996bbe8292fac4e2865803017832ca4a677c80dc13a97eb7 ;;
  aarch64) ARCH=aarch64; SHA256=b739d2c42e55eaec14e7cf2efb861d165be04b680a61a0dba38878f20673a7cf ;;
  *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
prefix=/opt/jdk-28-ea23
if [[ ! -x $prefix/bin/javac ]]; then
  archive=$(mktemp)
  trap 'rm -f "$archive"' EXIT
  curl -fsSL --retry 3 -o "$archive" "$BASE/openjdk-28-ea+23_linux-${ARCH}_bin.tar.gz"
  echo "$SHA256  $archive" | sha256sum -c -
  rm -rf "$prefix"
  mkdir -p "$prefix"
  tar -xzf "$archive" --strip-components=1 -C "$prefix"
fi
"$prefix/bin/java" -version
