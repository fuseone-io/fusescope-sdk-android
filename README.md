<p align="center">
  <img alt="FuseScope" src=".github/brand/fusescope-logo-universal.svg" width="440">
</p>

<h1 align="center">FuseScope Android SDK</h1>

<p align="center">
  <b>Real User Monitoring for Android apps, built on OpenTelemetry Android.</b>
</p>

<p align="center">
  <a href="https://docs.fusescope.fuseone.io/frontend/set-up-android/">Docs</a> ·
  <a href="https://central.sonatype.com/namespace/io.fuseone.fusescope">Maven Central</a> ·
  <a href="LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-0f5fb8"></a>
  <a href="https://github.com/fuseone-io/fusescope-sdk-android/issues"><img alt="GitHub issues" src="https://img.shields.io/github/issues/fuseone-io/fusescope-sdk-android?color=950fb8"></a>
</p>

Real User Monitoring for Android apps, sending to [FuseScope](https://github.com/fuseone-io/fusescope). It is a
thin distribution over [OpenTelemetry Android](https://github.com/open-telemetry/opentelemetry-android) 1.7.0
(sessions, crashes, ANRs, app start, OkHttp spans) that adds what the FuseScope data model needs: sessions with
`session.previous_id` and session sampling, `view` spans for Activities, Fragments and Compose, PII scrubbing,
hashed users, crash and ANR events in the `exception` model, the `app_start` vital and an R8 mapping upload
plugin. It follows `@fusescope/browser` and [ADR 0003](https://github.com/fuseone-io/fusescope/blob/main/docs/adr/0003-real-user-monitoring.md).

**Homologated, not mandatory.** This is the tested and recommended path, but FuseScope accepts any OTLP client.
Clients configured by hand only need the attributes under [Data](#data).

Status: `0.1.0-beta.3` is published. Android 1.0 stabilization is tracked in
[#189](https://github.com/fuseone-io/fusescope/issues/189); the API audit and release gates are recorded in
[`docs/android-1.0-audit.md`](docs/android-1.0-audit.md). Upstream instrumentation includes alpha components.

## Install

```kotlin
// app/build.gradle.kts
plugins {
    id("io.fuseone.fusescope.android.mappings") version "0.1.0-beta.3" // optional, uploads R8 mappings
}

dependencies {
    implementation("io.fuseone.fusescope:android:0.1.0-beta.3")
    implementation("io.fuseone.fusescope:android-compose:0.1.0-beta.3") // optional, Compose and Navigation views
}
```

Everything is on Maven Central, the plugin included: add `mavenCentral()` to `pluginManagement.repositories` in
`settings.gradle.kts`.

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FuseScope.init(
            this,
            FuseScopeConfig(
                applicationId = "01a16431-12ca-7064-bae9-6ac64cf20a29", // Frontend › Applications › Setup
                key = "rum_pub_…",
                endpoint = "https://ingest.example.com",
                environment = "production",
            ),
        )
    }
}
```

Requirements, inherited from OpenTelemetry Android 1.7.0:

- `minSdk` 23 and `compileSdk` 37.
- `android.useFullClasspathForDexingTransform=true` in `gradle.properties`; without it debug builds crash with
  `NoSuchMethodError` on `DeclarativeConfigProperties.empty()` ([issue](https://issuetracker.google.com/issues/230454566)).
- With `minSdk` below 26, [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring).

## API

| Function | Does |
|---|---|
| `FuseScope.init(application, config)` | Starts collection. Call it in `Application.onCreate`. A second call or an invalid config is logged and ignored. |
| `FuseScope.setConsent(granted)` | `true` starts collection when `requireConsent` is set; `false` flushes and stops it. |
| `FuseScope.setUser(id)` | Opt-in: sends `user.id`, hashed (see [User identity](#user-identity)). `null` clears it. |
| `FuseScope.addError(throwable, attributes?)` | Reports a handled error (`fusescope.rum.error.source = manual`). |
| `FuseScope.addAction(name, attributes?)` | Reports a custom action. |
| `FuseScope.startView(route)` | Starts a view named by a route, e.g. `orders/{id}`; for Compose screens. |
| `FuseScope.callFactory(okHttpClient)` | An OkHttp `Call.Factory` that traces requests and adds `traceparent`. |
| `FuseScope.openTelemetry` | The OpenTelemetry instance, for custom spans and logs; no-op while collection is off. |
| `FuseScope.flush()` | Exports pending data now (blocking, call it off the main thread). |
| `FuseScope.shutdown()` | Flushes and stops; `init()` can be called again. |

Calls made before collection starts are dropped, except `setUser`, which is applied on start.

`io.fuseone.fusescope:android-compose` adds `FuseScopeView(route)` (a composable that starts a view),
`FuseScopeNavigationViews(navController)` and `NavController.trackFuseScopeViews()`, which start a view per
Navigation destination named by its route template.

## Configuration

| `FuseScopeConfig` | Default | |
|---|---|---|
| `applicationId` | required | RUM application id (a UUID) from FuseScope's Setup screen. |
| `key` | required | Public, write-only key (`rum_pub_…`). Safe to ship in an APK. |
| `endpoint` | required | Collector ingestion base URL on server 0.159.0+, without embedded credentials, query or fragment. Data goes to `<endpoint>/rum/v1/traces` and `<endpoint>/rum/v1/logs`. |
| `service` | — | `service.name`. Unset, the gateway names the service after the FuseScope application. |
| `environment` | — | `deployment.environment.name`. |
| `release` | `versionName` | `service.version`. Must equal the `release` of the mapping upload, which defaults to `versionName` too. |
| `sessionSampleRate` | `1.0` | Share of sessions recorded, 0–1. |
| `traceparentHosts` | the `endpoint`'s host | Hosts whose requests carry `traceparent`: exact (`api.example.com`), subdomains only (`*.example.com`) or `*` for every host. Matched on the host alone, any scheme or port. |
| `requireConsent` | `false` | Collect nothing until `setConsent(true)`. |
| `diskBuffering` | `false` | Upstream disk buffering: survives offline periods, exports 30 s or more later. |
| `privacy.dropQuery` | `true` | Drop query strings from URLs, span names, error messages and stack traces. |
| `privacy.queryAllowlist` | `[]` | Query parameters kept while `dropQuery` is on. |
| `privacy.hashUserId` | `true` | `setUser()` sends the SHA-256 of the id; `false` sends the raw id. |
| `privacy.installationId` | `false` | Send upstream's persistent `app.installation.id`. |
| `session.inactivityTimeoutMillis` | 15 min | A session ends after this long in the background. |
| `session.maxDurationMillis` | 4 h | A session never lasts longer. |
| `instrumentations` | | `crashes`, `anrs`, `views`, `appStart` on; `lifecycleSpans` (upstream `Created`/`Resumed`/`Paused` spans) and `slowRendering` off. |

## Behaviour

- **Sessions.** `session.id` and `session.previous_id` on every span and log record. The session is kept in
  `SharedPreferences`, so a relaunch within the inactivity timeout (a crash, the system killing the app) continues
  it. After 15 minutes in the background, or at 4 hours, the next signal starts a new session whose
  `session.previous_id` is the one that expired. Upstream's `session.start` and `session.end` events are sent.
- **Sampling.** Decided once per session from its id, with the rule of `@fusescope/browser`. An unsampled session
  exports nothing and sends **no `traceparent`**: flags `00` would make parent-based backend samplers drop the
  server trace too.
- **Trace link.** Requests made through `FuseScope.callFactory(client)` are `GET`, `POST`, … spans (upstream
  `opentelemetry-okhttp-3.0`). Those to a host in `traceparentHosts` carry W3C `traceparent`, so the app span
  becomes the parent of the backend trace. By default that is only the `endpoint`'s host, the one host the SDK
  knows is yours, as `@fusescope/browser` propagates to its own origin only: list your API hosts, e.g.
  `traceparentHosts = listOf("api.example.com", "*.example.com")`, or `listOf("*")` for every host (the
  behaviour of 0.1.0-beta.1), which also hands trace ids to third-party APIs. The factory can be created before
  `init()`; requests made while collection is off go through the client unchanged.
- **Views.** A `view` span per screen, ending when the next one starts or its Activity pauses, so its duration
  is the time on screen. The screen is the last Activity or Fragment resumed in one main-thread message, so a host
  Activity or a `NavHostFragment` gets no view of its own; names come from upstream's `@RumScreenName` or the
  class name.
  `FuseScope.startView(route)` replaces the automatic view (and renames it when called within 1 s of it, as a
  Compose screen does on its first frame); that view is restored when its Activity resumes.
  `fusescope.rum.view.loading_type` is `initial_load` for the first view of the process and `route_change`
  after; `fusescope.rum.view.navigation_type` is `load`, `push`, `restore` (the same screen shown again) or
  `manual`.
- **Native crashes (beta.3).** On API 30+, recover native process exits through
  `ApplicationExitInfo` on the next consented launch, with source `native` and the original session,
  release and timestamp. API 31+ can supply tombstone code frames; missing/overwritten traces produce
  metadata only. Only the crashing thread's code frames are retained, excluding registers, memory,
  command lines and logs. `instrumentations.crashes=false` disables recovery. Uninitialized and
  unsampled exits are ignored; successful exports are deduplicated, while failed exports can retry
  while bounded OS history and private snapshots remain available. No native signal handler or new
  runtime dependency is installed. R8 does not apply; ELF/DWARF symbolication is not included.
- **Crashes.** Upstream's crash reporter; the record is flushed before the process dies. Sent as an `exception`
  event with `fusescope.rum.error.source = crash`, severity `FATAL`.
- **ANRs.** Upstream's watchdog reports the main thread after 5 missed 1 s checks (so a block of about 10 s).
  Sent as an `exception` event with `fusescope.rum.error.source = anr`, `exception.type = ANR` and the main
  thread's frames as a Java stack trace, so it is retraced like a crash.
- **App start.** Upstream's `AppStart` span (`start.type` `cold`, `warm` or `hot`) becomes a `rum.vital` event
  `app_start` in milliseconds. A cold start counts from process start (`Process.getStartUptimeMillis`), which
  upstream's span misses, to the end of the first Activity's creation. Ratings follow Android vitals'
  "excessive" thresholds (5 s cold, 2 s warm, 1.5 s hot): `poor` from the threshold, `needs-improvement` from
  half of it, `good` below.
- **Metrics** and upstream's SDK initialization events are off: the gateway accepts traces and logs only.

## Privacy defaults

- Query strings and fragments are dropped from URL attributes, span names, error messages and stack traces;
  `privacy.queryAllowlist` keeps named parameters.
- No user identity unless you call `setUser()`, and then hashed by default.
- No persistent device identifier: upstream's `app.installation.id` is removed unless `privacy.installationId`.
- `requireConsent` with `setConsent()` lets the SDK start only after the user agrees.
- The FuseScope gateway applies the application's PII policy again server-side.

## User identity

`setUser(id)` sends `user.id` on every span and log record that starts afterwards, as the hex SHA-256 of the id
(the same value as `@fusescope/browser`). Spans already started, such as the current view, keep their previous
value. FuseScope can then filter sessions by user without storing who they are.

## Releases and R8 mappings

`release` is the `service.version` resource attribute, and FuseScope retraces stack traces with the mapping
uploaded for it. The `io.fuseone.fusescope.android.mappings` plugin uploads `mapping.txt` after `assemble<Variant>` and
`bundle<Variant>` of every minified variant, each variant its own mapping:

```kotlin
fusescope {
    endpoint.set("https://fusescope.example.com")       // or fusescope.endpoint / FUSESCOPE_ENDPOINT
    applicationId.set("01a16431-…")                     // or fusescope.applicationId / FUSESCOPE_APPLICATION_ID
    apiKey.set(providers.environmentVariable("FUSESCOPE_API_KEY")) // or fusescope.apiKey / FUSESCOPE_API_KEY; an Editor API key
    // release.set("1.4.0")  defaults to the variant's versionName, like the SDK
    // failOnError.set(false)
}
```

Each setting falls back to a Gradle property and then an environment variable, so CI can pass the key without
touching the build script: `./gradlew :app:bundleRelease -Pfusescope.apiKey=…`, or `fusescope.apiKey=…` in
`~/.gradle/gradle.properties` (never in the project's `gradle.properties`, which is committed). The key is an API
key with the Editor role, sent as `SigNoz-Api-Key`; the public RUM key is not accepted.

Without an endpoint, application id or key the upload is skipped with a warning, so local release builds work.
`./gradlew :app:uploadFuseScopeMappingRelease` uploads on its own.

Variants that share a `versionName` (flavors, `staging` build types) share a `release`. Each upload carries R8's
`pg_map_id` as `map_id`, and every obfuscated frame names its file `r8-map-id-<pg_map_id>`, so the backend picks
the mapping of the build that produced the trace (FuseScope ≥ 0.156.0; older servers keep the release's last
upload, so set a distinct `release` per variant there).

## Data

| Signal | Name | Key attributes |
|---|---|---|
| Resource | — | `service.name` (only when `service` is set), `service.version`, `deployment.environment.name`, `fusescope.rum.platform=android`, `fusescope.rum.application.id`, `app.package.name`, `fusescope.rum.android.version_code`, `telemetry.distro.name=io.fuseone.fusescope:android`, `telemetry.distro.version`, `telemetry.sdk.*`, `device.*`, `os.*`, `android.os.api_level` |
| Every span and log | — | `session.id`, `session.previous_id`, `fusescope.rum.view.route`, `user.id`, `app.screen.name`, `network.*` |
| Span | `view` | `fusescope.rum.view.route`, `fusescope.rum.view.loading_type`, `fusescope.rum.view.navigation_type`; duration = time on screen |
| Span | `GET`, `POST`, … | `http.request.method`, `url.full`, `http.response.status_code`, `server.*` |
| Span | `AppStart` | `start.type` (upstream) |
| Log event | `exception` | `exception.type` (`ANR` for ANRs), `exception.message`, `exception.stacktrace`, `fusescope.rum.error.source` (`crash`, `anr`, `manual`, `native`), `fusescope.rum.view.route`, `thread.*` |
| Log event | `rum.vital` | `fusescope.rum.vital.name=app_start`, `fusescope.rum.vital.value`, `fusescope.rum.vital.unit=ms`, `fusescope.rum.vital.rating`, `start.type` |
| Log event | `fusescope.rum.action` | `fusescope.rum.action.type=custom`, `fusescope.rum.action.name` |
| Log event | `session.start`, `session.end`, `network.change`, `device.screen_orientation` | upstream |

Every log record also carries its event name as the **`event.name`** attribute, because FuseScope's storage drops
the record's own event-name field.

### Backend contract

From backend `0.159.0`, RUM requests go to the Apache-2.0 **FuseScope collector**
(repository `fusescope-collector-server`), not the backend API. The collector receives
application/key policy over authenticated TLS OpAMP and applies the gateway behavior below.
The backend queries and Errors UI remain on the management URL. Earlier server versions
used the backend gateway. The SDK's paths, public-key header and OTLP payload stay the same.

What the SDK and FuseScope ≥ 0.155.0 agree on ([ADR 0003](https://github.com/fuseone-io/fusescope/blob/main/docs/adr/0003-real-user-monitoring.md), phase 3).

1. **Platform.** `fusescope.rum.platform=android` on the resource; the gateway overwrites it from the application.
2. **Package name.** `app.package.name` resource attribute, equal to the Android `applicationId`; the gateway
   refuses a request whose package is missing or not among the application's allowed package names. No
   `Origin` header is sent, and the gateway refuses one.
3. **Service.** `service.name` only when `service` is set; otherwise the gateway sets it to the application's name.
4. **Crash vs ANR.** Both are `exception` events; `fusescope.rum.error.source` is `crash` or `anr`, as it is
   `onerror` / `unhandledrejection` / `manual` on the web. ANRs have `exception.type = ANR`. The gateway maps
   plain `opentelemetry-android` `app.crash` and `device.anr` events the same way.
5. **Release.** `service.version` = the upload's `release` (default `versionName` on both sides);
   `fusescope.rum.android.version_code` is informative.
6. **Stack traces** are raw Java traces (`\tat a.b.c(SourceFile:12)`), retraced when read. R8 names each
   obfuscated frame's file `r8-map-id-<pg_map_id>`, and the upload sends the same `map_id`.
7. **Mapping upload.** `POST <mappingEndpoint>/api/v1/rum/applications/{applicationId}/mappings?release=<release>`
   (path configurable with `uploadPath`), header `SigNoz-Api-Key: <Editor API key>`, `multipart/form-data`
   with `release`, `package_name`, `variant`, `version_code` and `map_id`, then the file part `file`
   (`filename="mapping.txt"`, `text/plain`). The server answers 201 with the mapping's `pg_map_id` as `debugId`;
   any 2xx is success.

Mapping uploads still use the backend management API and its Editor key until the
artifact-ingress migration (#234) is delivered. Configure the mappings plugin's
`fusescope.endpoint` independently from `FuseScopeConfig.endpoint`; the former is the
backend URL today, the latter is the collector URL. The sample exposes
`-Pfusescope.sample.mappingEndpoint=<backend-url>` for that separation. A shared hostname
only works if the proxy routes these paths to their respective services.

## Gateway contract

- `POST <endpoint>/rum/v1/traces` and `POST <endpoint>/rum/v1/logs`, OTLP/HTTP **protobuf**
  (`application/x-protobuf`), `Content-Encoding: gzip`, sent chunked by OkHttp.
- The public key in the **`FuseScope-RUM-Key`** header.
- No CORS or `Origin`: requests come from the app, not a browser.

On server 0.159.0+, these routes are served by the collector over TLS. The backend
has no RUM handlers on those paths (the UI catch-all may return HTML). Use a trusted certificate; private CAs must be
trusted by the consuming app. Do not disable certificate or hostname verification.

## Compatibility

| io.fuseone.fusescope:android | FuseScope server | OpenTelemetry Android | OpenTelemetry Java |
|---|---|---|---|
| 0.1.0-beta.1 | ≥ 0.155.0 | 1.7.0 | 1.65.0, instrumentation 2.31.1 |
| 0.1.0-beta.2 | ≥ 0.156.0 (≥ 0.155.0 without per-build mappings) | 1.7.0 | 1.65.0, instrumentation 2.31.1 |
| 0.1.0-beta.3 | ≥ 0.156.0 for existing signals; ≥ 0.159.0-beta.2 for native frames/grouping | 1.7.0 | 1.65.0, instrumentation 2.31.1 |

**Topology change:** server 0.159.0+ requires the new collector as RUM ingress
(published collector 0.1.0 is the acceptance target). Older server minima above do not
imply the ingest routes still exist on the backend in a newer release.

The [Android 1.0 migration and compatibility guide](docs/android-1.0-migration.md)
records the candidate toolchain, feature-specific backend requirements and aligned
artifact verification. Version 1.0.0 is not published yet. App-start timeline support
has passed source-build acceptance in backend PR #313; its backend release is pending.

## Contributing

JDK 21 and the Android SDK (platform 37). Repository layout: `sdk/` (`io.fuseone.fusescope:android`), `compose/`
(`io.fuseone.fusescope:android-compose`), `gradle-plugin/` (`io.fuseone.fusescope.android.mappings`, an included build),
`sample/`.

```bash
./gradlew ktlintCheck lintRelease testDebugUnitTest checkLicense :sample:assembleRelease
./gradlew -p gradle-plugin ktlintCheck test validatePlugins
./gradlew :sdk:connectedDebugAndroidTest   # instrumented smoke test, needs a device or emulator
```

End to end on an emulator, against a local stand-in for the gateway (never a real backend):

```bash
uv run --no-project --with opentelemetry-proto==1.39.1 scripts/stub_gateway.py --port 4399 --out build/stub
FUSESCOPE_API_KEY=stub ./gradlew :sample:installRelease -Pfusescope.sample.uploadMapping=true
adb shell am start -n io.fuseone.fusescope.sample/.MainActivity --es action error   # request, crash, native, anr, details, compose
```

The sample sends to `http://10.0.2.2:4399` (the host from the emulator); the stub writes the decoded OTLP
requests to `build/stub/*.jsonl` and each mapping upload to `build/stub/mappings/<applicationId>/`.

- Behaviour changes start with a failing test: JUnit and Robolectric under `src/test`, the device smoke test
  under `src/androidTest`.
- Every OpenTelemetry artifact stays on one upstream release line (see `gradle/libs.versions.toml`).
- Runtime dependencies must be MIT, BSD, ISC or Apache-2.0; `checkLicense` fails otherwise
  (`config/allowed-licenses.json`).
- Conventional commits; pull requests to `main` with CI green.

## Releasing

`.github/workflows/release.yml` runs on manual dispatch only and does nothing until it is enabled. It checks,
publishes `io.fuseone.fusescope:android`, `io.fuseone.fusescope:android-compose` and `io.fuseone.fusescope:android-mappings-gradle-plugin`
(with its plugin marker) to Maven Central through the Central Portal, GPG-signed as Central requires, and
attests build provenance with Sigstore (GitHub artifact attestations).

To enable it:

1. Create a Central Portal account at <https://central.sonatype.com> and verify the `io.fuseone` namespace
   (a DNS TXT record on `fuseone.io`; with another namespace, change `GROUP` in `gradle.properties`).
2. Generate a Central Portal user token.
3. Create a GPG key for signing and publish its public key to a key server Central checks
   (e.g. `keys.openpgp.org`).
4. In the repository, create the `maven-central` environment (with required reviewers) and add its secrets:

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | Central Portal user token name |
   | `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
   | `SIGNING_KEY` | ASCII-armored private key (`gpg --armor --export-secret-keys <id>`) |
   | `SIGNING_KEY_ID` | Last 8 hex digits of the key id |
   | `SIGNING_KEY_PASSWORD` | Key passphrase |

5. Set the `MAVEN_CENTRAL_ENABLED` variable of that environment to `true`.
6. Dispatch the workflow from `main` with the `version` to publish.

## Licence

[Apache-2.0](LICENSE). See [NOTICE](NOTICE) for the OpenTelemetry attribution.

To exercise native recovery on an API 31+ emulator against the local stub, build the sample with
`./gradlew :sample:assembleDebug`, install `opentelemetry-proto==1.39.1` in your Python environment,
and run `python scripts/native_smoke.py`. It deliberately aborts the sample process and verifies
frames, the original session and no duplicate after relaunch. Native recovery starts in SDK `0.1.0-beta.3`, with FuseScope `0.159.0-beta.2` or later
for parsed native frames and grouping. It is not available in SDK `0.1.0-beta.2`.

The sample uses the SDK projects by default. To validate the actual Maven Central artifacts,
build with `-Pfusescope.sample.sdkVersion=0.1.0-beta.3`. CI also checks this consumer path
against the already published beta.3 before a new release. The manual `native-acceptance`
workflow exercises a published SDK against a real isolated FuseScope and collector, then
checks the native event in the Errors UI.

For complete Android beta/release acceptance, dispatch the same workflow with
`full_acceptance=true`:

```bash
gh workflow run native-acceptance.yml --repo fuseone-io/fusescope-sdk-android \
  --ref main -f sdk_version=0.1.0-beta.3 -f full_acceptance=true
```

Use `--ref` to select an acceptance branch before it is merged. The workflow creates isolated
applications for normal usage, consent and zero sampling; builds SDK/Compose consumers from Maven;
uploads the actual R8 mapping with the source Gradle plugin; and checks native recovery, Java crashes,
ANRs and Errors UI. The sample-only `fusescope.sample.requireConsent`, `sampleRate` and `propagate`
properties configure these scenarios without changing SDK defaults.

Published-image mode pins a digest and checks it against `backend_ref`. To test an unmerged backend,
set `backend_image=source` and `backend_ref` to its commit; the workflow builds the backend and frontend
and records the image identity. With `full_acceptance=true`, `require_app_start_timeline=true` also
requires the stored app start to appear with matching values/ratings in the session API and UI.

Backend `0.159.0-beta.2` omits Android app starts from the timeline. The fix and real-sample acceptance
are in [backend PR #313](https://github.com/fuseone-io/fusescope/pull/313), resolving
[#312](https://github.com/fuseone-io/fusescope/issues/312) once merged and released. The existing beta.2
acceptance remains reproducible with `collector_ingress=false` and explicit beta.2 backend ref/image, without the strict flag. See the [1.0 audit](docs/android-1.0-audit.md)
for the accepted baselines and remaining publication gates.


Acceptance defaults now target backend 0.159.0 and collector 0.1.0. Collector mode
records image identity and proves the backend paths no longer accept OTLP; SDK events go
to the collector while API queries/R8 uploads go to the backend. The emulator verifies
the fixture certificate (including its bridge-IP SAN) using a test CA embedded only
for sample builds; source resources are restored afterward. This is sample/test setup,
not a change to SDK trust defaults. Final collector acceptance is recorded in the audit.
