# AniSub Chrome companion — local prototype

MV3 companion, no model payload, server or broad host permissions. User action grants access only
to the selected tab. The popup inventories video/track metadata without sending it to Windows.
Clicking **Bắt đầu** authorizes direct subtitle text to the local Windows runtime. Supports one
main-frame HTML5 video with readable Vietnamese/English TextTracks. Cross-origin iframe players,
canvas/burned-in subtitles and DRM may not expose usable tracks. An empty cue moment never
triggers hidden audio/image capture.

## Setup (explicit user action)

1. In Chrome `chrome://extensions`, enable developer mode and **Load unpacked** this folder.
   Copy its 32-character extension ID.
2. Run `apps/desktop/native-messaging/Register-NativeHost.ps1 -ExtensionId YOUR_ID -PythonPath
   "PATH_TO_EXISTING_PYTHON_EXE"` with Windows PowerShell. It compiles a small .NET launcher and
   registers only HKCU, only that extension ID; no dependency download.
3. Start the external AniSub Windows host with prepared models (see root desktop docs).
4. On a normal HTTP(S) video page, open AniSub action; choose video and supported track, then
   **Bắt đầu thuyết minh**. No auto-start on page load. Voice is selected in desktop.
5. **Dừng** restores current user volume and closes the source. Navigation, video replacement
   or disconnected host also restore volume. Stop previous source before switching tabs.

Seek/pause/speed/track changes retire old inference. Heartbeats continue while paused. Browser
clock is sent with active + 5-second-ahead cues. This is live narration, not a guarantee of zero
late/skipped lines, lip sync or original-dialogue removal. Uninstall bridge with
`Unregister-NativeHost.ps1`; remove unpacked extension separately. Registration has **not** been
executed automatically. No installed-Chrome end-to-end claim is made from mock tests.

## Tests

`node --test tests/chrome/*.test.mjs` from repo root. `python -m unittest discover -s
tests/native-messaging` exercises framing/origin/sequence guards. Real browser/voice quality
still requires smoke. Firefox/Edge registration is not included.

Primary references: [Chrome native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging)
and [activeTab](https://developer.chrome.com/docs/extensions/develop/concepts/activeTab).
