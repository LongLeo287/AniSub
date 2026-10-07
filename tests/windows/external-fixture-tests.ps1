param([switch]$Real)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location -LiteralPath $root
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
$files=@('TranslationBridge.cs','ExternalMedia.cs') | ForEach-Object {Join-Path $root ('apps\desktop\windows\'+$_)}
$files+=Join-Path $PSScriptRoot 'ExternalFixtureTests.cs'
Add-Type -Path $files -ReferencedAssemblies PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System,System.Core,System.Web.Extensions
$python=(Get-Content (Join-Path $root 'work\windows-local.json') -Raw | ConvertFrom-Json).python
$worker=if($Real){Join-Path $root 'providers\translation\windows_worker.py'}else{Join-Path $PSScriptRoot 'external-fixture-worker.py'}
$app=New-Object System.Windows.Application
$window=New-Object System.Windows.Window
$window.Width=1;$window.Height=1;$window.Opacity=0;$window.ShowInTaskbar=$false
$window.Add_Loaded({
 $script:task=[AniSub.Windows.ExternalFixtureTests]::Run($python,$worker,$Real.IsPresent)
 $script:timer=New-Object System.Windows.Threading.DispatcherTimer
 $script:timer.Interval=[TimeSpan]::FromMilliseconds(100)
 $script:deadline=[DateTime]::UtcNow.AddSeconds(60)
 $script:timer.Add_Tick({if($script:task.IsCompleted){$script:timer.Stop();if($script:task.IsFaulted){$script:failure=$script:task.Exception.GetBaseException().Message}else{$script:result=$script:task.Result};$window.Close()}elseif([DateTime]::UtcNow -gt $script:deadline){$script:failure='Fixture timeout';$window.Close()}})
 $script:timer.Start()
})
[void]$app.Run($window)
if($script:failure){throw $script:failure}
Write-Output $script:result
