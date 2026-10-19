#!/usr/bin/env bash
# Runs the JVM self-test: plist codec, pairing certificates and the full install flow against a simulated iPhone.
# Needs bcprov (BouncyCastle) as $BCPROV: the JDK's own X.509 parser rejects the empty subject/issuer names
# that lockdownd pairing certificates use (Android's Conscrypt parser accepts them).
set -euo pipefail
cd "$(dirname "$0")"
BCPROV="${BCPROV:-$PWD/tools/bin/bcprov.jar}"
OUT=app/build/test
rm -rf "$OUT" && mkdir -p "$OUT/classes"
find app/src/main/java app/src/test/java -name '*.java' ! -name 'MainActivity.java' ! -name 'AndroidUsbPipe.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 -cp "$BCPROV" -d "$OUT/classes" @"$OUT/sources.txt"
for lang in ru en; do
    java -Dstdout.encoding=UTF-8 -cp "$OUT/classes:$BCPROV" app.ipainstaller.SelfTest "$OUT/certs" "$lang"
done
if command -v openssl >/dev/null; then
    # issuer and subject are both empty (as in libimobiledevice), so `openssl verify` cannot build a chain;
    # just confirm OpenSSL parses the DER (signatures are verified by SelfTest).
    echo "openssl parse of generated pairing certificates:"
    for c in root host device; do openssl x509 -in "$OUT/certs/$c.pem" -noout -subject -issuer -dates -ext basicConstraints,keyUsage | tr '\n' ' '; echo " <- $c"; done
fi
