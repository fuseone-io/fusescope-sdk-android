package io.fuseone.fusescope.android

/**
 * Configuration of [FuseScope.init].
 *
 * @property applicationId RUM application id (a UUID) from FuseScope's Setup screen.
 * @property key Public, write-only application key (`rum_pub_…`), sent in the `FuseScope-RUM-Key` header.
 * @property endpoint FuseScope base URL; data goes to `<endpoint>/rum/v1/traces` and `<endpoint>/rum/v1/logs`.
 * @property environment `deployment.environment.name`, e.g. `production`.
 * @property release `service.version`; defaults to the app's `versionName`. Must equal the release of the R8 mapping upload.
 * @property service `service.name`; unset, the FuseScope gateway names the service after the application.
 * @property sessionSampleRate Share of sessions recorded, 0 to 1. Unsampled sessions send nothing and no `traceparent`.
 * @property requireConsent Collect nothing until [FuseScope.setConsent] is called with `true`.
 */
public data class FuseScopeConfig
    @JvmOverloads
    constructor(
        val applicationId: String,
        val key: String,
        val endpoint: String,
        val environment: String? = null,
        val release: String? = null,
        val service: String? = null,
        val sessionSampleRate: Double = 1.0,
        val privacy: PrivacyConfig = PrivacyConfig(),
        val session: SessionConfig = SessionConfig(),
        val instrumentations: InstrumentationsConfig = InstrumentationsConfig(),
        val requireConsent: Boolean = false,
        val diskBuffering: Boolean = false,
    )

/**
 * @property dropQuery Drop query strings from URLs, span names, error messages and stack traces. Fragments are always dropped.
 * @property queryAllowlist Query parameters kept while [dropQuery] is on.
 * @property hashUserId [FuseScope.setUser] sends the hex SHA-256 of the id as `user.id` instead of the raw id.
 * @property installationId Send upstream's persistent `app.installation.id`.
 */
public data class PrivacyConfig
    @JvmOverloads
    constructor(
        val dropQuery: Boolean = true,
        val queryAllowlist: List<String> = emptyList(),
        val hashUserId: Boolean = true,
        val installationId: Boolean = false,
    )

/**
 * @property inactivityTimeoutMillis A session ends after the app spent this long in the background.
 * @property maxDurationMillis A session never lasts longer.
 */
public data class SessionConfig
    @JvmOverloads
    constructor(
        val inactivityTimeoutMillis: Long = 15 * 60_000L,
        val maxDurationMillis: Long = 4 * 3_600_000L,
    )

/**
 * Turns collection on or off by kind.
 *
 * @property views `view` spans from Activities and Fragments.
 * @property appStart The `app_start` vital, from upstream's `AppStart` span.
 * @property lifecycleSpans Upstream's Activity and Fragment lifecycle spans (`Created`, `Resumed`, `Paused`, …).
 * @property slowRendering Upstream slow and frozen frame reports.
 */
public data class InstrumentationsConfig
    @JvmOverloads
    constructor(
        val crashes: Boolean = true,
        val anrs: Boolean = true,
        val views: Boolean = true,
        val appStart: Boolean = true,
        val lifecycleSpans: Boolean = false,
        val slowRendering: Boolean = false,
    )
