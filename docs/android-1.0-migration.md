# Android 1.0 migration and compatibility

Status: preparation for [#189](https://github.com/fuseone-io/fusescope/issues/189).
The published SDK is `0.1.0-beta.3`; **1.0.0 is not published yet**. The commands below
prepare and verify the release candidate. They do not announce availability.

## Compatibility

| Component | Candidate baseline / evidence |
|---|---|
| Android SDK, Compose, mappings plugin and plugin marker | One aligned release version: `1.0.0` |
| Build toolchain | JDK 21, Gradle wrapper 9.7.1, AGP 9.4.0, Kotlin 2.4.10, compile SDK 37 |
| Runtime | SDK/Compose minSdk 23; sample minSdk 26; real acceptance on emulator API 35 |
| OpenTelemetry | Android 1.7.0 (including its 1.7.0-alpha modules), Java 1.65.0, instrumentation 2.31.1 |
| Compose | Sample BOM 2026.09.00; no promise of compatibility with other compiler/toolchain combinations |

MinSdk is an API floor, not an acceptance matrix: API 23–34 and 36+ have not been
validated by the recorded emulator run. Below API 26, configure core-library desugaring.
Keep `android.useFullClasspathForDexingTransform=true` as in the repository properties.
Native recovery requires API 30 for exit metadata and API 31 for available tombstone
frames. ELF/DWARF symbolication remains separate work (#188/#191).

| Backend feature | Requirement and verified status |
|---|---|
| Existing Android sessions, views, errors and per-build mappings | Existing beta.3 contract: server 0.156.0+; see README for older beta constraints |
| Native frames and grouping | Server 0.159.0-beta.2; accepted with published Maven beta.3 |
| App-start storage | Accepted with published Maven beta.3 and server 0.159.0-beta.2 |
| App-start session timeline | Backend containing [PR #313](https://github.com/fuseone-io/fusescope/pull/313); accepted from source, **no released minimum version assigned yet** |

The full [source-build acceptance](https://github.com/fuseone-io/fusescope-sdk-android/actions/runs/41763544558)
passed sessions, consent/sampling, Compose, privacy/propagation, native/Java/ANR/R8 and
four UI checks. It used published beta.3, not published 1.0 artifacts. Replace the
pending backend minimum with the actual release containing #313 and record a final
1.0 acceptance run before closing #189. Do not infer that every server numerically
newer than beta.2 contains #313.

## Collector migration (backend 0.159.0+)

The new Apache-2.0 `fusescope-collector` distribution (repo
`fusescope-collector-server`) owns `/rum/v1/traces` and `/rum/v1/logs`. Point
`FuseScopeConfig.endpoint` at its HTTPS ingestion URL; those backend routes are removed.
The paths, public RUM key/header, protobuf/gzip payloads and Android package rules remain
unchanged. Collector policy and setup reports travel over authenticated TLS OpAMP.
A private CA needs explicit app trust; keep hostname/certificate verification enabled.

API reads/Errors UI remain on the backend URL. The R8 mappings plugin still targets the
backend management API with an Editor key while #234's artifact-ingress migration is
pending; configure its endpoint separately. Do not send this Editor key to RUM ingest
or assume artifacts-only upload exists merely because RUM moved. Once #234 lands,
change the plugin upload endpoint/auth under its coordinated contract.

The acceptance workflow defaults to collector mode and backend 0.159.0/collector 0.1.0,
records the actual collector image identity, and refuses collector mode with legacy
backend fixtures. The old beta.2 baseline requires explicit backend ref/image and
`collector_ingress=false`. Strict app-start timeline still requires a source/release
containing #313. Historical backend-gateway evidence does not establish collector acceptance.

### Consent withdrawal correction

Published beta.3 can intermittently close a TLS socket on main during withdrawal,
throwing `NetworkOnMainThreadException` ([#336](https://github.com/fuseone-io/fusescope/issues/336)).
The 1.0 source candidate moves exporter shutdown onto workers and preserves completion/
failure results while leaving lifecycle cleanup unchanged. The source candidate passed
five consent cycles and full real-collector/UI acceptance; the fix is not yet in a
published SDK. Withdrawal/shutdown still flush pending data and should be invoked off
main. Merely moving the caller off main did not avoid the upstream callback race.

### Older Android transport gate

The library's minSdk remains 23. A direct connection to the collector's TLS-1.3-only
listener needs a TLS-1.3-capable provider; Android's default provider supports it from
API 29 ([official protocol matrix](https://developer.android.com/reference/javax/net/ssl/SSLSocket)).
API 23–28 with the stock provider therefore cannot directly use that listener. API 35
acceptance does not establish transport compatibility for that older range.

Preserving older-device support needs an explicit approved transport strategy and
on-device tests before the 1.0 compatibility claim is complete. No TLS downgrade,
new provider dependency or minSdk change is made in this work. Record the decision
under #189/#235; do not infer support from a successful compilation at minSdk 23.

## Migrating from beta.3

1. When 1.0 is published, update SDK, optional Compose and optional mappings plugin
   together. Keep the plugin marker and implementation at the same version; resolve
   plugins from Maven Central in `pluginManagement.repositories`.
2. Keep the existing facade, Compose APIs, constructor parameters and defaults.
   No event protocol or backend data migration is required. Existing beta.3 data
   remains usable; updating the backend supplies app-start timeline projection.
3. Remove embedded credentials from `endpoint`. Use the existing public RUM `key`
   for ingestion. Malformed endpoints and URLs with userinfo are ignored with a
   fixed diagnostic; diagnostics no longer echo supplied URL secrets.
4. Review propagation when migrating from beta.1: beta.2+ defaults to the endpoint
   host. Set `traceparentHosts` to your API hosts; an empty list disables propagation.
   Unsampled sessions send neither events nor trace context.
5. Preserve your consent policy. Sampling remains 1.0 by default; collection does not
   require explicit consent unless configured. With consent required, grant starts
   collection; withdrawal stops new collection and flushes previous data, without
   retracting prior exports. A later grant resumes it.
6. Preserve privacy/session defaults: hashed user IDs, no installation ID, stripped
   URL queries, no disk buffering, 15-minute background timeout and 4-hour maximum.
   Match application/release and the real R8 map ID when uploading mappings. Keep
   the Editor upload secret in CI; it is distinct from the public ingestion key.

The facade exposes OpenTelemetry/OkHttp types; use the pinned dependency line and
revalidate consumers before overriding those versions. Internal classes and generated
helpers are not supported extension points.

## Verify all four artifacts without source substitution

CI publishes a **local-only candidate** with `VERSION_NAME=1.0.0` into a temporary Maven
repository, then runs the isolated consumer below. This does not publish to Central
or change the development version in `gradle.properties`.

After actual Central publication, run from the SDK checkout with JDK 21/Android SDK 37:

```bash
python3 scripts/release_consumer.py --version 1.0.0
```

The verifier downloads and checks these POMs, including aligned transitive references:

- `io.fuseone.fusescope:android:1.0.0`
- `io.fuseone.fusescope:android-compose:1.0.0`
- `io.fuseone.fusescope:android-mappings-gradle-plugin:1.0.0`
- `io.fuseone.fusescope.android.mappings:io.fuseone.fusescope.android.mappings.gradle.plugin:1.0.0`

It generates a separate sample build with no included plugin build or SDK/Compose
project dependencies, resolves FuseScope coordinates exclusively from the selected
repository, builds the minified release, and checks runtime/plugin dependency reports.
Evidence is written under `build/release-consumer/` (`acceptance.json`,
`dependencies.txt`, `buildEnvironment.txt`). CI uploads the candidate reports as
`release-consumer-candidate`. Local repository success proves packaging and consumption;
Central availability and final real-backend 1.0 acceptance are separate release gates.

## Release completion

Merge SDK PR #9 and backend PR #313; publish a backend containing the latter. Then
record its exact version/source/digest, update this table and the public Android guide,
publish aligned 1.0 artifacts with the existing manual release workflow, run the
Central consumer verifier, and dispatch full strict acceptance against those published
SDK/backend versions. Archive versions, dependency reports, acceptance JSON and screenshots.
Keep #189 open until the published artifacts and acceptance evidence are available.
