#!/usr/bin/env bash
# One-shot provisioning of an Android build toolchain into /c/dev-toolchain
set -u
ROOT=/c/dev-toolchain
cd "$ROOT" || exit 1
log(){ echo "[$(date +%H:%M:%S)] $*"; }

# 0. verify urls
for u in \
  "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" \
  "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" \
  "https://services.gradle.org/distributions/gradle-8.9-bin.zip" ; do
  code=$(curl -sSL -m 30 -o /dev/null -w "%{http_code}" "$u" 2>/dev/null)
  log "HEAD $code  $u"
done

# 1. JDK 17
if [ ! -x "$ROOT/jdk/bin/java.exe" ]; then
  log "downloading JDK 17 (Temurin)"
  curl -sSL --retry 3 -o jdk.zip "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" || { log "JDK download failed"; exit 1; }
  rm -rf jdk_tmp && mkdir -p jdk_tmp
  unzip -q jdk.zip -d jdk_tmp || { log "JDK unzip failed"; exit 1; }
  mv jdk_tmp/* jdk 2>/dev/null || mv "$(ls -d jdk_tmp/* | head -1)" jdk
  rm -rf jdk_tmp jdk.zip
fi
log "JDK: $("$ROOT/jdk/bin/java.exe" -version 2>&1 | head -1)"

# 2. Android cmdline tools + SDK
export ANDROID_HOME="$ROOT/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  log "downloading Android command-line tools"
  curl -sSL --retry 3 -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" || { log "cmdtools download failed"; exit 1; }
  rm -rf ct && mkdir -p ct "$ANDROID_HOME/cmdline-tools"
  unzip -q cmdtools.zip -d ct || { log "cmdtools unzip failed"; exit 1; }
  mv ct/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" || { log "cmdtools move failed"; exit 1; }
  rm -rf ct cmdtools.zip
fi
log "sdkmanager present"

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1
log "licenses accepted"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | tail -5
log "SDK packages installed"
ls "$ANDROID_HOME"

# 3. Gradle
if [ ! -x "$ROOT/gradle-8.9/bin/gradle" ]; then
  log "downloading Gradle 8.9"
  curl -sSL --retry 3 -o gradle.zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip" || { log "gradle download failed"; exit 1; }
  unzip -q gradle.zip || { log "gradle unzip failed"; exit 1; }
  rm -f gradle.zip
fi
log "Gradle ready: $ROOT/gradle-8.9/bin/gradle"
log "TOOLCHAIN_OK"
