package io.fuseone.fusescope.android

import java.io.InputStream

internal data class NativeExitContext(
    val applicationId: String,
    val sessionId: String,
    val sampled: Boolean,
    val release: String? = null,
    val service: String? = null,
    val environment: String? = null,
)

internal data class NativeExit(
    val timestamp: Long,
    val pid: Int,
    val status: Int,
    val summary: String?,
    val trace: () -> InputStream? = { null },
)

internal data class RecoveredNativeCrash(
    val context: NativeExitContext,
    val exit: NativeExit,
    val tombstone: NativeTombstone?,
)

internal interface NativeHistoryStore {
    fun context(summary: String): NativeExitContext?

    fun reported(id: String): Boolean

    fun markReported(id: String)
}

internal class NativeExitRecovery(
    private val applicationId: String,
    private val store: NativeHistoryStore,
    private val report: (RecoveredNativeCrash) -> Boolean,
) {
    @Suppress("UNUSED_PARAMETER")
    fun recover(exits: List<NativeExit>) = Unit
}
