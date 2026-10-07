$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition -ne 'Desktop') { & powershell.exe -NoProfile -STA -File $PSCommandPath;exit $LASTEXITCODE }
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
Add-Type -Path @((Join-Path $root 'apps\desktop\windows\TranslationBridge.cs'),(Join-Path $root 'apps\desktop\windows\VoiceSelection.cs')) -ReferencedAssemblies @('PresentationFramework','PresentationCore','WindowsBase','System.Xaml','System','System.Core','System.Web.Extensions')
$count=0
function Assert($value,$message){if(!$value){throw $message};$script:count++}
$voices=@()
foreach($entry in @(@('Default','male','north'),@('South','female','south'))) {
 $item=New-Object AniSub.Windows.VoicePreset
 $item.name=$entry[0];$item.gender=$entry[1];$item.region=$entry[2];$item.description='Verified metadata fixture'
 $voices+=$item
}
$voices=[AniSub.Windows.VoicePreset[]]$voices
$control=New-Object AniSub.Windows.VoiceSelection
Assert (!$control.IsEnabled -and !$control.SelectedVoice) 'Catalog unavailable initially'
$control.SetCatalog([AniSub.Windows.VoicePreset[]]$voices,'Default')
Assert ($control.IsEnabled -and $control.SelectedVoice -eq 'Default') 'Default preserved'
Assert ([AniSub.Windows.VoiceSelection]::Filter($voices,'north','male').Count -eq 1) 'North male filter'
Assert ([AniSub.Windows.VoiceSelection]::Filter($voices,'south','female').Count -eq 1) 'South female filter'
Assert ([AniSub.Windows.VoiceSelection]::Filter($voices,'central','all').Count -eq 0) 'Central not invented'
Assert ([AniSub.Windows.VoiceSelection]::Filter($voices,'north','female').Count -eq 0) 'No unsupported voice fallback'
$region=$control.Children[1]
Assert (!$region.Items[3].IsEnabled) 'Central disabled honestly'
$region.SelectedIndex=2
Assert ($control.SelectedVoice -eq 'South') 'Filter selects supported actual preset'
$rejected=$false;try{$control.SetCatalog([AniSub.Windows.VoicePreset[]]$voices,'UNKNOWN')}catch{$rejected=$true}
Assert $rejected 'Missing default rejected'
$rejected=$false;try{$control.SetCatalog([AniSub.Windows.VoicePreset[]]@($voices[0],$voices[0]),'Default')}catch{$rejected=$true}
Assert $rejected 'Duplicate names rejected'
$rejected=$false;try{$control.SetCatalog([AniSub.Windows.VoicePreset[]]@(),'Default')}catch{$rejected=$true}
Assert $rejected 'Empty catalog rejected'
$python=if($env:ANISUB_PYTHON){$env:ANISUB_PYTHON}else{(Get-Command python -ErrorAction Stop).Source}
$bridge=New-Object AniSub.Windows.TranslationBridge($python,(Join-Path $root 'providers\translation\windows_worker.py'),(Join-Path $root 'models\opus-en-vi-989c9fb'),(Join-Path $root 'models\vieneu-nano-aba295e'))
try {
 $catalog=$bridge.RequestAsync('voice-catalog','').GetAwaiter().GetResult()
 Assert ($catalog.Ok -and $catalog.Voices.Length -eq 11) 'Actual bounded catalog crosses bridge'
 $south=@($catalog.Voices | Where-Object {$_.region -eq 'south' -and $_.gender -eq 'female'})[0]
 $bridge.SelectedVoice=$south.name
 $reply=$bridge.RequestAsync('synthesize','Xin chao. Windows video.').GetAwaiter().GetResult()
 Assert ($reply.Ok -and $reply.VoiceName -ceq $south.name -and $reply.AudioDurationMs -gt 100) 'Selected real preset crosses bridge and produces audio'
 [void]$bridge.RequestAsync('release',$reply.AudioPath).GetAwaiter().GetResult()
 $bridge.SelectedVoice='UNKNOWN'
 $invalid=$bridge.RequestAsync('synthesize','Xin chao.').GetAwaiter().GetResult()
 Assert (!$invalid.Ok -and $invalid.Error -eq 'ValueError') 'Unknown explicit preset refused without fallback'
} finally {$bridge.Dispose()}
Write-Output "PASS $count voice metadata/filter assertions"
