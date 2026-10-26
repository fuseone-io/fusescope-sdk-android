package io.fuseone.fusescope.android

internal const val KEY_HEADER = "FuseScope-RUM-Key"

internal data class ResolvedConfig(
    val applicationId: String,
    val service: String?,
    val environment: String?,
    val release: String?,
    val tracesUrl: String,
    val logsUrl: String,
    val headers: Map<String, String>,
    val sessionSampleRate: Double,
    val queryPolicy: QueryPolicy,
    val hashUserId: Boolean,
    val installationId: Boolean,
    val session: SessionConfig,
    val instrumentations: InstrumentationsConfig,
    val requireConsent: Boolean,
    val diskBuffering: Boolean,
)

private val ABSOLUTE_HTTP_URL = Regex("^https?://[^/?#\\s]+[^?#\\s]*$", RegexOption.IGNORE_CASE)

internal fun resolveConfig(c: FuseScopeConfig): ResolvedConfig {
    require(c.applicationId.isNotBlank()) { "FuseScope: applicationId is required" }
    require(c.key.isNotBlank()) { "FuseScope: key is required" }
    require(ABSOLUTE_HTTP_URL.matches(c.endpoint)) {
        "FuseScope: endpoint must be an absolute http(s) URL without query or fragment, got \"${c.endpoint}\""
    }
    require(c.sessionSampleRate in 0.0..1.0) { "FuseScope: sessionSampleRate must be within [0, 1]" }
    require(c.session.inactivityTimeoutMillis > 0 && c.session.maxDurationMillis > 0) {
        "FuseScope: session timeouts must be positive"
    }
    val base = c.endpoint.trimEnd('/')
    return ResolvedConfig(
        applicationId = c.applicationId,
        service = c.service?.takeIf { it.isNotBlank() },
        environment = c.environment?.takeIf { it.isNotBlank() },
        release = c.release?.takeIf { it.isNotBlank() },
        tracesUrl = "$base/rum/v1/traces",
        logsUrl = "$base/rum/v1/logs",
        headers = mapOf(KEY_HEADER to c.key),
        sessionSampleRate = c.sessionSampleRate,
        queryPolicy = if (c.privacy.dropQuery) QueryPolicy.Allow(c.privacy.queryAllowlist.toSet()) else QueryPolicy.KeepAll,
        hashUserId = c.privacy.hashUserId,
        installationId = c.privacy.installationId,
        session = c.session,
        instrumentations = c.instrumentations,
        requireConsent = c.requireConsent,
        diskBuffering = c.diskBuffering,
    )
}
