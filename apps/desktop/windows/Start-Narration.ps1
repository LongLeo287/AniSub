param([string]$SessionFile)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition -ne 'Desktop' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') {throw 'Use Windows PowerShell 5.1 STA.'}
$root=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
if(!$SessionFile){$SessionFile=Join-Path $root 'work\narration-session.json'}
$session=Get-Content -LiteralPath $SessionFile -Encoding UTF8 -Raw | ConvertFrom-Json
$python=(Get-Content -LiteralPath (Join-Path $root 'work\windows-local.json') -Encoding UTF8 -Raw | ConvertFrom-Json).python
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
Add-Type -Path @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.cs' | ForEach-Object FullName) -ReferencedAssemblies @('PresentationFramework','PresentationCore','WindowsBase','System.Xaml','System','System.Core','System.Web.Extensions')
$provider=New-Object AniSub.Windows.TranslationBridge($python,(Join-Path $root 'providers\translation\windows_worker.py'),(Join-Path $root 'models\opus-en-vi-989c9fb'),(Join-Path $root 'models\vieneu-nano-aba295e'))
$app=New-Object System.Windows.Application
$window=New-Object AniSub.Windows.AppWindow($provider,$false)
$readyPath=Join-Path $root 'work\narration-ready.txt'
[IO.File]::WriteAllText($readyPath,'STARTING processId='+$PID)
$window.Add_ContentRendered({
    $script:startTask=$window.StartVietnameseNarration([string]$session.video,[string]$session.subtitle,[double]$session.startMs)
    $script:poll=New-Object System.Windows.Threading.DispatcherTimer
    $script:poll.Interval=[TimeSpan]::FromSeconds(1)
    $script:poll.Add_Tick({
        if($script:startTask.IsCompleted) {
            if($script:startTask.IsCanceled) {
                [IO.File]::WriteAllText($readyPath,'CANCELLED processId='+$PID+' '+$window.NarrationDiagnostic)
                $script:poll.Stop()
            } elseif($script:startTask.IsFaulted) {
                $redacted='FAILED processId='+$PID+' '+$script:startTask.Exception.GetBaseException().GetType().Name
                [IO.File]::WriteAllText($readyPath,$redacted)
                $script:poll.Stop()
            } else {
                [IO.File]::WriteAllText($readyPath,'READY processId='+$PID+' '+$window.NarrationDiagnostic)
            }
        }
    })
    $script:poll.Start()
})
$window.Add_Closed({if($script:poll){$script:poll.Stop()}})
try {[void]$app.Run($window)} finally {$provider.Dispose()}
