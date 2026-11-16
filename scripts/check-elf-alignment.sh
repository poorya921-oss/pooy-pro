#!/usr/bin/env bash
# Fails if any arm64-v8a / x86_64 native library in the given APK has ELF LOAD segments aligned below 16 KB
# (Google Play requires 16 KB page-size support for apps targeting Android 15+). Usage: check-elf-alignment.sh app.apk
set -euo pipefail
apk="${1:?usage: $0 <apk>}"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
unzip -q -o "$apk" 'lib/*' -d "$work" || { echo "no native libraries in $apk"; exit 0; }
bad=0
while IFS= read -r so; do
  case "$so" in */arm64-v8a/*|*/x86_64/*) ;; *) continue;; esac
  min=$(readelf -lW "$so" | awk '$1=="LOAD"{print $NF}' | sort | head -n1)
  if [ $((min)) -lt 16384 ]; then echo "[FAIL] $so LOAD alignment $min < 0x4000"; bad=1; else echo "[ok] $so ($min)"; fi
done < <(find "$work/lib" -name '*.so')
exit $bad
