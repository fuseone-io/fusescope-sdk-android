package io.fuseone.fusescope.android

internal data class NativeTombstone(
    val signal: String,
    val stack: String,
)

@Suppress("UNUSED_PARAMETER")
internal fun parseNativeTombstone(bytes: ByteArray): NativeTombstone? = null
