package io.fuseone.fusescope.android

import java.io.ByteArrayOutputStream
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
    fun recover(exits: List<NativeExit>) {
        val ordered = exits.take(16).sortedBy { it.timestamp }
        for (exit in ordered) {
            val summary = exit.summary ?: continue
            val context = store.context(summary) ?: continue
            if (context.applicationId != applicationId || !context.sampled || context.sessionId.length != 32) continue
            if (exit.timestamp <= 0 || exit.pid <= 0) continue
            val id = "${exit.timestamp}:${exit.pid}"
            if (store.reported(id)) continue
            val crash = RecoveredNativeCrash(context, exit, readNativeTrace(exit))
            val accepted =
                try {
                    report(crash)
                } catch (_: Exception) {
                    false
                }
            if (accepted) store.markReported(id)
        }
    }
}

private const val MAX_NATIVE_TRACE_BYTES = 4 * 1024 * 1024

private fun readNativeTrace(exit: NativeExit): NativeTombstone? =
    try {
        val bytes =
            exit.trace()?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_NATIVE_TRACE_BYTES) {
                    val size = minOf(buffer.size, MAX_NATIVE_TRACE_BYTES + 1 - output.size())
                    val count = input.read(buffer, 0, size)
                    if (count < 0) break
                    if (count == 0) {
                        val one = input.read()
                        if (one < 0) break
                        output.write(one)
                    } else {
                        output.write(buffer, 0, count)
                    }
                }
                if (output.size() > MAX_NATIVE_TRACE_BYTES) null else output.toByteArray()
            }
        bytes?.let(::parseNativeTombstone)
    } catch (_: Exception) {
        null
    }
