$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition -ne 'Desktop') { & powershell.exe -NoProfile -STA -File $PSCommandPath;exit $LASTEXITCODE }
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System.Speech
Add-Type -Path @(Get-ChildItem (Join-Path $root 'apps\desktop\windows') -Filter '*.cs' | ForEach-Object FullName) -ReferencedAssemblies @('PresentationFramework','PresentationCore','WindowsBase','System.Xaml','System.Speech','System','System.Core','System.Web.Extensions')
$count=0
function Assert($value,$message){if(!$value){throw $message};$script:count++}
$srt=[AniSub.Windows.SubtitleParser]::Parse("1`r`n00:00:01,000 --> 00:00:02,500`r`n<b>Hello</b>`r`nworld`r`n`r`n2`r`n00:00:02,000 --> 00:00:03,000`r`noverlap")
Assert ($srt.Count -eq 2) 'SRT count'
Assert ($srt[0].StartMs -eq 1000 -and $srt[0].EndMs -eq 2500) 'Immutable media timestamps'
Assert ($srt[0].Text -eq "Hello`nworld") 'Multiline/tag normalization'
$vtt=[AniSub.Windows.SubtitleParser]::Parse(([char]0xfeff)+"WEBVTT`n`ncue-id`n00:01.000 --> 00:02.000 align:start`nA &amp; B")
Assert ($vtt.Count -eq 1 -and $vtt[0].Text -eq 'A & B') 'VTT BOM/settings/entities'
foreach($bad in @("00:02.000 --> 00:01.000`nx","00:bad --> 00:02.000`nx",("00:01.000 --> 00:02.000`n"+('x'*4097)),('x'*2097153))) {
 $rejected=$false;try{[void][AniSub.Windows.SubtitleParser]::Parse($bad)}catch{$rejected=$true};Assert $rejected 'Malformed/bounded input rejection'
}
$window=New-Object AniSub.Windows.AppWindow
Assert ($window.Title -like 'AniSub*') 'UI constructs without model'
$flags=[Reflection.BindingFlags]'Instance,NonPublic'
$panel=$window.GetType().GetField('externalSources',$flags).GetValue($window)
$candidate=New-Object AniSub.Windows.SourceCandidate(12345,12345,([IntPtr]12345),'fixture','fixture')
# Fixture state only: no real window binding, capture or model required.
$panel.GetType().GetProperty('SelectedSource').GetSetMethod($true).Invoke($panel,[object[]]@($candidate.PSObject.BaseObject)) | Out-Null
$window.GetType().GetField('narrate',$flags).GetValue($window).IsChecked=$true
$window.GetType().GetField('language',$flags).GetValue($window).SelectedIndex=1
$window.GetType().GetMethod('Invalidate',$flags).Invoke($window,@()) | Out-Null
$coordinator=$window.GetType().GetField('narration',$flags).GetValue($window)
Assert (!$coordinator.GetType().GetField('enabled',$flags).GetValue($coordinator)) 'External source disables local prefetch'
$window.TogglePlayback()
Assert (!$window.GetType().GetField('playing',$flags).GetValue($window)) 'External source cannot play local harness'
$original=$window.GetType().GetField('original',$flags).GetValue($window)
$original.Text='fixture sentinel'
$window.GetType().GetField('mediaOpened',$flags).SetValue($window,$true)
$window.GetType().GetMethod('Tick',$flags).Invoke($window,@()) | Out-Null
Assert ($original.Text -eq 'fixture sentinel') 'External selection does not repaint local captions'
$window.Close()
Write-Output "PASS $count Windows parser/lifecycle assertions"
