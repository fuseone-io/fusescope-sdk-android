package io.fusescope.android

import java.security.MessageDigest

internal fun sha256Hex(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** `user.id` of [id]: its hex SHA-256 unless [hash] is false; `null` clears it. */
internal fun userId(
    id: String?,
    hash: Boolean,
): String? = id?.let { if (hash) sha256Hex(it) else it }
