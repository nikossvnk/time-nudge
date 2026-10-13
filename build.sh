#!/bin/sh
# Builds Time Nudge without Gradle: aapt + javac + dx + apksigner (Ubuntu android-sdk packages).
set -e
cd "$(dirname "$0")"
KS=${KEYSTORE:-keystore/timenudge.jks}
if [ ! -f "$KS" ]; then
  echo "Keystore not found: $KS" >&2
  echo "Set KEYSTORE=/path/to/timenudge.jks. A different key can't update the installed app." >&2
  exit 1
fi
SDK=/usr/lib/android-sdk
JAR=$SDK/platforms/android-23/android.jar
BT=$(ls -d $SDK/build-tools/* | tail -1)
rm -rf build && mkdir -p build/gen build/obj build/dex
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I $JAR -F build/unsigned.apk
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath $JAR -encoding UTF-8 -d build/obj \
  $(find src build/gen -name '*.java')
$BT/dx --dex --min-sdk-version=25 --output=build/dex/classes.dex build/obj
(cd build/dex && aapt add ../unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:timenudge --key-pass pass:timenudge --out build/TimeNudge.apk build/aligned.apk
apksigner verify build/TimeNudge.apk && ls -la build/TimeNudge.apk
