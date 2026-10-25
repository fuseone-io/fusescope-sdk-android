package io.fuseone.fusescope.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserTest {
    @Test
    fun `hashes the id with SHA-256 like the browser SDK`() {
        assertEquals("a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3", userId("123", hash = true))
        assertEquals("123", userId("123", hash = false))
        assertNull(userId(null, hash = true))
    }
}
