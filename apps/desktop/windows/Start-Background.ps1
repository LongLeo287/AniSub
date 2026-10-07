param([string]$PythonPath,[string]$TtsDir,[ValidateSet('Auto','Nano','Turbo')][string]$VoiceProfile='Auto')
$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition -ne 'Desktop' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA'){
 $launch=@('-NoProfile','-STA','-ExecutionPolicy','Bypass','-File',$PSCommandPath,'-VoiceProfile',$VoiceProfile)
 if($PythonPath){$launch+=@('-PythonPath',$PythonPath)}
 if($TtsDir){$launch+=@('-TtsDir',$TtsDir)}
 & "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" @launch
 exit $LASTEXITCODE
}
$root=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
if(!$PythonPath){$PythonPath=(Get-Content -LiteralPath (Join-Path $root 'work\windows-local.json') -Raw | ConvertFrom-Json).python}
if(!$TtsDir){
 $turbo=Join-Path $root 'models\vieneu-turbo-61b85e3'
 if($VoiceProfile -eq 'Turbo' -or ($VoiceProfile -eq 'Auto' -and (Test-Path (Join-Path $turbo 'manifest.json')))){$TtsDir=$turbo}
 else{$TtsDir=Join-Path $root 'models\vieneu-nano-aba295e'}
}
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System.Web.Extensions
$files=@('TranslationBridge.cs','SourceSelection.cs','VoiceSelection.cs','ExternalMedia.cs','ExternalHost.cs','CaptureBridge.cs','CapturePanel.cs','ChromeSetupPanel.cs') | ForEach-Object {Join-Path $PSScriptRoot $_}
Add-Type -Path $files -ReferencedAssemblies PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System,System.Core,System.Web.Extensions
[AniSub.Windows.ExternalHost]::Run($PythonPath,(Join-Path $root 'providers\translation\windows_worker.py'),(Join-Path $root 'models\opus-en-vi-989c9fb'),$TtsDir)
