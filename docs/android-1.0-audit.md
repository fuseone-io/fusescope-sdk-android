# Android 1.0 stabilization audit

Tracking: [RUM E / #189](https://github.com/fuseone-io/fusescope/issues/189).
Baseline: SDK main `94649af`, after native level-1 acceptance. This audit prepares 1.0;
it does not claim that 1.0 has been released or that all acceptance gates have passed.

## Public surface to preserve

- `FuseScope`: `VERSION`, `init`, `setConsent`, `setUser`, `addError`, `addAction`,
  `startView`, `callFactory`, `openTelemetry`, `flush`, `shutdown`.
- Configuration: `FuseScopeConfig`, `PrivacyConfig`, `SessionConfig`,
  `InstrumentationsConfig`, their existing constructor parameters, defaults and Java overloads.
- Compose: `FuseScopeView`, `FuseScopeNavigationViews`, `NavController.trackFuseScopeViews`.
- Gradle DSL: `fusescope` endpoint, applicationId, apiKey, release, uploadPath, enabled,
  failOnError; property/environment fallbacks and per-variant upload task names.
- Wire model: existing resource/session/view/error/vital attributes, gzip OTLP protobuf,
  `FuseScope-RUM-Key`, `/rum/v1/traces` and `/rum/v1/logs`.
- Mapping contract: Editor secret via `SigNoz-Api-Key`; application/release/map-ID matching.
  Public RUM keys must never replace upload credentials.

Java callers have a compile/run test for the facade and shortest constructor/method overloads.
Internal implementation classes and compiler-generated helpers are not extension points.
OpenTelemetry and OkHttp types appear in the facade; their pinned versions are part of the
compatibility matrix, and dependency upgrades require renewed consumer validation.

## Configuration and lifecycle review

Defaults remain unchanged from beta.3: full-session sampling 1.0; consent opt-in;
15-minute background timeout, 4-hour maximum; hashed users, no installation ID,
query removal and no disk buffering. Crashes/native recovery, ANRs, views and app start
are enabled; lifecycle spans and slow rendering are disabled. Regression tests protect
these choices. `traceparentHosts` defaults to the endpoint host; an empty list disables
propagation while retaining request spans. Unsampled sessions propagate no trace context.

Invalid configuration is logged and ignored. Duplicate initialization is ignored;
shutdown allows reinitialization. Consent withdrawal flushes previously collected data
and stops collection; it does not retract prior exports. Collection resumes on a later
grant. A new regression test checks actual error exports and request headers across
withheld consent, grant, withdrawal and grant again, using a factory created before init.

Finding: invalid endpoint diagnostics echoed query/fragment values, and endpoints with
embedded credentials passed validation. The regression run
[41747780762](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/41747780762)
failed both new tests (53 SDK tests, 2 expected failures). Validation now parses the URL,
rejects credentials and reports a fixed diagnostic without the supplied endpoint.
Migration: remove userinfo from endpoints; authenticate with the existing public `key`.
No constructor, default or wire-format change is required.

## Tested baseline and remaining acceptance

| Area | Existing coverage / evidence | 1.0 release gate |
|---|---|---|
| Sessions and sampling | SessionsTest, SamplingTest, FuseScopeTest | Real-backend session continuity and zero-sampling acceptance |
| Consent and network | FuseScopeTest, ResolvedConfigTest; grant/revoke export/header regression | Exercise current sample with consent and propagation policy |
| Views | AutoViewsTest; Compose route-template implementation | Activity/Fragment/Compose navigation in a real app |
| Handled errors and privacy | FuseScopeTest, ExportersTest, ScrubTest, UserTest | Confirm scrubbed errors in Errors |
| Java crash, ANR and R8 | ExportersTest; backend Android integration; Gradle uploader tests and R8 sample build | Real minified sample crash and ANR, upload actual matching mapping, retrace in Errors |
| Native recovery | Real emulator/collector/Errors [41646520775](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/41646520775), beta.3/backend beta.2 | Re-run against the final published 1.0 artifacts |
| Public API / consumer | Java facade test, defaults test, published beta.3 SDK + Compose consumer build | Verify all published 1.0 artifacts and plugin marker |

The existing native acceptance archive is attached to backend `v0.159.0-beta.2`.
Native ELF/DWARF symbolication is D level 2 (#188/#191), outside E's release gate.

## Compatibility to carry into the release

Verified baseline: SDK beta.3 on backend `0.159.0-beta.2`, JDK 21, Gradle wrapper
9.7.1, AGP 9.4.0, Kotlin 2.4.10, compile SDK 37, runtime emulator API 35.
SDK/Compose min SDK is 23; the sample uses 26. API 30 adds native exit metadata;
API 31 adds available tombstone frames. Core-library desugaring is needed below API 26.
The full-classpath dexing property remains required. OpenTelemetry Android is pinned to
1.7.0 with Java 1.65.0 / instrumentation 2.31.1.

Before release, replace this baseline with actual 1.0 evidence, update the existing
README and public guides in `fusescope-docs`, and verify the advertised toolchain
from workflow artifacts. Do not infer support for older AGP/Gradle or untested Android
API levels solely from minSdk. Publish SDK, Compose, mappings plugin and marker as
one aligned version only after these gates pass. Verify consumption from Maven Central
and preserve source/version/digest and acceptance evidence before closing #189.

## Beta.3 real-backend acceptance — 2026-11-03

[Run 41757268235](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/41757268235)
passed on acceptance source `d93f8ccf916ab1ef72db06fe6d8f850f7b760d1e` with Maven SDK/Compose
`0.1.0-beta.3`, the source Gradle uploader, API 35 emulator and backend `0.159.0-beta.2`.
The published backend image and its exact source are recorded in `versions.txt`.

Accepted with real SDK signals through the backend gateway and collector:

- Activity/Fragment views and Compose route `orders/{id}`, persisted session continuity after relaunch,
  request spans correlated with received W3C trace context, hashed user and removed URL query values.
- Stored `rum.vital` / `app_start`, with milliseconds, rating, original session and release.
- A minified sample's uncaught Java exception and blocked-main-thread ANR, retraced through the R8
  mapping uploaded by that exact build. The Java stack resolves `Checkout.pay`; the ANR resolves
  `MainActivity` frames. Both have no missing-mapping warning.
- Withheld consent, collection after grant, no new handled errors after withdrawal, collection after
  another grant, and no propagation for an explicit empty host list.
- Zero sampling: no ingest and no traceparent, plus a persisted unsampled session proving initialization.
- Actual native SIGABRT recovery with original session/release/time and no duplicate after acknowledged
  export and another relaunch. Three Playwright checks cover native, Java crash and ANR in Errors.

The observed sampled header had flags `03`: the OpenTelemetry 1.65 API also represents the random-ID
bit. The acceptance checks the sampled bit independently and verifies nonzero trace/span IDs against
the stored request span. Six local oracle tests guard these checks and negative-scenario assertions.

Evidence is preserved as
[`android-beta3-full-acceptance.zip`](https://github.com/fuseone-io/fusescope/releases/download/v0.159.0-beta.2/android-beta3-full-acceptance.zip):
JSON events/session/app-start, R8 map ID/hash, source/image manifest and Errors screenshots.
Archive SHA-256: `9eac017f68f25dec5b50e8d78eea2153ace5bec7bc09a571ec574dbfac6e250f`.

Known backend limitation: `appStartStored=true`, `appStartInSessionTimeline=false`. The timeline queries
only browser vitals; Android app-start presentation is tracked separately in
[#312](https://github.com/fuseone-io/fusescope/issues/312). This acceptance verifies storage directly
and does not claim that missing presentation is delivered.

The real-usage beta gate is now complete. Final 1.0 compatibility/migration documentation, publication
of aligned SDK/Compose/plugin/marker artifacts and post-publication consumption/acceptance remain
pending under #189. No 1.0 artifact is published by the acceptance workflow.


## App-start timeline follow-up — #312

[Run 41763544558](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/41763544558)
passed using Maven SDK/Compose beta.3, an API 35 emulator and the source-built backend from
[PR #313](https://github.com/fuseone-io/fusescope/pull/313). It required app start in both the session
API and UI, while repeating native recovery, Java/ANR/R8, sessions/views, propagation/privacy,
consent and zero sampling. All emulator scenarios and four Playwright checks passed.

The session API now returns a leading `app_start` vital with the actual stored value/rating:
`82.795066 ms`, `good`, rendered as `App start 83 ms`. Evidence records
`requiresAppStartTimeline=true`, `appStartStored=true`, `appStartInSessionTimeline=true`.

- Acceptance source: `e7db5b51fb8af838a735d6b79156cd4a41c6d621`.
- Backend source: `28269fbb0109cd2569fd06ffc637b558c7a0cebb`.
- Source-built image ID: `sha256:76e2d10f8d4bafe58ef8baf6cc84eb6934fc61a97bd4b7bf5e3d86fadd8919c9`.
- Artifact: `native-release-acceptance`, including `session-app-start.png`, `session.json`,
  `app-start.json`, `android-acceptance.json` and `versions.txt`.

The backend's final follow-up `abeb32196` only changes an equivalent Go type conversion requested
by lint; its Go tests and all 32 PR checks passed. PostgreSQL and SQLite each passed all 28 RUM
integration tests; web-vital presentation was checked in both themes. Seven local acceptance-oracle
tests passed, including rejection of a web-only vital counter in strict app-start mode.

The published backend beta.2 baseline above still has the original limitation. E must use a backend
release containing #313 before claiming timeline support in the final compatibility guide. Merge
and backend publication, then final SDK 1.0 documentation/publication/consumption remain pending.


## Release preparation follow-up — 2026-11-04

The [migration and compatibility guide](android-1.0-migration.md) now records the
actual toolchain, unchanged public/default/wire contracts, endpoint and older-beta
migration notes, feature-specific backend requirements, and release completion steps.
The public Android guide has a companion update on `docs/189-android-1.0` in
`fusescope-docs`. The released backend minimum for app-start timeline remains pending
#313; preparing the guide does not require publishing that backend now.

CI now publishes all four candidate coordinates as `1.0.0` to a temporary local Maven
repository and runs `scripts/release_consumer.py`. The isolated consumer uses no
included plugin build or SDK/Compose project dependencies. It verifies POM versions,
Compose→SDK and marker→plugin references, resolves SDK/Compose/plugin from the chosen
repository, builds the R8 release sample and records dependency/plugin reports.
Ten local oracle tests pass, including rejection of mismatched versions and source
substitution configuration. Central publication/availability and final 1.0 real-backend
acceptance remain release gates; the local candidate is not a published release.


Validation: [CI 41941627480](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/41941627480)
passed both build and instrumented jobs on `fd59574`. The `release-consumer-candidate`
artifact records all four `1.0.0` coordinates and `releaseBuild=passed`; SDK/Compose
runtime and plugin build-environment reports confirm exact Maven resolution. The
minified isolated consumer built successfully. Candidate checks are local-only; no
Central release was dispatched.


## New collector acceptance and TLS shutdown correction — 2026-11-05

The new collector is the Apache-2.0 distribution `fusescope-collector`, repository
`fusescope-collector-server`. Backend 0.159.0 removed RUM ingest. Acceptance now separates
SDK ingest from backend management/API and the current R8 upload endpoint; the collector
uses the fixture CA/hostname with TLS 1.3 and OpAMP policy. No trust verification is disabled.
Artifact ingress migration #234 remains pending; no artifacts-only collector route is
claimed available. Web/Android setup was corrected separately in backend PR #335 / #235.

The historical Maven beta.3 collector runs passed native recovery, full usage (including
app-start timeline/Java/ANR/R8) and zero sampling, but failed consent. Setup counters arrive
asynchronously, so the negative oracle now also queries actual stored errors/sessions.
The five-cycle diagnostic [42076820901](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/42076820901)
captured the crash: an already-completed batch future invoked exporter shutdown on main,
OkHttp evicted a TLS connection, and Conscrypt's close/write threw
`NetworkOnMainThreadException`. A generic worker-shutdown guard alone did not reproduce
the callback race; direct span/log wrapper tests did.

Regression-first [CI 42081297829](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/42081297829)
ran 59 tests with exactly the two new wrapper tests failing. The fix queues delegate
shutdown on a worker, forwarding its success/failure, without changing Android lifecycle
cleanup, API shape, protocol, sampling or consent defaults. Failure/exception propagation
is also covered. Corrected runtime CI [42082062400](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/42082062400)
and source-consumer follow-up CI [42086337072](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/42086337072)
passed build/instrumented jobs, including the isolated aligned 1.0 local-Maven consumer.
All 16 Python oracle tests passed. No runtime dependency or collector TLS downgrade was added.

Full [collector acceptance 42086331484](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/42086331484)
passed native recovery, full usage, **five** grant/withdraw/resume cycles, zero sampling
and **four** Errors/Sessions UI checks on API 35. Consent had exactly six expected manual
errors, no ingestion before grant/no new errors after withdrawal, and resumed correctly.
The full scenario retained session continuity, Activity/Fragment/Compose, sampled
traceparent/request matching, privacy, Java/ANR retracing and app-start value/rating in
the timeline. Native recovery retained session/release/time and durable-ack deduplication.

Exact identities:

- SDK origin: **source**, candidate version `1.0.0`, acceptance/runtime source
  `b9c495f67c64f7b0b938f4a0ed9ef0a29d9d1b65`; shutdown fix commit `4399c2b`.
- Backend source: `b7d55b948c0b3d4272f17455c2e227608820877d` (main plus #313), image ID
  `sha256:8896f30e79ef8c96606b0110bfebc88b7a4e5663e1ffc4e74c9daf240266448d`.
- Collector 0.1.0: `ghcr.io/fuseone-io/fusescope-collector@sha256:894e8718ceb8c6785997f49014fe8efea9c246122f175504ed980e51afcea028`,
  revision `9549652a55f807cc6c88247b972757388d96b4cf`.
- Artifact `native-release-acceptance`: `versions.txt`, `collector-transport.json`,
  actual SDK/Compose dependency reports, scenario/session/app-start/native/Java/ANR JSON,
  R8 map identity and four screenshots. Private fixture credentials/CA keys are excluded.

This is **source-candidate acceptance, not publication or consumption of Maven 1.0**.
The selected source mode verifies project consumption explicitly and defaults remain
Maven-only. Complete #336's delivery with merge/release tracking; #189 remains open for
merges, backend publication containing #313, actual aligned Maven 1.0 publication and
acceptance of those published artifacts. Stock API 23–28 cannot directly negotiate the
collector's TLS 1.3 listener; its provider/support decision and device tests remain open.
The library minSdk remains 23. The already approved modern-path acceptance does not
resolve or silently remove that older-device gate.


## Android 6+ collector compatibility accepted — 2026-11-07

The user retained API 23+ and chose TLS 1.2/1.3 at RUM ingress without adding Conscrypt.
Collector 0.3.1 and chart 0.161.1 are published; TLS 1.3 remains their default, with an
explicit RUM TLS 1.2 ECDHE-AEAD opt-in for direct old clients. Public TLS termination
must also permit old clients; internal transports retain their policy. Edge residual
CBC tracking belongs to #341 and was not changed by this SDK work.

| AOSP API | Complete acceptance | Negotiated transport | Acceptance source |
|---|---|---|---|
| 23 | [42398331995](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/42398331995) | TLS 1.2, ECDHE-RSA/AES-128-GCM | `a54bac14cd163a6f79e5a71b853db29c335f1b0a` |
| 28 | [42389543298](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/42389543298) | TLS 1.2, ECDHE-RSA/AES-128-GCM | `2cb7b0c4d22413aecb2d8e5ee951a32dc9b4bf0f` |
| 35 | [42389541041](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/42389541041) | TLS 1.3, AES-128-GCM | `2cb7b0c4d22413aecb2d8e5ee951a32dc9b4bf0f` |

All three validate the unchanged source SDK candidate 1.0.0, full/consent/zero-sampling
scenarios, five consent cycles, sessions/views/Compose, propagation/privacy, app-start
timeline and Java/ANR/R8 plus Errors/Sessions UI. Wrong-host and untrusted certificates
are refused. API 35 additionally repeats native recovery and durable acknowledgement.
API 23's real Java crash is required in the backend before acknowledging Android's
system crash dialog; process termination and the original session/release remain checked.

Collector image digest: `sha256:3856f022c2ac27369c3bb1224b8945940bd7d6f6a30fce0f59734584cfc4374b`,
revision `51a5ef114d96953531c7a76737a3d367ce2a9014`. Backend source is
`b7d55b948c0b3d4272f17455c2e227608820877d` (#313); each run records its source-built
image ID. Sanitized artifacts contain TLS attempts, dependency reports, version manifests,
R8 map identity, stored sessions/errors/app-start and UI screenshots; private fixture
credentials/CA keys are excluded.

The host uses the existing sample with test-only minSdk 23 and core-library desugaring.
API 23 predates network-security-config: only its disposable emulator receives the fixture
CA in the system trust store, followed by a full reboot. No SDK trust/provider override is
introduced. ADB commands are bounded, and only pre-TLS connection startup errors may retry;
certificate/hostname/protocol checks stay strict. Earlier incomplete runs revealed test
readiness, async-report and system-dialog assumptions; only the complete runs above count
as acceptance. The final probe-file read deadline is a harness-only follow-up, covered
by its local regression and CI; it changes no SDK runtime code or positive acceptance criteria.

Backend #342 / [PR #343](https://github.com/fuseone-io/fusescope/pull/343) separately fixes
1.0.0 being labelled outdated by the prior exclusive supported-range bound. Publish a
backend containing #313/#343, record its real minimum, then publish and consume all four
aligned 1.0 Maven coordinates and repeat published-version acceptance. This evidence
completes the source-candidate older-device gate, not E's merge/publication gates.
