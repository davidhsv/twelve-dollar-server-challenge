#!/usr/bin/env bash
# ni-build.sh <graalvm home> <dst> [pgo]: the Java entry as a native image, with /b/java's libfeed.so
set -euo pipefail
G=$1; D=$2; PGO=${3:-0}
SRC=/repo/submissions/java-iouring-davidhsv
rm -rf $D && mkdir -p $D/bin && cp -r $SRC/train $D/ && cp /b/java/bin/libfeed.so $D/bin/
cat > $D/start.sh <<"EOS"
#!/usr/bin/env bash
dir="$(cd "$(dirname "$0")" && pwd)"
exec "$dir/bin/server" -Xmx64m ${FEED_JVM_FLAGS:-} -Dfeed.lib="$dir/bin/libfeed.so"
EOS
chmod +x $D/start.sh
cd $D && $G/bin/javac --release 25 -proc:none -nowarn -d cls $SRC/src/*.java
if [[ $(uname -m) == x86_64 ]]; then march=-march=x86-64-v3; else march=-march=native; fi
ni() { $G/bin/native-image -J-Xmx1g -O3 $march --gc=serial --install-exit-handlers -H:+UnlockExperimentalVMOptions \
  -H:+ForeignAPISupport -H:ConfigurationFileDirectories=/work/ni --initialize-at-build-time=N \
  --enable-native-access=ALL-UNNAMED -cp cls "$@" Server bin/server 2>&1 | grep -E "Finished|rror|Peak RSS" ; }
if [[ $PGO == pgo ]]; then
  ni --pgo-instrument
  python3 train/train.py ./start.sh && ls -la *.iprof
  ni --pgo=default.iprof
else
  ni
fi
ls -la bin/server
