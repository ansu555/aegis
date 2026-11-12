#!/usr/bin/env bash
# Build the debug APK with a JDK 17 (Gradle 8.11 can't run on newer JDKs).
set -euo pipefail
cd "$(dirname "$0")"

is_jdk17() { [[ -n "$1" && -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | grep -q 'version "17\.'; }

if ! is_jdk17 "${JAVA_HOME:-}"; then
  JAVA_HOME=""
  for candidate in \
    "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/temurin-17-jdk-amd64; do
    if is_jdk17 "$candidate"; then JAVA_HOME="$candidate"; break; fi
  done
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JDK 17 not found. Install it (macOS: brew install openjdk@17) or point JAVA_HOME at one." >&2
  exit 1
fi
export JAVA_HOME
echo "Using JAVA_HOME=$JAVA_HOME"
./gradlew :app:assembleDebug
echo "APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
