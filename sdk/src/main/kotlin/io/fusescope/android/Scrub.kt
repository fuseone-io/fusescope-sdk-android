package io.fusescope.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

internal sealed interface QueryPolicy {
    data object KeepAll : QueryPolicy

    data class Allow(
        val names: Set<String>,
    ) : QueryPolicy
}

private val URL_ATTRIBUTES = setOf("url.full", "url.original", "http.url", "http.target")
private val URL_QUERY = AttributeKey.stringKey("url.query")
private val URL_FRAGMENT = AttributeKey.stringKey("url.fragment")
private val URL_IN_TEXT = Regex("""((?:https?://|/)[^\s?#]*)[?#]\S*""")
private val URL_IN_STACK = Regex("""((?:https?://|/)[^\s?#()]*)[?#][^\s()]*?(:\d+(?::\d+)?)?(?=[\s)]|$)""")

/** Drops the fragment and every query parameter not allowed by [policy]. */
internal fun scrubUrl(
    raw: String,
    policy: QueryPolicy,
): String {
    val noFragment = raw.substringBefore('#')
    if (policy is QueryPolicy.KeepAll) return noFragment
    val names = (policy as QueryPolicy.Allow).names
    val base = noFragment.substringBefore('?')
    if (base.length == noFragment.length) return base
    val kept = keptParams(noFragment.substringAfter('?'), names)
    return if (kept.isEmpty()) base else "$base?$kept"
}

private fun keptParams(
    query: String,
    names: Set<String>,
): String =
    query
        .split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') in names }
        .joinToString("&")

/** Strips query strings and fragments from URLs and paths inside free text such as span names and messages. */
internal fun scrubText(
    text: String,
    policy: QueryPolicy,
): String = if (policy is QueryPolicy.KeepAll) text else text.replace(URL_IN_TEXT, "$1")

/** Like [scrubText], keeping the `:line:column` that follows a URL in a stack frame. */
internal fun scrubStack(
    stack: String,
    policy: QueryPolicy,
): String = if (policy is QueryPolicy.KeepAll) stack else stack.replace(URL_IN_STACK, "$1$2")

/** Returns [attrs] with URL attributes scrubbed, or the same instance when nothing changed. */
internal fun scrubAttributes(
    attrs: Attributes,
    policy: QueryPolicy,
): Attributes {
    var builder: io.opentelemetry.api.common.AttributesBuilder? = null
    attrs.forEach { key, value ->
        if (value !is String) return@forEach
        val scrubbed =
            when {
                key == URL_FRAGMENT -> null
                key == URL_QUERY -> scrubUrl("?$value", policy).removePrefix("?").ifEmpty { null }
                key.key in URL_ATTRIBUTES -> scrubUrl(value, policy)
                else -> return@forEach
            }
        if (scrubbed == value) return@forEach
        val b = builder ?: attrs.toBuilder().also { builder = it }

        @Suppress("UNCHECKED_CAST")
        val stringKey = key as AttributeKey<String>
        if (scrubbed == null) b.remove(stringKey) else b.put(stringKey, scrubbed)
    }
    return builder?.build() ?: attrs
}
