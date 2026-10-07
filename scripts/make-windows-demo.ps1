$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$destination=Join-Path $root 'tests\fixtures\windows-demo.mp4'
if(Test-Path -LiteralPath $destination){throw 'Demo already exists; refusing overwrite.'}
& ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=duration=15:size=640x360:rate=25' -f lavfi -i 'sine=frequency=220:duration=15' -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $destination
if($LASTEXITCODE -ne 0){throw 'Demo generation failed'}
