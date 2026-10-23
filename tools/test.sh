#!/usr/bin/env bash
# Compiles the pure-Kotlin core + JVM unit tests with kotlinc and runs them with JUnit 4.
# Needs: JDK 17+, kotlinc (KOTLINC env var), JUnit 4 + Hamcrest jars (JUNIT_JAR / HAMCREST_JAR env vars).
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
JUNIT_JAR="${JUNIT_JAR:-/usr/share/java/junit4.jar}"
HAMCREST_JAR="${HAMCREST_JAR:-/usr/share/java/hamcrest-core.jar}"
OUT=build/jvm
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
"$KOTLINC" -jvm-target 1.8 -cp "$JUNIT_JAR:$HAMCREST_JAR" \
  app/src/main/java/app/plaintext/editor/core/*.kt app/src/test/java/app/plaintext/editor/core/*.kt \
  -d "$OUT/classes" 2>&1 | grep -v '^warning: ' || true
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
CLASSES=$(cd app/src/test/java && grep -l '@Test' app/plaintext/editor/core/*.kt | sed 's#/#.#g; s#\.kt$##' | tr '\n' ' ')
# Test classes declared in files with several classes are found by their own names:
EXTRA="app.plaintext.editor.core.TextSearchTest app.plaintext.editor.core.IndentToolsTest app.plaintext.editor.core.WindowingTest app.plaintext.editor.core.PdfLogicTest app.plaintext.editor.core.SessionStoreTest app.plaintext.editor.core.UndoHistoryBulkAndPersistTest"
CLASSES=$(echo "$CLASSES" | tr ' ' '\n' | grep -v -E 'SearchIndentWindowTest|^$' | tr '\n' ' ')
# shellcheck disable=SC2086
java -Xmx1500m -cp "$OUT/classes:$STDLIB:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore $CLASSES $EXTRA
