#!/usr/bin/env bash
# Fetches the two external build inputs at the exact versions pinned in scripts/deps.lock and verifies them:
#   1) libv2ray.aar        pinned release URL + SHA-256 (build FAILS on mismatch or if no hash is pinned)
#   2) hev-socks5-tunnel   pinned full git commit (verified with rev-parse; submodules must match the gitlinks)
# Usage:  scripts/fetch-deps.sh                 fetch + verify
#         scripts/fetch-deps.sh --print-sha256  download the AAR to a temp file and print its SHA-256 (to pin it)
set -euo pipefail
cd "$(dirname "$0")/.."

LOCK=scripts/deps.lock
get() { grep -E "^$1=" "$LOCK" | head -n1 | cut -d= -f2- | sed 's/[[:space:]]*#.*$//; s/[[:space:]]*$//'; }
die() { echo "[FAIL] $*" >&2; exit 1; }

AAR=app/libs/libv2ray.aar
HEV=app/src/main/jni/hev-socks5-tunnel
AAR_URL=$(get XRAY_AAR_URL); AAR_SHA=$(get XRAY_AAR_SHA256)
HEV_URL=$(get HEV_URL); HEV_COMMIT=$(get HEV_COMMIT)

[ -n "$AAR_URL" ] && [ -n "$HEV_URL" ] || die "deps.lock is incomplete"
[[ "$HEV_COMMIT" =~ ^[0-9a-f]{40}$ ]] || die "HEV_COMMIT must be a full 40-character commit hash"
case "$AAR_URL" in https://*) ;; *) die "XRAY_AAR_URL must be https";; esac
if echo "$AAR_URL" | grep -Eq '/latest/|/main/|/master/'; then die "moving references are not allowed in XRAY_AAR_URL"; fi

sha256_of() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
download() { curl --proto '=https' --tlsv1.2 -fL --retry 3 -o "$1" "$AAR_URL"; }

if [ "${1:-}" = "--print-sha256" ]; then
  tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
  download "$tmp"
  echo "XRAY_AAR_SHA256=$(sha256_of "$tmp")"
  echo "Verify this value independently before committing it to scripts/deps.lock (a first download cannot prove itself)."
  exit 0
fi

[[ "$AAR_SHA" =~ ^[0-9a-fA-F]{64}$ ]] || die "XRAY_AAR_SHA256 is not pinned in scripts/deps.lock. Run: scripts/fetch-deps.sh --print-sha256, verify it, then commit it."

mkdir -p app/libs
if [ -f "$AAR" ]; then
  got=$(sha256_of "$AAR")
  [ "${got,,}" = "${AAR_SHA,,}" ] || die "$AAR does not match the pinned SHA-256 (expected $AAR_SHA, got $got). Delete it and re-run, or investigate."
  echo "[ok] $AAR present, checksum verified"
else
  tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
  echo "[..] downloading $AAR_URL"
  download "$tmp"
  got=$(sha256_of "$tmp")
  [ "${got,,}" = "${AAR_SHA,,}" ] || die "downloaded AAR checksum mismatch (expected $AAR_SHA, got $got)"
  mv "$tmp" "$AAR"; trap - EXIT
  echo "[ok] $AAR downloaded, checksum verified"
fi

verify_hev() {
  [ "$(git -C "$HEV" rev-parse HEAD)" = "$HEV_COMMIT" ] || return 1
  # '+' = submodule at another commit, '-' = not initialised, 'U' = conflicts
  if git -C "$HEV" submodule status --recursive | grep -Eq '^[+U-]'; then return 1; fi
  [ -z "$(git -C "$HEV" status --porcelain)" ] || return 1
}

if [ -f "$HEV/Android.mk" ]; then
  verify_hev || die "$HEV is not exactly commit $HEV_COMMIT with matching submodules and a clean tree. Delete it and re-run."
  echo "[ok] $HEV present, commit verified"
else
  echo "[..] cloning hev-socks5-tunnel @ $HEV_COMMIT"
  rm -rf "$HEV"
  git clone --no-checkout "$HEV_URL" "$HEV"
  git -C "$HEV" checkout --detach "$HEV_COMMIT"
  git -C "$HEV" submodule update --init --recursive
  verify_hev || die "hev-socks5-tunnel verification failed after checkout"
  echo "[ok] $HEV checked out and verified"
fi
echo "Done. Dependencies match scripts/deps.lock."
