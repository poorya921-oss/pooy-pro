# Windows version of fetch-deps.sh (requires git and curl.exe, both bundled with current Windows 10/11).
# Usage: scripts/fetch-deps.ps1            fetch + verify against scripts/deps.lock
#        scripts/fetch-deps.ps1 -PrintSha256
param([switch]$PrintSha256)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

function Get-Lock($key) {
  $line = Get-Content scripts/deps.lock | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
  if (-not $line) { return "" }
  return (($line -replace "^$key=", "") -replace "\s*#.*$", "").Trim()
}
function Fail($msg) { Write-Error "[FAIL] $msg"; exit 1 }

$aar = "app/libs/libv2ray.aar"
$hev = "app/src/main/jni/hev-socks5-tunnel"
$aarUrl = Get-Lock "XRAY_AAR_URL"; $aarSha = Get-Lock "XRAY_AAR_SHA256"
$hevUrl = Get-Lock "HEV_URL"; $hevCommit = Get-Lock "HEV_COMMIT"
if ($hevCommit -notmatch "^[0-9a-f]{40}$") { Fail "HEV_COMMIT must be a full 40-character commit hash" }
if ($aarUrl -notmatch "^https://" -or $aarUrl -match "/latest/|/main/|/master/") { Fail "XRAY_AAR_URL must be a pinned https URL" }

if ($PrintSha256) {
  $tmp = New-TemporaryFile
  curl.exe --proto "=https" --tlsv1.2 -fL --retry 3 -o $tmp.FullName $aarUrl
  Write-Host "XRAY_AAR_SHA256=$((Get-FileHash $tmp.FullName -Algorithm SHA256).Hash.ToLower())"
  Write-Host "Verify independently before committing it to scripts/deps.lock."
  Remove-Item $tmp.FullName
  exit 0
}
if ($aarSha -notmatch "^[0-9a-fA-F]{64}$") { Fail "XRAY_AAR_SHA256 is not pinned in scripts/deps.lock. Run with -PrintSha256, verify, then commit it." }

New-Item -ItemType Directory -Force -Path "app/libs" | Out-Null
if (Test-Path $aar) {
  if ((Get-FileHash $aar -Algorithm SHA256).Hash -ne $aarSha.ToUpper()) { Fail "$aar does not match the pinned SHA-256" }
  Write-Host "[ok] $aar present, checksum verified"
} else {
  $tmp = New-TemporaryFile
  curl.exe --proto "=https" --tlsv1.2 -fL --retry 3 -o $tmp.FullName $aarUrl
  if ((Get-FileHash $tmp.FullName -Algorithm SHA256).Hash -ne $aarSha.ToUpper()) { Remove-Item $tmp.FullName; Fail "downloaded AAR checksum mismatch" }
  Move-Item $tmp.FullName $aar
  Write-Host "[ok] $aar downloaded, checksum verified"
}

function Test-Hev {
  if ((git -C $hev rev-parse HEAD).Trim() -ne $hevCommit) { return $false }
  if (git -C $hev submodule status --recursive | Select-String "^[+U-]") { return $false }
  if (git -C $hev status --porcelain) { return $false }
  return $true
}
if (Test-Path "$hev/Android.mk") {
  if (-not (Test-Hev)) { Fail "$hev is not exactly commit $hevCommit (clean, matching submodules). Delete it and re-run." }
  Write-Host "[ok] $hev present, commit verified"
} else {
  if (Test-Path $hev) { Remove-Item -Recurse -Force $hev }
  git clone --no-checkout $hevUrl $hev
  git -C $hev checkout --detach $hevCommit
  git -C $hev submodule update --init --recursive
  if (-not (Test-Hev)) { Fail "hev-socks5-tunnel verification failed after checkout" }
  Write-Host "[ok] $hev checked out and verified"
}
Write-Host "Done. Dependencies match scripts/deps.lock."
