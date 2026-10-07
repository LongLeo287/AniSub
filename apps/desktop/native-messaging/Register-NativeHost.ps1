param([Parameter(Mandatory=$true)][ValidatePattern('^[a-p]{32}$')][string]$ExtensionId,
      [Parameter(Mandatory=$true)][string]$PythonPath)
$ErrorActionPreference = 'Stop'
$nativeFolder = $PSScriptRoot
$pythonResolved = (Resolve-Path -LiteralPath $PythonPath).Path
if (-not (Test-Path -LiteralPath $pythonResolved -PathType Leaf)) { throw 'Python executable missing.' }
$registration = Join-Path $nativeFolder 'registration'
if ((Test-Path -LiteralPath $registration) -and ((Get-Item -LiteralPath $registration).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { throw 'Registration directory cannot be a junction or symbolic link.' }
$manifestPath = Join-Path $registration 'com.anisub.desktop.json'
$registryPath = 'HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.anisub.desktop'
if (Test-Path -LiteralPath $registryPath) {
    $existing = (Get-Item -LiteralPath $registryPath).GetValue('')
    if ($existing -ne $manifestPath) { throw 'A different AniSub host registration exists. Not overwritten.' }
}
New-Item -ItemType Directory -Path $registration -Force | Out-Null
$exePath = Join-Path $registration 'AniSubNativeHost.exe'
if (Test-Path -LiteralPath $exePath) { throw 'Launcher already exists. Run Unregister-NativeHost.ps1 before re-registering.' }
$cscPath = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $cscPath)) { throw '.NET Framework compiler unavailable.' }
& $cscPath /nologo /target:exe ('/out:' + $exePath) (Join-Path $nativeFolder 'NativeLauncher.cs')
if ($LASTEXITCODE -ne 0) { throw 'Native launcher compilation failed.' }
$utf8 = New-Object System.Text.UTF8Encoding($false)
$allowlist = @{ allowed_origins = @('chrome-extension://' + $ExtensionId + '/') }
[System.IO.File]::WriteAllText((Join-Path $registration 'python-path.txt'), $pythonResolved, $utf8)
[System.IO.File]::WriteAllText((Join-Path $registration 'allowlist.json'), ($allowlist | ConvertTo-Json -Depth 4), $utf8)
$manifest = @{name='com.anisub.desktop';description='AniSub local subtitle bridge';path=$exePath;type='stdio';allowed_origins=$allowlist.allowed_origins}
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 4), $utf8)
New-Item -Path $registryPath -Force | Out-Null
Set-Item -LiteralPath $registryPath -Value $manifestPath
Write-Output 'Registered only for this Windows user and the specified Chrome extension. Restart Chrome if needed.'
