#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:?Imposta ANDROID_SDK_ROOT}"
tools="${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
api="$sdk/platforms/android-35/android.jar"
mkdir -p classes dex generated dist
"$tools/aapt2" compile --dir res -o resources.zip
"$tools/aapt2" link -o unsigned.apk --manifest AndroidManifest.xml -I "$api" --min-sdk-version 26 --target-sdk-version 35 --version-code 5 --version-name 0.5 --java generated resources.zip
javac --release 8 -encoding UTF-8 -classpath "$api" -d classes Setup.java Monitor.java generated/it/bose/overlay/R.java
jar cf classes.jar -C classes .
"$tools/d8" --lib "$api" --min-api 26 --output dex classes.jar
zip -j unsigned.apk dex/classes.dex
"$tools/zipalign" -f 4 unsigned.apk aligned.apk
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Bose Overlay Debug' -keyalg RSA -keysize 2048 -validity 10000
fi
"$tools/apksigner" sign --ks debug.keystore --ks-pass pass:android --out dist/Bose-Overlay.apk aligned.apk
"$tools/apksigner" verify dist/Bose-Overlay.apk
