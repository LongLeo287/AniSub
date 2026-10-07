param([string]$PythonPath)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
if(!$PythonPath) {
    $local=Join-Path $root 'work\windows-local.json'
    if(Test-Path -LiteralPath $local){$PythonPath=(Get-Content -LiteralPath $local -Raw | ConvertFrom-Json).python}
    else {$PythonPath=(Get-Command python -ErrorAction Stop).Source}
}
& (Join-Path $PSScriptRoot 'Start-AniSub.ps1') -PythonPath $PythonPath -ModelDir (Join-Path $root 'models\opus-en-vi-989c9fb') -TtsDir (Join-Path $root 'models\vieneu-nano-aba295e')
exit $LASTEXITCODE
