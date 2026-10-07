param([Parameter(Mandatory=$true)][string]$VideoPath)
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
& powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File (Join-Path $root 'apps\desktop\windows\Start-AniSub.ps1') -Smoke -VideoPath $VideoPath
exit $LASTEXITCODE
