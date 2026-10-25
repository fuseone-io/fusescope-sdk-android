package io.fusescope.android

import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScrubTest {
    private val drop = QueryPolicy.Allow(emptySet())

    @Test
    fun `scrubUrl drops fragments and query parameters not allowed`() {
        val cases =
            listOf(
                Triple("https://a.io/p?token=s&page=2#x", drop, "https://a.io/p"),
                Triple("https://a.io/p?token=s&page=2#x", QueryPolicy.Allow(setOf("page")), "https://a.io/p?page=2"),
                Triple("https://a.io/p?token=s", QueryPolicy.Allow(setOf("page")), "https://a.io/p"),
                Triple("https://a.io/p?token=s#frag", QueryPolicy.KeepAll, "https://a.io/p?token=s"),
                Triple("/orders/1?x=1", drop, "/orders/1"),
                Triple("https://a.io/p", drop, "https://a.io/p"),
            )
        for ((raw, policy, want) in cases) assertEquals(raw, want, scrubUrl(raw, policy))
    }

    @Test
    fun `scrubText strips queries from URLs inside text`() {
        assertEquals(
            "GET https://a.io/users failed, see /help",
            scrubText("GET https://a.io/users?email=a@b.c failed, see /help#faq", drop),
        )
        assertEquals("x https://a.io/u?e=1", scrubText("x https://a.io/u?e=1", QueryPolicy.KeepAll))
    }

    @Test
    fun `scrubStack keeps line and column after a URL`() {
        assertEquals(
            "at load (https://a.io/app.js:10:5)",
            scrubStack("at load (https://a.io/app.js?v=secret:10:5)", drop),
        )
        val java = "java.lang.IllegalStateException: boom\n\tat com.example.Foo.bar(Foo.kt:12)"
        assertEquals(java, scrubStack(java, drop))
    }

    @Test
    fun `scrubAttributes scrubs URL attributes only`() {
        val attrs =
            Attributes
                .builder()
                .put("url.full", "https://api.io/v1/users?email=a@b.c")
                .put("url.query", "email=a@b.c&page=2")
                .put("url.fragment", "top")
                .put("http.request.method", "GET")
                .put("http.response.status_code", 200L)
                .build()
        val out = scrubAttributes(attrs, QueryPolicy.Allow(setOf("page")))
        assertEquals("https://api.io/v1/users", out.get(stringKey("url.full")))
        assertEquals("page=2", out.get(stringKey("url.query")))
        assertNull(out.get(stringKey("url.fragment")))
        assertEquals("GET", out.get(stringKey("http.request.method")))
        assertEquals(200L, out.get(longKey("http.response.status_code")))
    }

    @Test
    fun `scrubAttributes returns the same instance when clean`() {
        val attrs = Attributes.of(stringKey("url.full"), "https://api.io/v1/users")
        assertSame(attrs, scrubAttributes(attrs, drop))
    }
}
