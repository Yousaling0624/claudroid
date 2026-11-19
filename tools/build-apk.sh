#!/usr/bin/env bash
# Build the debug APK using whatever JDK/SDK the repo's setup script installed.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -d /c/dev-toolchain/jdk ]; then
  export JAVA_HOME='C:\dev-toolchain\jdk'
  export ANDROID_HOME='C:\dev-toolchain\sdk'
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

# Flutter is the app's only frontend, so every build needs its SDK too.
if ! grep -qs '^flutter\.sdk=' local.properties && [ -z "${FLUTTER_ROOT:-}" ]; then
  echo "Flutter SDK required: set flutter.sdk in local.properties or FLUTTER_ROOT" >&2
  echo "(install the SDK, then run: cd flutter && flutter pub get)" >&2
  exit 1
fi

# -Pandroid.newDsl=false is required by the Flutter Gradle plugin on AGP 9;
# it only takes effect on the command line, so append it here.
./gradlew "${@:-assembleDebug}" -Pandroid.newDsl=false
echo
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
ls -la app/build/outputs/apk/debug/*.apk 2>/dev/null || true
