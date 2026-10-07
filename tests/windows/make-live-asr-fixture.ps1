$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -AssemblyName System.Speech
$directory=Join-Path $root 'work\live-asr-fix'
[void][IO.Directory]::CreateDirectory($directory)
$speech=New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
 $voice=$speech.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'en-*' } | Select-Object -First 1
 if(!$voice){throw 'No installed English test voice; no automatic installation.'}
 $speech.SelectVoice($voice.VoiceInfo.Name)
 $speech.SetOutputToWaveFile((Join-Path $directory 'english-boundaries.wav'))
 $speech.Speak('The battery lasts for two hours, but performance drops when you unplug the charger. This is not a problem with the screen. You should not buy this device only for its battery life. Yes, yes, the price matters too.')
} finally {$speech.Dispose()}
Write-Output 'Owned synthetic English fixture created; no user audio captured.'
