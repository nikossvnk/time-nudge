#!/bin/sh
# Builds Time Nudge without Gradle: aapt + javac + dx + apksigner (Ubuntu android-sdk packages),
# plus two platform android.jar files from Google's SDK repository (downloaded once, checksum-checked).
set -e
cd "$(dirname "$0")"
KS=${KEYSTORE:-keystore/timenudge.jks}
if [ ! -f "$KS" ]; then
  echo "Keystore not found: $KS" >&2
  echo "Set KEYSTORE=/path/to/timenudge.jks. A different key can't update the installed app." >&2
  exit 1
fi

# Downloads platform-$1.zip into platforms/ unless it's there, checks its SHA-256 ($2) and prints
# the path of its android.jar.
platform() {
  dir=platforms/$1
  if [ ! -f "$dir/android.jar" ]; then
    mkdir -p "$dir"
    curl -fsSL -o "$dir.zip" "https://dl.google.com/android/repository/platform-$1.zip"
    echo "$2  $dir.zip" | sha256sum -c --quiet
    unzip -q -j -o "$dir.zip" '*/android.jar' -d "$dir"
    rm "$dir.zip"
  fi
  echo "$dir/android.jar"
}
# The code compiles against Android 17 (API 37), the version the app targets. Ubuntu's aapt can't
# read resource tables newer than Android 14 (API 34), so resources link against that one; they
# only use attributes that exist there.
JAR=${ANDROID_JAR:-$(platform 37.0_r02 840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d)}
RES_JAR=${RES_JAR:-$(platform 34-ext7_r03 16fdb74c55e59ae3ef52def135aec713508467bd56d7dabcd8c9be31fa8b20f3)}

SDK=/usr/lib/android-sdk
BT=$(ls -d $SDK/build-tools/* | tail -1)
rm -rf build && mkdir -p build/gen build/obj build/dex
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$RES_JAR" -F build/unsigned.apk
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -encoding UTF-8 -d build/obj \
  $(find src build/gen -name '*.java')
$BT/dx --dex --min-sdk-version=25 --output=build/dex/classes.dex build/obj
(cd build/dex && aapt add ../unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:timenudge --key-pass pass:timenudge --out build/TimeNudge.apk build/aligned.apk
apksigner verify build/TimeNudge.apk && ls -la build/TimeNudge.apk
