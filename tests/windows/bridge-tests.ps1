$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -Path (Join-Path $root 'apps\desktop\windows\TranslationBridge.cs') -ReferencedAssemblies @('System','System.Core','System.Web.Extensions')
$python=if($env:ANISUB_PYTHON){$env:ANISUB_PYTHON}else{(Get-Command python -ErrorAction Stop).Source}
$echo=New-Object AniSub.Windows.TranslationBridge($python,(Join-Path $PSScriptRoot 'echo_worker.py'),'unused','unused')
try {
    $vietnamese='Ti'+[char]0x1EBF+'ng Vi'+[char]0x1EC7+'t '+[char]0x0111+[char]0x1EA7+'y '+[char]0x0111+[char]0x1EE7+' d'+[char]0x1EA5+'u.'
    $roundTrip=$echo.RequestAsync('translate',$vietnamese).GetAwaiter().GetResult()
    if($roundTrip.Text -cne $vietnamese){throw 'UTF8 Vietnamese round trip failed'}
    Write-Output 'PASS exact Vietnamese UTF8 stdin/stdout round trip (wire fixture only)'
} finally {$echo.Dispose()}
$bridge=New-Object AniSub.Windows.TranslationBridge($python,(Join-Path $root 'providers\translation\windows_worker.py'),(Join-Path $root 'models\opus-en-vi-989c9fb'),(Join-Path $root 'models\vieneu-nano-aba295e'))
try {
    $speechText=''
    foreach($operation in @('probe','translate','synthesize')) {
        $text=if($operation -eq 'synthesize'){$speechText}else{'Hello. This is a test of offline video translation.'}
        $task=$bridge.RequestAsync($operation,$text)
        $result=$task.GetAwaiter().GetResult()
        if(!$result.Ok){throw ('Provider returned '+$result.Error)}
        if($operation -eq 'translate'){$speechText=$result.Text}
        Write-Output ('PASS bridge '+$operation+' '+$result.LatencyMs+'ms')
    }
} finally {$bridge.Dispose()}
