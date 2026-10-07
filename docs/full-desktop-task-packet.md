# External-media desktop implementation

Baseline: standalone local source workspace, no Git/branch. AniBox remains out of scope.

Root extension of allowed scope: CaptureBridge/CapturePanel/ChromeSetupPanel and focused
integration tests; local-only Turbo adapter/worker profile changes; optional EasyOCR Latin
installer/adapter/tests. Existing EasyOCR 1.7.2 runtime reused, no package installation.
Additional OCR pack limit 115 MiB, total new model-pack storage remains below 2 GiB.

Allowed disjoint owners: (1) Chrome companion and desktop/native-messaging Python broker,
installer and their tests; (2) new Windows ExternalHost.cs, ExternalMedia.cs and background
launcher/tests; (3) providers/capture Python window OCR/process-audio/ASR adapters and tests;
(4) model-manager quality-profile installer and docs/tests only. Root integrates common wire
contract, existing worker/bridge changes and canonical docs. Independent reviewer is read-only.

Local prototype wire: JSON object {type:'snapshot', session:string<=80, revision:integer,
sequence:integer, positionMs:number>=0, playing:boolean, speed:0.5..2, language:'vi'|'en',
cues:[{startMs:number,endMs:number,text:string<=512}]} with <=32 cues and <=64KiB frame.
Type close stops source; type status polls redacted {ok,speaking,translated,error}.
Native messaging host AniSub prototype com.anisub.desktop. Windows named pipe AniSub.Desktop.v1
current-user ACL; peer extension ID verified against an explicitly installed allowlist.
No URLs/credentials/media files cross browser wire. New session on tab/navigation/reconnect.

Acceptance: selected browser video direct cues arrive at local host with real MT/TTS output,
clock/revision guards, bounded admission, stop/disconnect/duck restoration. Manual window OCR
is explicit source-specific ROI consent, no whole-desktop screenshot; process-specific audio
must report unsupported if unavailable and never use global loopback silently. OCR/ASR output
is labelled delayed live recognition, not original-media accurate alignment. Every heavy model
is separate, immutable/integrity checked and chosen explicitly; downloads bounded. Captured user
content is transient, not logged. Auto is truthful recommendation unless verified media state.

Non-goals: AniBox wiring/release/push; anti-cheat bypass/DRM extraction; guaranteeing every
game/site/language or lip-sync; hidden global capture/permission changes; speaker cloning.
Rollback: remove new source hosts/companion and restore reviewed patches; preserve original
videos/current known-good model packs. Native-host registration provides explicit removal.
