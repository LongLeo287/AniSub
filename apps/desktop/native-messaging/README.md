# Local Chrome broker

`native_host.py` validates explicitly registered Chrome origin, consumes 4-byte little-endian
UTF-8 frames (64 KiB limit), strips unknown fields and forwards only snapshot/status/close to
`\\.\pipe\AniSub.Desktop.v1`. Per-request connection, overlapped Windows I/O with 3-second
timeout. Browser EOF sends close; desktop also requires heartbeat expiry. No model inference,
media/file reads from messages, network calls or transcript logs. Only adjacent configuration
`registration/allowlist.json` is read.

`NativeLauncher.cs` relays binary stdio to existing Python. Explicit `Register-NativeHost.ps1`
compiles with Windows .NET Framework compiler and registers one exact extension in HKCU only.
It refuses a different existing registration. Generated launcher/config live under `registration/`.
`Unregister-NativeHost.ps1` removes only that entry and four known generated files with path checks.
Neither install nor removal is automatically executed. Model dependencies remain desktop host
responsibility; this is a local prototype, not protocol-v1 Binder/LAN interop.
