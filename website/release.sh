#!/usr/bin/env bash
# Copy an APK into website/downloads/aegis.apk and write its version, size
# and SHA-256 into index.html. Usage: website/release.sh [path/to/app.apk]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
apk="${1:-$here/../android/app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$apk" ] || { echo "no APK at $apk (run android/build-apk.sh first)"; exit 1; }
mkdir -p "$here/downloads"
cp "$apk" "$here/downloads/aegis.apk"
sha="$(shasum -a 256 "$here/downloads/aegis.apk" | cut -d' ' -f1)"
size="$(( $(wc -c < "$here/downloads/aegis.apk") / 1048576 )) MB"
version="$(grep -m1 'versionName' "$here/../android/app/build.gradle.kts" | sed -E 's/.*"(.*)".*/\1/')"
SHA="$sha" SIZE="$size" VERSION="$version" perl -0pi -e '
  s/(<!--apk-sha-->).*?(<!--\/apk-sha-->)/$1$ENV{SHA}$2/s;
  s/(<!--apk-size-->).*?(<!--\/apk-size-->)/$1$ENV{SIZE}$2/s;
  s/(<!--apk-version-->).*?(<!--\/apk-version-->)/$1$ENV{VERSION}$2/s;
' "$here/index.html"
echo "website/downloads/aegis.apk  v$version  $size  sha256 $sha"
