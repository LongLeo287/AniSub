$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition -ne 'Desktop') { & powershell.exe -NoProfile -STA -File $PSCommandPath;exit $LASTEXITCODE }
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
Add-Type -Path @(Get-ChildItem (Join-Path $root 'apps\desktop\windows') -Filter '*.cs' | ForEach-Object FullName) -ReferencedAssemblies @('PresentationFramework','PresentationCore','WindowsBase','System.Xaml','System','System.Core','System.Web.Extensions')
$count=0
function Assert($value,$message){if(!$value){throw $message};$script:count++}
$cues=New-Object 'System.Collections.Generic.List[AniSub.Windows.SubtitleCue]'
$cues.Add((New-Object AniSub.Windows.SubtitleCue(1000,2000,'first')))
$cues.Add((New-Object AniSub.Windows.SubtitleCue(1800,3000,'overlap')))
$cues.Add((New-Object AniSub.Windows.SubtitleCue(14000,15000,'future')))
Assert ([AniSub.Windows.NarrationTimeline]::First($cues,0,0) -eq 0) 'Fresh media anchor'
Assert ([AniSub.Windows.NarrationTimeline]::First($cues,2100,0) -eq 1) 'Seek retires expired input; accepts current overlap'
Assert ([AniSub.Windows.NarrationTimeline]::First($cues,3000,0) -eq 2) 'End boundary does not replay completed cue'
Assert ([AniSub.Windows.NarrationTimeline]::First($cues,2100,200) -eq 0) 'Offset changes media anchor'
Assert ([AniSub.Windows.NarrationTimeline]::First($cues,20000,0) -eq 3) 'EOF has no newly admitted input'
Assert (![AniSub.Windows.NarrationTimeline]::InWindow($cues[2],0,0)) 'Future clips outside 12-second window rejected'
Assert ([AniSub.Windows.NarrationTimeline]::InWindow($cues[2],2000,0)) '12-second window boundary admitted'
Assert (![AniSub.Windows.NarrationTimeline]::Due($cues[0],950,0)) 'Pre-open is not an output start'
Assert ([AniSub.Windows.NarrationTimeline]::Due($cues[0],1000,0)) 'Due uses media clock rather than inference wall time'
Assert (![AniSub.Windows.NarrationTimeline]::Due($cues[0],1000,200)) 'Positive offset delays speech'
Assert ([AniSub.Windows.NarrationTimeline]::MustHold($true,$true,$false,$false)) 'Late inference holds instead of dropping dialogue'
Assert ([AniSub.Windows.NarrationTimeline]::MustHold($true,$true,$true,$true)) 'Overlap preserves accepted speech; waits for completion'
Assert (![AniSub.Windows.NarrationTimeline]::MustHold($true,$false,$true,$true)) 'Caption expiry alone does not cancel accepted speech'
Assert (![AniSub.Windows.NarrationTimeline]::MustHold($false,$true,$false,$true)) 'Explicit realtime mode avoids sync wait'
$ramp=New-Object AniSub.Windows.VolumeRamp(0.8)
$ramp.Set(0.2,1000,150)
Assert ([Math]::Abs($ramp.Advance(1000)-0.8) -lt 0.000001) 'Ducking has no initial volume step'
Assert ([Math]::Abs($ramp.Advance(1075)-0.5) -lt 0.000001) 'Smooth down-ramp midpoint'
Assert ([Math]::Abs($ramp.Advance(1150)-0.2) -lt 0.000001) 'Duck reaches exact target at 150 ms'
$ramp.Set(0.8,1150,350)
Assert ([Math]::Abs($ramp.Advance(1325)-0.5) -lt 0.000001) 'Restore follows 350 ms ramp'
Assert ([Math]::Abs($ramp.Advance(1500)-0.8) -lt 0.000001) 'Restore exact original baseline'
$ramp.Set(0.2,1600,150);$mid=$ramp.Advance(1650);$ramp.Set(0.8,1650,350)
Assert ([Math]::Abs($ramp.Advance(1650)-$mid) -lt 0.000001) 'Cancelled duck reverses without discontinuity'
Write-Output "PASS $count fake-clock timeline/duck assertions (not acoustic output evidence)"
