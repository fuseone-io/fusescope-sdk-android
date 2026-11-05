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
