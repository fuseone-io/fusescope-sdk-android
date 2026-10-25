package io.fusescope.android

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.sdk.trace.samplers.SamplingDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {
    @Test
    fun `isSampled compares the first 32 bits of the id with the rate`() {
        assertTrue(isSampled("00000000aaaaaaaaaaaaaaaaaaaaaaaa", 0.01))
        assertFalse(isSampled("ffffffffaaaaaaaaaaaaaaaaaaaaaaaa", 0.99))
        assertTrue(isSampled("7fffffff000000000000000000000000", 0.51))
        assertFalse(isSampled("80000000000000000000000000000000", 0.49))
        assertTrue(isSampled("not-hex", 1.0))
        assertFalse(isSampled("00000000000000000000000000000000", 0.0))
    }

    @Test
    fun `SessionSampler follows the session decision`() {
        var sampled = true
        val sampler = SessionSampler { sampled }
        val sample = {
            sampler
                .shouldSample(
                    Context.root(),
                    "0".repeat(32),
                    "view",
                    io.opentelemetry.api.trace.SpanKind.INTERNAL,
                    io.opentelemetry.api.common.Attributes
                        .empty(),
                    emptyList(),
                ).decision
        }
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, sample())
        sampled = false
        assertEquals(SamplingDecision.DROP, sample())
    }

    @Test
    fun `SampledOnlyPropagator injects traceparent for sampled spans only`() {
        val propagator = SampledOnlyPropagator(W3CTraceContextPropagator.getInstance())
        val setter = TextMapSetter<MutableMap<String, String>> { carrier, key, value -> carrier!![key] = value }

        fun inject(flags: TraceFlags): Map<String, String> {
            val sc = SpanContext.create("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", flags, TraceState.getDefault())
            return mutableMapOf<String, String>().also { propagator.inject(Context.root().with(Span.wrap(sc)), it, setter) }
        }
        assertEquals(
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
            inject(TraceFlags.getSampled())["traceparent"],
        )
        assertTrue(inject(TraceFlags.getDefault()).isEmpty())
    }
}
