#!/usr/bin/env sh
# Builds the signed release APK + AAB and copies them to ./dist  (needs Gradle 8.13+ and JDK 17+, or run it from Android Studio instead).
set -e
cd "$(dirname "$0")/.."
if [ -x ./gradlew ]; then G=./gradlew
elif command -v gradle >/dev/null 2>&1; then G=gradle
else echo "Gradle not found. Install Gradle 8.13+ (https://gradle.org/install) or open this folder in Android Studio."; exit 1; fi
if [ ! -f keystore.properties ] && [ -z "$DUSTLINE_KEYSTORE_FILE" ]; then
  echo "No keystore.properties - run tools/make-keystore.sh first."; exit 1; fi
$G assembleRelease bundleRelease
mkdir -p dist
cp app/build/outputs/apk/release/*.apk dist/
cp app/build/outputs/bundle/release/*.aab dist/
echo; echo "Done:"; ls -la dist
