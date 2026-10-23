#!/usr/bin/env bash
# Builds a signed debug APK WITHOUT Gradle, using only: JDK, kotlinc, an android.jar, aapt2, dx, zipalign, apksigner.
# This is the pipeline that was actually run to produce and verify the APK in this repository's test environment
# (the sandbox had no access to Google's Maven repository, so Gradle/AGP could not be used there).
# For everyday development on your own machine, the Gradle build in README.md is the normal route.
#
# Required environment (defaults shown):
#   ANDROID_JAR   path to an android.jar for API 34   (e.g. $ANDROID_HOME/platforms/android-34/android.jar)
#   KOTLINC       kotlinc 2.0.x                        (default: kotlinc on PATH)
#   Tools on PATH: java, keytool, aapt2, dx (or dalvik-exchange), zipalign, apksigner, zip, unzip
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
: "${ANDROID_JAR:?Set ANDROID_JAR to an API 34 android.jar}"
KOTLINC="${KOTLINC:-kotlinc}"
DX="$(command -v dx || command -v dalvik-exchange)"
VERSION_NAME="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
PKG=app.plaintext.editor
B=build/apk
rm -rf "$B" && mkdir -p "$B"/{res,gen,classes,dexin}

echo "== 1/7 compile resources"
aapt2 compile --dir app/src/main/res -o "$B/res.zip"

echo "== 2/7 link resources + manifest (injects package, SDK levels, version)"
sed "s|<manifest |<manifest package=\"$PKG\" |" app/src/main/AndroidManifest.xml > "$B/AndroidManifest.xml"
aapt2 link -I "$ANDROID_JAR" --manifest "$B/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$B/gen" -o "$B/base.apk" "$B/res.zip"

echo "== 3/7 compile Kotlin (+ generated R.java)"
"$KOTLINC" -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xno-call-assertions -Xno-param-assertions -Xno-receiver-assertions \
  -no-reflect -cp "$ANDROID_JAR" \
  $(find app/src/main/java -name '*.kt') "$B/gen/app/plaintext/editor/R.java" -d "$B/classes" 2>&1 | grep -v '^warning: ' || true
test -f "$B/classes/app/plaintext/editor/ui/MainActivity.class" || { echo "Kotlin compile failed"; exit 1; }
# R.java is Java; compile it against the same classpath
javac -source 8 -target 8 -Xlint:-options -cp "$ANDROID_JAR" -d "$B/classes" "$B/gen/app/plaintext/editor/R.java"

echo "== 4/7 dex (app classes + Kotlin stdlib classes)"
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
(cd "$B/dexin" && unzip -q -o "$STDLIB" "kotlin/*" -x "*.kotlin_builtins" || true)
"$DX" --dex --min-sdk-version=26 --no-strict --output="$B/classes.dex" "$B/classes" "$B/dexin" 2>&1 | grep -v '^$' | head -20 || true
test -s "$B/classes.dex" || { echo "dex failed"; exit 1; }

echo "== 5/7 assemble APK"
cp "$B/base.apk" "$B/unaligned.apk"
(cd "$B" && zip -q -j unaligned.apk classes.dex)

echo "== 6/7 zipalign"
zipalign -f -p 4 "$B/unaligned.apk" "$B/aligned.apk"

echo "== 7/7 sign with a debug keystore"
KS="$ROOT/build/debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
  -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
mkdir -p build/outputs
OUT=build/outputs/plain-editor-debug.apk
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "$OUT" "$B/aligned.apk"
apksigner verify --verbose "$OUT" | head -8
echo "APK: $ROOT/$OUT ($(du -h "$OUT" | cut -f1))"
