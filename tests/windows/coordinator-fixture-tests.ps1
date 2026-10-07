$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
$files=@(Get-ChildItem (Join-Path $root 'apps\desktop\windows') -Filter '*.cs' | ForEach-Object FullName)+(Join-Path $PSScriptRoot 'CoordinatorFixtureTests.cs')
Add-Type -Path $files -ReferencedAssemblies @('PresentationFramework','PresentationCore','WindowsBase','System.Xaml','System','System.Core','System.Web.Extensions')
$python=(Get-Content (Join-Path $root 'work\windows-local.json') -Raw | ConvertFrom-Json).python
$app=New-Object System.Windows.Application
$window=New-Object System.Windows.Window
$window.Width=1;$window.Height=1;$window.ShowInTaskbar=$false;$window.Opacity=0
$window.Add_Loaded({
    $script:task=[AniSub.Windows.CoordinatorFixtureTests]::Run($python,(Join-Path $PSScriptRoot 'sync-fixture-worker.py'))
    $script:timer=New-Object System.Windows.Threading.DispatcherTimer
    $script:timer.Interval=[TimeSpan]::FromMilliseconds(50)
    $script:deadline=[DateTime]::UtcNow.AddSeconds(15)
    $script:timer.Add_Tick({if($script:task.IsCompleted){$script:timer.Stop();if($script:task.IsFaulted){$script:failure=$script:task.Exception.GetBaseException().Message}else{$script:result=$script:task.Result};$window.Close()}elseif([DateTime]::UtcNow -gt $script:deadline){$script:failure='Fixture timeout';$window.Close()}})
    $script:timer.Start()
})
[void]$app.Run($window)
if($script:failure){throw $script:failure}
Write-Output $script:result
