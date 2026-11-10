#!/usr/bin/env bash
# builds the submission for callgrind (generic CPU, debug info) in /b/vg; PGO=1 to train too
set -e
mkdir -p /b/vg && cd /repo/submissions/goose-iouring-davidhsv && cp -f *.goose *.c *.h *.sh /b/vg/ && cp -rf train /b/vg/
cd /b/vg && [ -d .deps ] || cp -r /b/mine/.deps .
PGO=${PGO:-0} EXTRA_CFLAGS="-g ${CG_ARCH:--mcpu=generic}" bash build.sh 2>&1 | grep -E "error|trained|warning: redundant" || true
