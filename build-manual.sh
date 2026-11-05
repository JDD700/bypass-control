#!/bin/bash
# Manual build pipeline for Bypass Control. The Gradle daemon is unreliable
# in some environments, so this script mirrors what AGP does by hand:
# javac -> d8 -> aapt2 link -> zipalign -> apksigner.
#
# Requirements (or drop them under tools/ with the same layout):
#   JDK 17+                 -> $JAVA_HOME, else ./tools/jdk
#   Android SDK platform 34 + build-tools 34.0.0
#                           -> $ANDROID_SDK_ROOT / $ANDROID_HOME, else ./tools/android-sdk
# The Shizuku libraries (API/provider/AIDL 12.2.0) are fetched from Maven
# Central into tools/shizuku/ on first build if missing.
# Signing: uses ./debug.keystore if present, otherwise generates one.
# A generated key is fine for local builds; official releases are signed
# with the maintainer's private key, which is never in this repo.
set -e
cd "$(dirname "$0")"

JDK=${JAVA_HOME:-$PWD/tools/jdk}
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$PWD/tools/android-sdk}}
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=$JDK
export PATH=$JDK/bin:$PATH
[ -x "$JDK/bin/javac" ] || { echo "JDK not found at $JDK - set JAVA_HOME"; exit 1; }
[ -f "$AJAR" ] || { echo "Android platform 34 not found at $SDK - set ANDROID_SDK_ROOT"; exit 1; }

# --- fetch Shizuku libraries if needed ---
SZ=$PWD/tools/shizuku
if [ ! -f "$SZ/shizuku-api.jar" ] || [ ! -f "$SZ/shizuku-provider.jar" ] || [ ! -f "$SZ/shizuku-aidl.jar" ]; then
  echo "Fetching Shizuku 12.2.0 libraries from Maven Central..."
  mkdir -p "$SZ/dl"
  for art in api provider aidl; do
    curl -sL -o "$SZ/dl/$art.aar" "https://repo1.maven.org/maven2/dev/rikka/shizuku/$art/12.2.0/$art-12.2.0.aar"
    unzip -o -q "$SZ/dl/$art.aar" classes.jar -d "$SZ/dl/$art-x"
    cp "$SZ/dl/$art-x/classes.jar" "$SZ/shizuku-$art.jar"
  done
fi
SZJARS=$SZ/shizuku-api.jar:$SZ/shizuku-provider.jar:$SZ/shizuku-aidl.jar

rm -rf build/manual
mkdir -p build/manual/classes build/manual/dex
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
  -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"

javac -encoding UTF-8 -cp "$AJAR:$SZJARS" -d build/manual/classes app/src/main/java/com/pooh/passcontrol/*.java
jar cf build/manual/classes.jar -C build/manual/classes .
"$BT/d8" --release --min-api 22 --lib "$AJAR" --output build/manual/dex build/manual/classes.jar \
  $SZ/shizuku-api.jar $SZ/shizuku-provider.jar $SZ/shizuku-aidl.jar

# aapt2 link needs a package attribute; Gradle normally injects it from `namespace`.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.pooh.passcontrol">|' \
  app/src/main/AndroidManifest.xml > build/manual/AndroidManifest.xml
"$BT/aapt2" compile --dir app/src/main/res -o build/manual/res.zip
"$BT/aapt2" link -o build/manual/unsigned.apk -I "$AJAR" --manifest build/manual/AndroidManifest.xml \
  build/manual/res.zip \
  --min-sdk-version 22 --target-sdk-version 22 --version-code 10 --version-name 1.9

(cd build/manual/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/manual/unsigned.apk build/manual/aligned.apk
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey \
  --key-pass pass:android --out BypassControl-v10.apk build/manual/aligned.apk
"$BT/apksigner" verify BypassControl-v10.apk
echo "Built: $PWD/BypassControl-v10.apk"
