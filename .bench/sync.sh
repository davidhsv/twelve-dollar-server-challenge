#!/usr/bin/env bash
# copies the submission's sources into the container-local build dir and builds there
set -e
mkdir -p /b/mine
cd /repo/submissions/goose-iouring-davidhsv
cp -f *.goose *.c *.h *.sh /b/mine/ 2>/dev/null
[ -d train ] && cp -rf train /b/mine/
cd /b/mine && bash build.sh "$@"
