package io.fuseone.fusescope.android

import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCrashExporterTest {
    @Test
    fun `exports the previous release session and time without closing the shared exporter`() {
        val sink = InMemoryLogRecordExporter.create()
        val context = NativeExitContext("app-native", "b".repeat(32), true, "1.0", environment = "old-environment")
        val exit = NativeExit(1700000000000, 17, 11, null)
        val crash = RecoveredNativeCrash(context, exit, NativeTombstone("SIGSEGV", "#00 pc 42 /data/app/libshop.so"))
        val resource =
            Resource.create(
                Attributes.builder().put("service.version", "2.0").put("deployment.environment.name", "new-environment").build(),
            )
        assertTrue(exportNativeCrash(crash, resource, sink, QueryPolicy.Allow(emptySet())))
        val log = sink.finishedLogRecordItems.single()
        assertEquals("1.0", log.resource.attributes.get(stringKey("service.version")))
        assertEquals("old-environment", log.resource.attributes.get(stringKey("deployment.environment.name")))
        assertEquals("b".repeat(32), log.attributes.get(Attr.SESSION_ID))
        assertEquals("native", log.attributes.get(Attr.ERROR_SOURCE))
        assertEquals("SIGSEGV", log.attributes.get(Attr.EXCEPTION_TYPE))
        assertEquals(Severity.FATAL, log.severity)
        assertEquals(1700000000000L * 1000000, log.timestampEpochNanos)
        assertFalse(log.attributes.asMap().containsKey(Attr.USER_ID))
        assertTrue(exportNativeCrash(crash, resource, sink, QueryPolicy.Allow(emptySet())))
        assertEquals(2, sink.finishedLogRecordItems.size)
    }
}
