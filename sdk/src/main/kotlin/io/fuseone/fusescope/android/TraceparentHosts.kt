package io.fuseone.fusescope.android

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.instrumentation.okhttp.v3_0.OkHttpTelemetry
import okhttp3.Call
import okhttp3.OkHttpClient

private val HOST_PATTERN = Regex("""^(\*|(\*\.)?[a-z0-9-]+(\.[a-z0-9-]+)*|\[[0-9a-f:.]+])$""", RegexOption.IGNORE_CASE)

/** Hosts that receive `traceparent`: exact (`api.example.com`), subdomains only (`*.example.com`) or `*`. */
internal class TraceparentHosts(
    patterns: Collection<String>,
) {
    private val any = "*" in patterns
    private val exact = patterns.filterNot { it.startsWith("*") }.map(::normalize).toSet()
    private val suffixes = patterns.filter { it.startsWith("*.") }.map { normalize(it.substring(1)) }

    fun allows(host: String): Boolean {
        if (any) return true
        val h = normalize(host)
        return h in exact || suffixes.any(h::endsWith)
    }

    companion object {
        fun isValid(pattern: String): Boolean = HOST_PATTERN.matches(pattern)

        private fun normalize(host: String): String = host.removePrefix("[").removeSuffix("]").lowercase()
    }
}

/** Traces every request; only requests to [hosts] carry `traceparent`. */
internal fun traceparentCallFactory(
    openTelemetry: OpenTelemetry,
    hosts: TraceparentHosts,
    client: OkHttpClient,
): Call.Factory {
    val propagating = OkHttpTelemetry.create(openTelemetry).createCallFactory(client)
    val silent = OkHttpTelemetry.create(WithoutPropagation(openTelemetry)).createCallFactory(client)
    return Call.Factory { request -> (if (hosts.allows(request.url.host)) propagating else silent).newCall(request) }
}

private class WithoutPropagation(
    private val delegate: OpenTelemetry,
) : OpenTelemetry {
    override fun getTracerProvider(): TracerProvider = delegate.tracerProvider

    override fun getMeterProvider(): MeterProvider = delegate.meterProvider

    override fun getPropagators(): ContextPropagators = ContextPropagators.noop()
}
