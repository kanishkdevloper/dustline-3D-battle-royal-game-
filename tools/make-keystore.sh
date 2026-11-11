#!/usr/bin/env sh
# Creates your own upload key (dustline-upload.jks) + keystore.properties. Needs a JDK (keytool).
set -e
cd "$(dirname "$0")/.."
OUT=dustline-upload.jks
if [ -f "$OUT" ]; then echo "$OUT already exists - not overwriting it."; exit 1; fi
command -v keytool >/dev/null 2>&1 || { echo "keytool not found - install JDK 17 (Android Studio ships one)."; exit 1; }
printf "Choose a keystore password (min 6 characters): "
stty -echo; read -r PW; stty echo; echo
[ "${#PW}" -ge 6 ] || { echo "Password too short."; exit 1; }
keytool -genkeypair -v -keystore "$OUT" -alias dustline -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PW" -keypass "$PW" -dname "CN=Dustline, O=Dustline"
cat > keystore.properties <<EOP
storeFile=$OUT
storePassword=$PW
keyAlias=dustline
keyPassword=$PW
EOP
echo
echo "Created $OUT and keystore.properties."
echo "BACK THEM UP somewhere safe (if you lose the key you must ask Google to reset it) and never upload them to GitHub."
