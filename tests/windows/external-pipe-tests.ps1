$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location -LiteralPath $root
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
$files=@('TranslationBridge.cs','SourceSelection.cs','VoiceSelection.cs','ExternalMedia.cs','ExternalHost.cs','CaptureBridge.cs','CapturePanel.cs','ChromeSetupPanel.cs') | ForEach-Object {Join-Path $root ('apps\desktop\windows\'+$_)}
Add-Type -Path $files -ReferencedAssemblies PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System,System.Core,System.Web.Extensions
$python=(Get-Content (Join-Path $root 'work\windows-local.json') -Raw | ConvertFrom-Json).python
$window=New-Object AniSub.Windows.ExternalHost($python,(Join-Path $PSScriptRoot 'external-fixture-worker.py'),'unused','unused')
$window.Opacity=0;$window.ShowInTaskbar=$false
# Client runs independently of owner Dispatcher. One connection per frame mirrors native broker.
$job=Start-Job -ScriptBlock {
 function Request([string]$line) {
  $pipe=New-Object System.IO.Pipes.NamedPipeClientStream('.','AniSub.Desktop.v1',[System.IO.Pipes.PipeDirection]::InOut)
  $pipe.Connect(3000)
  $writer=New-Object System.IO.StreamWriter($pipe,(New-Object System.Text.UTF8Encoding($false)));$writer.AutoFlush=$true
  $reader=New-Object System.IO.StreamReader($pipe,(New-Object System.Text.UTF8Encoding($false)))
  try{$writer.WriteLine($line);return $reader.ReadLine()|ConvertFrom-Json}finally{$pipe.Dispose()}
 }
 $a=Request '{"type":"status"}';if(!$a.ok){throw 'Initial status failed'}
 $b=Request '{"type":"snapshot","session":"pipe-test","revision":1,"sequence":1,"positionMs":0,"playing":true,"speed":1,"language":"vi","cues":[{"startMs":0,"endMs":10000,"text":"SLOW"}]}'
 if(!$b.ok){throw 'Snapshot failed'}
 Start-Sleep -Milliseconds 250
 $c=Request '{"type":"status","session":"pipe-test","revision":1,"sequence":2}';if(!$c.ok){throw 'Per-frame disconnect cancelled session'}
 $d=Request '{"type":"snapshot","session":"pipe-test","revision":0,"sequence":2,"positionMs":0,"playing":true,"speed":1,"language":"vi","cues":[]}'
 if($d.ok -or $d.error -ne 'STALE_SNAPSHOT'){throw 'Stale guard failed'}
 $e=Request '{not json}';if($e.ok -or $e.error -ne 'INVALID_MESSAGE'){throw 'Malformed guard failed'}
 $foreign=Request '{"type":"close","session":"old-tab","revision":1,"sequence":999}';if($foreign.ok -or $foreign.error -ne 'STALE_CONTROL'){throw 'Foreign close accepted'}
 $foreign=Request '{"type":"status","session":"old-tab","revision":1,"sequence":999}';if($foreign.ok -or $foreign.translated){throw 'Foreign status exposed caption'}
 $f=Request '{"type":"close","session":"pipe-test","revision":1,"sequence":3}';if(!$f.ok -or $f.speaking){throw 'Close failed'}
 'EXTERNAL_PIPE_PASS 8'
}
$timer=New-Object System.Windows.Threading.DispatcherTimer
$timer.Interval=[TimeSpan]::FromMilliseconds(100)
$deadline=[DateTime]::UtcNow.AddSeconds(15)
$timer.Add_Tick({if($job.State -in @('Completed','Failed') -or [DateTime]::UtcNow -gt $deadline){$timer.Stop();$window.Close()}})
$timer.Start()
$app=New-Object System.Windows.Application
[void]$app.Run($window)
if($job.State -ne 'Completed'){Stop-Job $job;throw 'Pipe fixture failed/timed out'}
Receive-Job $job -ErrorAction Stop
Remove-Job $job
