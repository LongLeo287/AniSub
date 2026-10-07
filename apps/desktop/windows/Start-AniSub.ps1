param([string]$PythonPath,[string]$ModelDir,[string]$TtsDir,[switch]$Smoke,[string]$VideoPath)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition -ne 'Desktop' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') {
    $argsList=@('-NoProfile','-STA','-ExecutionPolicy','Bypass','-File', $PSCommandPath)
    foreach($pair in @(@('-PythonPath',$PythonPath),@('-ModelDir',$ModelDir),@('-TtsDir',$TtsDir),@('-VideoPath',$VideoPath))) { if($pair[1]) {$argsList += $pair} }
    if($Smoke){$argsList+='-Smoke'}
    & powershell.exe @argsList
    exit $LASTEXITCODE
}
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System.Speech
$refs=@('PresentationFramework','PresentationCore','WindowsBase','System.Xaml','System.Speech','System','System.Core','System.Web.Extensions')
Add-Type -Path @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.cs' | ForEach-Object FullName) -ReferencedAssemblies $refs
$provider=$null
if($PythonPath -and $ModelDir -and $TtsDir) {
    $root=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
    $worker=Join-Path $root 'providers\translation\windows_worker.py'
    $provider=New-Object AniSub.Windows.TranslationBridge($PythonPath,$worker,$ModelDir,$TtsDir)
}
$app=New-Object System.Windows.Application
$window=New-Object AniSub.Windows.AppWindow($provider,[bool]$Smoke)
if($Smoke) {
    if(!$VideoPath){throw 'Smoke requires -VideoPath.'}
    $window.Add_ContentRendered({
        $script:smokeTask=$window.RunSmoke($VideoPath)
        $script:poll=New-Object System.Windows.Threading.DispatcherTimer
        $poll.Interval=[TimeSpan]::FromMilliseconds(100)
        $poll.Add_Tick({
            if($script:smokeTask.IsCompleted){
                $script:poll.Stop()
                if($script:smokeTask.IsFaulted){$script:smokeFailure=$script:smokeTask.Exception.GetBaseException().Message}
                else {$script:smokeResult=$script:smokeTask.Result}
                $window.Close()
            }
        })
        $poll.Start()
    })
}
try { [void]$app.Run($window) } finally { if($provider -is [IDisposable]){$provider.Dispose()} }
if($script:smokeFailure){throw $script:smokeFailure}
if($script:smokeResult){Write-Output $script:smokeResult}
