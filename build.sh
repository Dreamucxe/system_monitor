#!/bin/bash
set -e
cd /root/app

SDK=/root/tools/sdk
TLIB=/root/tools/tlib:/data/data/com.termux/files/usr/lib
ANDROID_JAR=$SDK/android.jar
AAPT=$SDK/bin/aapt
D8=/root/tools/r8.jar
APKSIGNER=$SDK/lib/apksigner.jar
# NOTE: Termux libs are ONLY for the aapt (native) binary. Do NOT export globally,
# or Ubuntu's java/javac/python will try to load Termux's incompatible libc.
AAPT_ENV="LD_LIBRARY_PATH=$TLIB"

rm -rf build && mkdir -p build/classes build/dex build/gen
OUT=build/SystemMonitor

echo "== [0/6] aapt gen R.java =="
env $AAPT_ENV "$AAPT" package -f -m -J build/gen \
  -M AndroidManifest.xml -S res -I "$ANDROID_JAR" 2>&1 | head
echo "  R.java: $(find build/gen -name 'R.java')"

echo "== [1/6] javac =="
javac --release 17 -Xlint:none -cp "$ANDROID_JAR" \
  -d build/classes \
  src/com/monitor/sysmon/*.java $(find build/gen -name '*.java') 2>&1 | grep -v 'warning:' || true
echo "  classes: $(find build/classes -name '*.class' | wc -l)"

echo "== [2/6] d8 (dex) =="
java -cp "$D8" com.android.tools.r8.D8 \
  --release --min-api 24 --lib "$ANDROID_JAR" \
  --output build/dex \
  $(find build/classes -name '*.class') 2>&1 | tail -3
echo "  dex: $(ls -la build/dex/classes.dex | awk '{print $5}') bytes"

echo "== [3/6] aapt package (manifest + res + arsc, arsc uncompressed) =="
env $AAPT_ENV "$AAPT" package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  -F "$OUT.unaligned.apk" -0 arsc 2>&1 | head
# add classes.dex
cd build/dex && env $AAPT_ENV "$AAPT" add "../../$OUT.unaligned.apk" classes.dex 2>&1 | head; cd /root/app
echo "  contents:"; env $AAPT_ENV "$AAPT" list "$OUT.unaligned.apk" 2>/dev/null

echo "== [4/6] align (pure-python zipalign) =="
python3 /root/tools/zipalign.py "$OUT.unaligned.apk" "$OUT.aligned.apk"

echo "== [5/6] keystore =="
if [ ! -f build/debug.ks ]; then
  keytool -genkeypair -keystore build/debug.ks -alias mon -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=System Monitor, O=SysMon, C=US" 2>&1 | tail -1
fi

echo "== [6/6] apksigner (v1+v2+v3) =="
java -jar "$APKSIGNER" sign \
  --ks build/debug.ks --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT.apk" "$OUT.aligned.apk" 2>&1 | tail -3

echo "== verify =="
java -jar "$APKSIGNER" verify --verbose "$OUT.apk" 2>&1 | head -8
echo "== RESULT =="
ls -la "$OUT.apk"