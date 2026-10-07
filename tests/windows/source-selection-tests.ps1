$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition -ne 'Desktop' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') { & 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -STA -File $PSCommandPath; exit $LASTEXITCODE }
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
Add-Type -Path (Join-Path $root 'apps\desktop\windows\SourceSelection.cs') -ReferencedAssemblies @('PresentationFramework','PresentationCore','WindowsBase','System.Xaml','System','System.Core')
$count=0
function Assert($value,$message) { if(!$value){throw $message};$script:count++ }
$a=New-Object AniSub.Windows.SourceCandidate(123,100,[IntPtr]5,'chrome','test')
$same=New-Object AniSub.Windows.SourceCandidate(123,100,[IntPtr]5,'chrome','new title')
$reused=New-Object AniSub.Windows.SourceCandidate(123,101,[IntPtr]5,'chrome','test')
$differentWindow=New-Object AniSub.Windows.SourceCandidate(123,100,[IntPtr]6,'chrome','test')
Assert ($a.MatchesIdentity($same)) 'Title change preserves identity'
Assert (!$a.MatchesIdentity($reused)) 'Reused PID rejected'
Assert (!$a.MatchesIdentity($differentWindow)) 'Different window rejected'
Assert (!$a.MatchesIdentity($null)) 'Missing identity rejected'
Assert ($a.IsBrowser -and [AniSub.Windows.SourceDiscovery]::IsBrowserName('MSEDGE')) 'Browser classification'
Assert (![AniSub.Windows.SourceDiscovery]::IsBrowserName('game')) 'Game not browser'
$long=New-Object AniSub.Windows.SourceCandidate(123,100,[IntPtr]5,'test',('x'*500))
Assert ($long.WindowTitle.Length -eq 120) 'Window title bounded'
$rejected=$false;try{[void](New-Object AniSub.Windows.SourceCandidate(0,100,[IntPtr]5,'test','test'))}catch{$rejected=$true}
Assert $rejected 'Invalid PID rejected'
$sources=[AniSub.Windows.SourceDiscovery]::Discover($PID)
Assert ($sources.Count -le 128) 'Discovery bounded'
Assert (@($sources | Where-Object ProcessId -eq $PID).Count -eq 0) 'Own host excluded'
Assert (![AniSub.Windows.SourceDiscovery]::Revalidate($a)) 'Stale synthetic source rejected'
$panel=New-Object AniSub.Windows.SourceSelectionPanel
Assert ($null -eq $panel.SelectedSource) 'Discovery does not bind source'
$panel.RefreshSources()
Assert ($null -eq $panel.SelectedSource) 'Refresh does not bind source'
Assert (!$panel.Bind($a)) 'Invalid source does not bind'
$panel.ClearSelection()
Assert ($null -eq $panel.SelectedSource) 'Clear selection'
$panel.Dispose();$panel.Dispose()
Assert (!$panel.Bind($a)) 'Disposed panel rejects binding'
Write-Output "PASS $count source selection assertions (read-only; no capture)"
