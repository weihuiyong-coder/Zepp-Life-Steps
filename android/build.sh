#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then echo 'Set ANDROID_HOME to an Android SDK directory.' >&2; exit 1; fi
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
if [[ ! -x "$BT/aapt2" || ! -f "$ANDROID_JAR" ]]; then
  MANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
  "$MANAGER" 'platforms;android-35' 'build-tools;35.0.0'
fi
BUILD=android/build
OUT=android/out
rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD"/{gen,classes,dex,testclasses} "$OUT"
node android/prepare-assets.cjs
# JSON is needed only for JVM fixture tests; the APK uses Android's own org.json.
curl --fail --location --retry 2 --max-time 90 --silent --show-error \
  https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$BUILD/json.jar"
javac --release 8 -encoding UTF-8 -cp "$BUILD/json.jar" -d "$BUILD/testclasses" \
  android/src/com/weihuiyong/zeppsteps/ZeppClient.java android/tests/com/weihuiyong/zeppsteps/ClientTests.java
java -cp "$BUILD/testclasses:$BUILD/json.jar" com.weihuiyong.zeppsteps.ClientTests android/assets/step-template.json | tee "$OUT/tests.txt"
"$BT/aapt2" compile --dir android/res -o "$BUILD/resources.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest android/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 35 --version-code 10000 --version-name 1.0.0 \
  --java "$BUILD/gen" -A android/assets -o "$BUILD/resources.apk" "$BUILD/resources.zip"
find android/src "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/resources.apk" "$BUILD/unsigned.apk"
zip -q -j "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex"
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$OUT/ZeppSteps-1.0.0-unsigned.apk"
"$BT/zipalign" -c -v 4 "$OUT/ZeppSteps-1.0.0-unsigned.apk" > "$OUT/zipalign.txt"
"$BT/aapt" dump badging "$OUT/ZeppSteps-1.0.0-unsigned.apk" > "$OUT/package-info.txt"
# Signing is performed after download; no signing private key is uploaded to this public repository or Actions.
cp "$BT/lib/apksigner.jar" "$OUT/apksigner.jar"
zip -q -r "$OUT/ZeppSteps-Android-Source.zip" android/AndroidManifest.xml android/src android/res android/assets android/tests android/build.sh android/prepare-assets.cjs android/README.md .github/workflows/android-apk.yml LICENSE lib/step-client.js server/python/vendor
{
  echo 'Android native build 1.0.0';
  echo "Source commit: $(git rev-parse HEAD)";
  echo 'minSdk=26; targetSdk=35; no native ABI restrictions; no runtime dependencies';
  echo 'Fixture tests passed; no live Zepp/WeChat account test has been performed.';
  echo 'Unsigned APK must be signed before installation.';
  sha256sum "$OUT/ZeppSteps-1.0.0-unsigned.apk";
} > "$OUT/build-report.txt"
echo 'Build complete.'
