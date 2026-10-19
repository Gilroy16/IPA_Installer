#!/usr/bin/env bash
# Builds app/build/ipa-installer-debug.apk without Gradle/Android SDK Manager.
# Needs: JDK 17+, python3, and in $TOOLS: android.jar (API 33), aapt2, dx.jar (dalvik-dx 16.0.1), apksig.jar (2.3.0).
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="${TOOLS:-$PWD/tools/bin}"
ANDROID_JAR="$TOOLS/android33.jar"
B=app/build
rm -rf "$B" && mkdir -p "$B/classes" "$B/gen"

echo "== aapt2 compile/link"
"$TOOLS/aapt2" compile --dir app/src/main/res -o "$B/res.zip"
"$TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 33 --java "$B/gen" -o "$B/base.apk" "$B/res.zip"

echo "== javac"
find app/src/main/java "$B/gen" -name '*.java' > "$B/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
    -d "$B/classes" @"$B/sources.txt"

echo "== dx"
java -cp "$TOOLS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=26 --output="$B/classes.dex" "$B/classes"

echo "== package"
cp "$B/base.apk" "$B/unaligned.apk"
(cd "$B" && zip -q -j unaligned.apk classes.dex)
python3 tools/zipalign.py "$B/unaligned.apk" "$B/aligned.apk"

echo "== sign"
KS="${KEYSTORE:-$PWD/tools/debug.p12}"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass android -keypass android \
        -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=IPA Installer Debug" >/dev/null 2>&1
fi
mkdir -p "$B/signer"
javac -nowarn -cp "$TOOLS/apksig.jar" -d "$B/signer" tools/Sign.java
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED -cp "$TOOLS/apksig.jar:$B/signer" Sign "$B/aligned.apk" "$B/ipa-installer-debug.apk" "$KS" "${KEYSTORE_PASS:-android}" "${KEY_ALIAS:-debug}"
ls -la "$B/ipa-installer-debug.apk"
