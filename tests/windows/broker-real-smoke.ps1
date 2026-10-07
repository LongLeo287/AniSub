$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location -LiteralPath $root
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
$files=@('TranslationBridge.cs','SourceSelection.cs','VoiceSelection.cs','ExternalMedia.cs','ExternalHost.cs','CaptureBridge.cs','CapturePanel.cs','ChromeSetupPanel.cs') | ForEach-Object {Join-Path $root ('apps\desktop\windows\'+$_)}
Add-Type -Path $files -ReferencedAssemblies PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System,System.Core,System.Web.Extensions
$python=(Get-Content (Join-Path $root 'work\windows-local.json') -Raw | ConvertFrom-Json).python
$window=New-Object AniSub.Windows.ExternalHost($python,(Join-Path $root 'providers\translation\windows_worker.py'),(Join-Path $root 'models\opus-en-vi-989c9fb'),(Join-Path $root 'models\vieneu-turbo-61b85e3'))
$window.Opacity=0;$window.ShowInTaskbar=$false
$job=Start-Job -ArgumentList $python,(Join-Path $root 'tests\native-messaging\desktop-real-smoke.py') -ScriptBlock {param($runtime,$fixture); & $runtime $fixture 2>&1 | ForEach-Object {"$_"};if($LASTEXITCODE -ne 0){throw 'Real broker smoke failed'}}
$timer=New-Object System.Windows.Threading.DispatcherTimer
$timer.Interval=[TimeSpan]::FromMilliseconds(100)
$deadline=[DateTime]::UtcNow.AddSeconds(40)
$timer.Add_Tick({if($job.State -in @('Completed','Failed') -or [DateTime]::UtcNow -gt $deadline){$timer.Stop();$window.Close()}})
$timer.Start()
$app=New-Object System.Windows.Application
[void]$app.Run($window)
if($job.State -eq 'Failed'){Receive-Job $job -ErrorAction Continue;throw 'Real broker fixture failed'}
if($job.State -ne 'Completed'){Stop-Job $job;throw 'Real broker fixture timed out'}
Receive-Job $job -ErrorAction Stop
Remove-Job $job
