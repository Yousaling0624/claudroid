#!/usr/bin/env bash
# Build the debug APK using whatever JDK/SDK the repo's setup script installed.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -d /c/dev-toolchain/jdk ]; then
  export JAVA_HOME='C:\dev-toolchain\jdk'
  export ANDROID_HOME='C:\dev-toolchain\sdk'
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

./gradlew "${@:-assembleDebug}"
echo
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
ls -la app/build/outputs/apk/debug/*.apk 2>/dev/null || true
