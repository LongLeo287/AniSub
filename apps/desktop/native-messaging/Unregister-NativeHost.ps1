$ErrorActionPreference = 'Stop'
$registration = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'registration'))
$expected = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'registration'))
if ($registration -ne $expected -or (Split-Path -Parent $registration) -ne $PSScriptRoot) { throw 'Invalid registration directory.' }
if ((Test-Path -LiteralPath $registration) -and ((Get-Item -LiteralPath $registration).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { throw 'Registration directory cannot be a junction or symbolic link.' }
$registryPath = 'HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.anisub.desktop'
$manifestPath = Join-Path $registration 'com.anisub.desktop.json'
if (Test-Path -LiteralPath $registryPath) {
    if ((Get-Item -LiteralPath $registryPath).GetValue('') -ne $manifestPath) { throw 'Different host registered. Nothing removed.' }
    Remove-Item -LiteralPath $registryPath
}
foreach ($file in @('com.anisub.desktop.json', 'allowlist.json', 'python-path.txt', 'AniSubNativeHost.exe')) {
    $target = Join-Path $registration $file
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
}
Write-Output 'Removed this AniSub user registration and generated launcher files. Extension and source preserved; re-registration is available.'
