# Android M1 model-store evidence

Date: 2026-10-07. Scope: Task 2, pure Java app-private model metadata/store only.
Implemented source and JVM fault tests; no downloaded model, native provider, device or hardware evidence.

Installation rechecks every declared file's byte count and SHA-256 before and after directory
promotion. It rejects missing/extra files, undeclared directories, traversal, executable filename
suffixes and canonical redirects. Installed directories use random store-owned UUID names;
model/version identity is a structured pair, never a delimiter-derived key or filesystem path.
Immutable manifests record per-asset licenses/digests, transport artifacts, provider/API/ABI
compatibility, upstream revision, required frontend assets, speakers and measured-quality metadata.
Manifest metadata alone does not establish reviewed licenses, compatibility or provider availability.

The store writes synced payloads/ownership markers and a bounded SHA-256-protected registry
snapshot. Two alternating generation slots retain the last committed snapshot when writing the
next one fails. A crash after version rename leaves an exact-owned orphan; startup recognizes it
without adding it to the registry or changing last-known-good. Startup automatically reclaims only
exact-owned unregistered staging targets. Promoted version orphans require explicit `reclaimOrphan`;
it refuses registered (including LKG/leased), unknown or redirected targets. Owner-only residue and
partial deletion failures stay enumerated for safe retry. Unknown folders remain untouched.

`install` returns INSTALLED. `acquire` rehashes before providing a pinned prepare lease;
`acquireReady` checks READY and pins under the same store lock. Different model preparations are
denied while another model is leased or READY. Host releases the physical provider and calls
`unload` before preparing a different model. Only the host's successful, current-generation
provider preparation may call `markReady`; the store cannot measure the provider's readiness.
Closed leases are idempotent. Mutations revoke prepare permission and record CORRUPT. Idle READY
metadata transitions to INSTALLED after 60 seconds without a lease. All residency restarts
INSTALLED; READY is never restored merely from persisted files.

Default quota is 1 GiB; the only optional setting is 4 GiB. Installed data, staging, orphan data
and registry/ownership bytes are counted. Reserve checks use checked long arithmetic and require
incoming expansion plus 128 MiB disk free. No automatic eviction. Registry is capped at 64 versions.
Metadata transactions preflight both peak usage (ownership marker, previous committed slots and
temporary snapshot together) and final usage before moving a candidate. Every ownership/snapshot
write is rechecked, including createStage and markReady; snapshot serialization stops at 4 MiB.
Required frontend paths and artifact asset mappings must exactly match declared path case.

Verification command (PowerShell, existing wrapper borrowed read-only):

```powershell
$env:JAVA_HOME='D:/SEOSONA AI/SEOSONA TV/.tools/jdk'
$env:ANDROID_HOME='D:/SEOSONA AI/SEOSONA TV/.tools/android-sdk'
& 'D:/SEOSONA AI/AniBox/gradlew.bat' -p 'D:/SEOSONA AI/AniSub/apps/android' --offline testDebugUnitTest --tests 'com.anisub.runtime.models.ModelStoreTest' assembleDebug lintDebug
```

Initial behavioral RED compiled: 4 tests, 4 failures (missing store behavior and budget admission).
Self-review RED: 8 tests, 2 failures (late mutation permission; undeclared empty directory).
Prepare-admission RED: 1 test, 1 failure (another resident model permitted a preparation lease).
Focused GREEN: 8 tests, zero failures/errors/skips. Test report:
`apps/android/app/build/test-results/testDebugUnitTest/TEST-com.anisub.runtime.models.ModelStoreTest.xml`.
Final focused tests + assembleDebug + lintDebug: exit 0, BUILD SUCCESSFUL in 10s.
Fresh full testDebugUnitTest: BUILD SUCCESSFUL in 8s, 16 tests (8 model-store + 8 protocol),
zero failures/errors/skips. Lint: 0 errors/4 warnings; three previous warnings and the
conservative API23 File.getUsableSpace advisory (new UsableSpace warning).

Tests cover digest/byte rejection, post-install and post-acquire mutation, active lease/removal,
idempotent close, idle boundary, one-resident prepare admission, startup residency reset,
rename and registry-write interruption, last-known-good preservation, 64-version bound,
structured NUL-containing identities, overflow/low-space denial, safe owned staging cancellation,
unknown-folder preservation, metadata immutability/serialization and canonical symlink escape.
Actual symlink creation succeeded on this Windows JVM; the test also has a directory-junction
fixture fallback for Windows hosts where symlink creation is denied.

Review fix round1/5 (I1 quota metadata, I2 recovery cleanup, I3 exact path references): each finding
had compiling behavioral RED before its fix. Fresh focused GREEN: 15 tests, zero errors/failures/
skips. New regressions cover near-1GiB temporary/final tree accounting with both registry slots,
createStage/markReady admission, bounded snapshot serialization, owned staging startup cleanup,
promoted orphan reclaim/reinstall, interrupted-removal retry, owner-only/deletion-failure retry,
registered/LKG/leased/unrelated preservation, and wrong-case reference rejection/valid serialization.
The near-quota fixtures use temporary-file logical length; no real user model/store was changed.
Final fresh shared testDebugUnitTest + assembleDebug + lintDebug: BUILD SUCCESSFUL in 24s,
23 tests (15 store + 8 protocol), zero failures/errors/skips; lint remains 0 errors/4 warnings.

Limits: app host must own one store instance and use a canonical app-private root; operations are
serialized within that instance. Native release/init deadline, license/catalog promotion,
download/extraction and Android adapter wiring belong to later tasks. Canonical checks assume
app-private storage and coordinated writers; they are not a defense against a hostile process
with the same filesystem UID racing file replacement. File fsync/rename process-crash tests do
not establish Android power-loss/directory durability, performance or physical API-23 acceptance.
Promoted orphan recovery remains an explicit host operation, not automatic eviction; failed staging
cleanup leaves safe retry targets.
