package io.fuseone.fusescope.android

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.samplers.SamplingResult

/** Deterministic per session id, with the same rule as `@fusescope/browser`. */
internal fun isSampled(
    sessionId: String,
    rate: Double,
): Boolean {
    if (rate >= 1.0) return true
    if (rate <= 0.0) return false
    val head = sessionId.take(8).toLongOrNull(16) ?: return false
    return head.toDouble() / 0xffffffffL.toDouble() < rate
}

/** Samples whole sessions: every span of a session shares its decision. */
internal class SessionSampler(
    private val sampled: () -> Boolean,
) : Sampler {
    override fun shouldSample(
        parentContext: Context,
        traceId: String,
        name: String,
        spanKind: SpanKind,
        attributes: Attributes,
        parentLinks: List<LinkData>,
    ): SamplingResult = if (sampled()) SamplingResult.recordAndSample() else SamplingResult.drop()

    override fun getDescription(): String = "FuseScopeSessionSampler"
}

/**
 * An unsampled span would send `traceparent` with flags 00, and parent-based backend samplers would then
 * drop the server trace too. Unsampled sessions send no header, so the backend samples on its own.
 */
internal class SampledOnlyPropagator(
    private val inner: TextMapPropagator,
) : TextMapPropagator {
    override fun fields(): Collection<String> = inner.fields()

    override fun <C : Any?> inject(
        context: Context,
        carrier: C?,
        setter: TextMapSetter<C>,
    ) {
        if (!Span.fromContext(context).spanContext.isSampled) return
        inner.inject(context, carrier, setter)
    }

    override fun <C : Any?> extract(
        context: Context,
        carrier: C?,
        getter: TextMapGetter<C>,
    ): Context = inner.extract(context, carrier, getter)
}
