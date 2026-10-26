package io.fuseone.fusescope.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedConfigTest {
    private val base = FuseScopeConfig(applicationId = "app-1", key = "rum_pub_x", endpoint = "https://fusescope.example.com/")

    @Test
    fun `derives gateway URLs, key header and defaults`() {
        val c = resolveConfig(base)
        assertEquals("https://fusescope.example.com/rum/v1/traces", c.tracesUrl)
        assertEquals("https://fusescope.example.com/rum/v1/logs", c.logsUrl)
        assertEquals(mapOf("FuseScope-RUM-Key" to "rum_pub_x"), c.headers)
        assertNull(c.service)
        assertEquals(1.0, c.sessionSampleRate, 0.0)
        assertEquals(QueryPolicy.Allow(emptySet()), c.queryPolicy)
        assertEquals(true, c.hashUserId)
        assertEquals(15 * 60_000L, c.session.inactivityTimeoutMillis)
        assertEquals(4 * 3_600_000L, c.session.maxDurationMillis)
    }

    @Test
    fun `keeps a path prefix on the endpoint`() {
        val c = resolveConfig(base.copy(endpoint = "http://10.0.2.2:4399/fusescope"))
        assertEquals("http://10.0.2.2:4399/fusescope/rum/v1/logs", c.logsUrl)
    }

    @Test
    fun `dropQuery false keeps every parameter`() {
        assertEquals(QueryPolicy.KeepAll, resolveConfig(base.copy(privacy = PrivacyConfig(dropQuery = false))).queryPolicy)
    }

    @Test
    fun `traceparent goes to the endpoint's host by default`() {
        val hosts = resolveConfig(base).traceparentHosts
        assertTrue(hosts.allows("fusescope.example.com"))
        assertTrue(hosts.allows("FuseScope.Example.com"))
        assertFalse(hosts.allows("api.example.com"))
        assertTrue(resolveConfig(base.copy(endpoint = "http://[::1]:4399")).traceparentHosts.allows("::1"))
    }

    @Test
    fun `traceparentHosts matches exact hosts, subdomain wildcards and star`() {
        val hosts = resolveConfig(base.copy(traceparentHosts = listOf("api.example.com", "*.cdn.example.com", "[::1]"))).traceparentHosts
        assertTrue(hosts.allows("API.example.com"))
        assertTrue(hosts.allows("eu.cdn.example.com"))
        assertTrue(hosts.allows("a.eu.cdn.example.com"))
        assertTrue(hosts.allows("::1"))
        assertFalse(hosts.allows("cdn.example.com"))
        assertFalse(hosts.allows("evilapi.example.com"))
        assertFalse(hosts.allows("fusescope.example.com"))
        assertTrue(resolveConfig(base.copy(traceparentHosts = listOf("*"))).traceparentHosts.allows("anything.io"))
        assertFalse(resolveConfig(base.copy(traceparentHosts = emptyList())).traceparentHosts.allows("fusescope.example.com"))
    }

    @Test
    fun `rejects invalid configuration`() {
        val invalid =
            listOf(
                base.copy(applicationId = ""),
                base.copy(key = " "),
                base.copy(endpoint = "fusescope.example.com"),
                base.copy(endpoint = "https://fusescope.example.com/?a=1"),
                base.copy(sessionSampleRate = 1.5),
                base.copy(sessionSampleRate = Double.NaN),
                base.copy(session = SessionConfig(inactivityTimeoutMillis = 0)),
                base.copy(traceparentHosts = listOf("https://api.example.com")),
                base.copy(traceparentHosts = listOf("api.example.com/v1")),
                base.copy(traceparentHosts = listOf("api.example.com:443")),
                base.copy(traceparentHosts = listOf("")),
                base.copy(traceparentHosts = listOf("*.")),
                base.copy(traceparentHosts = listOf("api*.example.com")),
            )
        for (c in invalid) assertThrows(c.toString(), IllegalArgumentException::class.java) { resolveConfig(c) }
    }
}
