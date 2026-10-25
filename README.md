# FuseScope Android SDK

Real User Monitoring for Android apps, sending to [FuseScope](https://github.com/fuseone-io/fusescope). It is a
thin distribution over [OpenTelemetry Android](https://github.com/open-telemetry/opentelemetry-android) 1.7.0
(sessions, crashes, ANRs, app start, OkHttp spans) that adds what the FuseScope data model needs: sessions with
`session.previous_id` and session sampling, `view` spans for Activities, Fragments and Compose, PII scrubbing,
hashed users, crash and ANR events in the `exception` model, the `app_start` vital and an R8 mapping upload
plugin. It follows `@fusescope/browser` and [ADR 0003](https://github.com/fuseone-io/fusescope/blob/main/docs/adr/0003-real-user-monitoring.md).

**Homologated, not mandatory.** This is the tested and recommended path, but FuseScope accepts any OTLP client.
Clients configured by hand only need the attributes under [Data](#data).

Status: 0.x, built on upstream's alpha instrumentation; options may change before 1.0.

## Install

```kotlin
// app/build.gradle.kts
plugins {
    id("io.fuseone.fusescope.android.mappings") version "0.1.0" // optional, uploads R8 mappings
}

dependencies {
    implementation("io.fuseone.fusescope:android:0.1.0")
    implementation("io.fuseone.fusescope:android-compose:0.1.0") // optional, Compose and Navigation views
}
```

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FuseScope.init(
            this,
            FuseScopeConfig(
                applicationId = "01a16431-12ca-7064-bae9-6ac64cf20a29", // Frontend › Applications › Setup
                key = "rum_pub_…",
                endpoint = "https://fusescope.example.com",
                environment = "production",
                service = "shop-android",
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
| `applicationId` | required | RUM application id (a UUID) from FuseScope's Setup screen. Also `service.name` unless `service` is set. |
| `key` | required | Public, write-only key (`rum_pub_…`). Safe to ship in an APK. |
| `endpoint` | required | FuseScope base URL. Data goes to `<endpoint>/rum/v1/traces` and `<endpoint>/rum/v1/logs`. |
| `service` | `applicationId` | `service.name`. |
| `environment` | — | `deployment.environment.name`. |
| `release` | `versionName` | `service.version`. Must equal the `release` of the mapping upload, which defaults to `versionName` too. |
| `sessionSampleRate` | `1.0` | Share of sessions recorded, 0–1. |
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
  `opentelemetry-okhttp-3.0`) and carry W3C `traceparent` to every host, so the app span becomes the parent of
  the backend trace. The factory can be created before `init()`; requests made while collection is off go
  through the client unchanged.
- **Views.** A `view` span per screen, ending when the next one starts or its Activity pauses, so its duration
  is the time on screen. The screen is the last Activity or Fragment resumed in one main-thread message, so a host
  Activity or a `NavHostFragment` gets no view of its own; names come from upstream's `@RumScreenName` or the
  class name.
  `FuseScope.startView(route)` replaces the automatic view (and renames it when called within 1 s of it, as a
  Compose screen does on its first frame); that view is restored when its Activity resumes.
  `fusescope.rum.view.loading_type` is `initial_load` for the first view of the process and `route_change`
  after; `fusescope.rum.view.navigation_type` is `load`, `push`, `restore` (the same screen shown again) or
  `manual`.
- **Crashes.** Upstream's crash reporter; the record is flushed before the process dies. Sent as an `exception`
  event with `fusescope.rum.error.source = crash`, severity `FATAL`.
- **ANRs.** Upstream's watchdog reports the main thread after 5 missed 1 s checks (so a block of about 10 s).
  Sent as an `exception` event with `fusescope.rum.error.source = anr`, `exception.type =
  ApplicationNotResponding` and the main thread's frames as a Java stack trace, so it is retraced like a crash.
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
`bundle<Variant>` of every minified variant:

```kotlin
fusescope {
    endpoint.set("https://fusescope.example.com")       // or fusescope.endpoint / FUSESCOPE_ENDPOINT
    applicationId.set("01a16431-…")                     // or fusescope.applicationId / FUSESCOPE_APPLICATION_ID
    apiKey.set(providers.environmentVariable("FUSESCOPE_API_KEY")) // the default; an Editor API key
    // release.set("1.4.0")  defaults to the variant's versionName, like the SDK
    // failOnError.set(false)
}
```

Without an endpoint, application id or key the upload is skipped with a warning, so local release builds work.
`./gradlew :app:uploadFuseScopeMappingRelease` uploads on its own.

## Data

| Signal | Name | Key attributes |
|---|---|---|
| Resource | — | `service.name`, `service.version`, `deployment.environment.name`, `fusescope.rum.platform=android`, `fusescope.rum.application.id`, `app.package.name`, `fusescope.rum.android.version_code`, `telemetry.distro.name=io.fuseone.fusescope:android`, `telemetry.distro.version`, `telemetry.sdk.*`, `device.*`, `os.*`, `android.os.api_level` |
| Every span and log | — | `session.id`, `session.previous_id`, `fusescope.rum.view.route`, `user.id`, `app.screen.name`, `network.*` |
| Span | `view` | `fusescope.rum.view.route`, `fusescope.rum.view.loading_type`, `fusescope.rum.view.navigation_type`; duration = time on screen |
| Span | `GET`, `POST`, … | `http.request.method`, `url.full`, `http.response.status_code`, `server.*` |
| Span | `AppStart` | `start.type` (upstream) |
| Log event | `exception` | `exception.type`, `exception.message`, `exception.stacktrace`, `fusescope.rum.error.source` (`crash`, `anr`, `manual`), `fusescope.rum.view.route`, `thread.*` |
| Log event | `rum.vital` | `fusescope.rum.vital.name=app_start`, `fusescope.rum.vital.value`, `fusescope.rum.vital.unit=ms`, `fusescope.rum.vital.rating`, `start.type` |
| Log event | `fusescope.rum.action` | `fusescope.rum.action.type=custom`, `fusescope.rum.action.name` |
| Log event | `session.start`, `session.end`, `network.change`, `device.screen_orientation` | upstream |

Every log record also carries its event name as the **`event.name`** attribute, because FuseScope's storage drops
the record's own event-name field.

### Contract assumptions for the backend

These are the SDK's side of phase 3; the backend may still rename them.

1. **Platform.** `fusescope.rum.platform=android` on the resource; the gateway overwrites it from the application.
2. **Package name.** `app.package.name` resource attribute (no semantic convention names one), equal to the
   Android `applicationId`, for the gateway's per-application package check. No `Origin` header is sent.
3. **Crash vs ANR.** Both are `exception` events; `fusescope.rum.error.source` is `crash` or `anr`, as it is
   `onerror` / `unhandledrejection` / `manual` on the web. ANRs have `exception.type = ApplicationNotResponding`.
4. **Release.** `service.version` = the upload's `release` (default `versionName` on both sides);
   `fusescope.rum.android.version_code` is informative.
5. **Stack traces** are raw Java traces (`\tat a.b.c(SourceFile:12)`). R8 names each obfuscated frame's file
   `r8-map-id-<pg_map_id>`, and the upload sends the same `map_id`, so the backend can pick the exact mapping
   even when two builds share a release.
6. **Mapping upload** (assumed, configurable with `uploadPath`):
   `POST <endpoint>/api/v1/rum/applications/{applicationId}/mappings?release=<release>`, header
   `SigNoz-Api-Key: <Editor API key>` (as for source maps; the public RUM key is not used), `multipart/form-data`
   with `release`, then the file part `file` (`filename="mapping.txt"`, `text/plain`), then
   `package_name`, `variant`, `version_code` and `map_id`. The server reads the first part after `release` as
   the mapping and answers 201 with `debugId` = `pg_map_id`; any 2xx is success.

## Gateway contract

- `POST <endpoint>/rum/v1/traces` and `POST <endpoint>/rum/v1/logs`, OTLP/HTTP **protobuf**
  (`application/x-protobuf`), `Content-Encoding: gzip`, sent chunked by OkHttp.
- The public key in the **`FuseScope-RUM-Key`** header.
- No CORS or `Origin`: requests come from the app, not a browser.

## Compatibility

| io.fuseone.fusescope:android | FuseScope server | OpenTelemetry Android | OpenTelemetry Java |
|---|---|---|---|
| 0.1.x | to be filled when the Android gateway ships | 1.7.0 | 1.65.0, instrumentation 2.31.1 |

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
adb shell am start -n io.fuseone.fusescope.sample/.MainActivity --es action error   # request, crash, anr, details, compose
```

The sample sends to `http://10.0.2.2:4399` (the host from the emulator); the stub writes the decoded OTLP
requests to `build/stub/*.jsonl` and the mapping uploads to `build/stub/mappings/`.

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
