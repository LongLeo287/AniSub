$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml
$files=@('TranslationBridge.cs','SourceSelection.cs','VoiceSelection.cs','ExternalMedia.cs','ExternalHost.cs','CaptureBridge.cs','CapturePanel.cs','ChromeSetupPanel.cs') | ForEach-Object {Join-Path $root ('apps\desktop\windows\'+$_)}
Add-Type -Path $files -ReferencedAssemblies PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System,System.Core,System.Web.Extensions
$script:checks=0
function Check($pass,$label) { if(!$pass){throw $label};$script:checks++ }
$p=[AniSub.Windows.ContentPolicy]::Parse('pc-game','party | squad | feast;faction')
Check ($p.profile -eq 'pc-game' -and $p.terms.Length -eq 1 -and $p.terms[0].aliases.Length -eq 2) 'policy parse'
Check ([AniSub.Windows.ContentPolicy]::Parse('film','').terms.Length -eq 0) 'empty glossary'
foreach($bad in @(@('console',''),@('general','bad format'),@('general','x | y | z;z'),@('general',"x | y | z`nx | y | q"),@('general',(('a'*81)+' | y | z')))) {
 $rejected=$false
 try{[void][AniSub.Windows.ContentPolicy]::Parse($bad[0],$bad[1])}catch{$rejected=$true}
 Check $rejected 'invalid policy'
}
$rejected=$false;try{[void][AniSub.Windows.ContentPolicy]::Parse('general',"x | y | a`tb")}catch{$rejected=$true}
Check $rejected 'embedded control'
Write-Output ('PASS: full Windows host source compilation; '+$script:checks+' content policy assertions. No host opened or capture started.')
