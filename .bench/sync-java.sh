#!/usr/bin/env bash
# copies the Java submission's sources into the container-local build dir and builds there
set -e
dst=${DST:-/b/java}
mkdir -p $dst
cd /repo/submissions/java-iouring-davidhsv
rm -rf $dst/src $dst/train
cp -f *.c *.h *.sh $dst/
cp -rf src train $dst/
cd $dst && bash build.sh "$@"
