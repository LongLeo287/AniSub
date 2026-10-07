# Downloads the pinned sherpa-onnx Android native libraries, verifies the archive and every
# extracted .so against native-artifacts.json, and installs them into native/jniLibs
# (Git-ignored). Run once before building:  powershell -File tools/fetch-native.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$spec = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'native-artifacts.json') | ConvertFrom-Json
$work = Join-Path $root 'build/native-download'
$out = Join-Path $root 'native/jniLibs'
New-Item -ItemType Directory -Force $work | Out-Null
$archive = Join-Path $work ([IO.Path]::GetFileName($spec.archive.url))
function Hash($p) { (Get-FileHash -Algorithm SHA256 $p).Hash.ToLowerInvariant() }
if (-not (Test-Path $archive) -or (Hash $archive) -ne $spec.archive.sha256) {
    Write-Host "Downloading $($spec.archive.url)"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri $spec.archive.url -OutFile $archive
}
if ((Get-Item $archive).Length -ne $spec.archive.bytes -or (Hash $archive) -ne $spec.archive.sha256) { throw 'Archive SHA-256 mismatch: refusing to use it.' }
$extract = Join-Path $work 'x'
if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
New-Item -ItemType Directory -Force $extract | Out-Null
& "$env:SystemRoot\System32\tar.exe" -xjf $archive -C $extract
if ($LASTEXITCODE -ne 0) { throw 'tar extraction failed' }
foreach ($lib in $spec.libraries) {
    $src = Join-Path $extract ("jniLibs/{0}/{1}" -f $lib.abi, $lib.file)
    if (-not (Test-Path $src)) { throw "Missing $($lib.abi)/$($lib.file) in archive" }
    if ((Get-Item $src).Length -ne $lib.bytes -or (Hash $src) -ne $lib.sha256) { throw "SHA-256 mismatch for $($lib.abi)/$($lib.file)" }
    $dir = Join-Path $out $lib.abi
    New-Item -ItemType Directory -Force $dir | Out-Null
    Copy-Item -Force $src (Join-Path $dir $lib.file)
}
Remove-Item -Recurse -Force $extract
Write-Host "Native libraries verified and installed into $out"
