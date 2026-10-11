#!/bin/sh
# Runs the plain-JVM unit tests (no Android needed).
set -e
cd "$(dirname "$0")/.."
OUT=build/test-classes; rm -rf $OUT; mkdir -p $OUT
J=${JARVIS_JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
$J/bin/javac -encoding UTF-8 -d $OUT shared/src/de/felix/jarvis/shared/SchoolParser.java shared/src/de/felix/jarvis/shared/BellTimes.java tests/src/*.java
for t in tests/src/*Test.java; do $J/bin/java -cp $OUT "$(basename "$t" .java)"; done
