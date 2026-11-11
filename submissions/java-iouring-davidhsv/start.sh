#!/usr/bin/env bash
# Runs the server in the foreground, configured by SQLITE_PATH, JWT_SECRET, HOST and PORT.
# bin/server is the JDK's java launcher under another name. The request path is records and
# sealed types; C2 replaces most of them by their fields, and what is left (about 600 bytes a
# request) dies young: a 48 MB young generation with Serial (no collector threads) collects it
# in under a millisecond every two seconds at 33,600 req/s. The four methods named below take
# or return records on the hot path and are too large for C2 to inline by itself; inlined, the
# records they pass are scalar-replaced.
dir="$(cd "$(dirname "$0")" && pwd)"
exec "$dir/bin/server" \
  -XX:+UseSerialGC -Xms96m -Xmx96m -Xmn48m -Xss1m \
  -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=16m \
  -XX:-UsePerfData -XX:+DisableAttachMechanism \
  -XX:CompileCommand=quiet -XX:CompileCommand=inline,feed.JsonWriter::post -XX:CompileCommand=inline,feed.FeedRows::row \
  -XX:CompileCommand=inline,feed.ReadPlans::author -XX:CompileCommand=inline,feed.SqliteRecord::field \
  ${FEED_JVM_FLAGS:-} \
  --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Dfeed.lib="$dir/bin/libfeed.so" \
  -cp "$dir/bin/server.jar" feed.Main
