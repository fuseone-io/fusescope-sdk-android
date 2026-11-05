package io.fuseone.fusescope.android

import io.opentelemetry.sdk.common.CompletableResultCode
import kotlin.concurrent.thread

/** A completed batch future can invoke shutdown on main; closing a TLS socket performs network I/O. */
internal fun shutdownExporter(close: () -> CompletableResultCode): CompletableResultCode {
    val result = CompletableResultCode()
    thread(name = "FuseScope-exporter-shutdown", isDaemon = true) {
        try {
            val completion = close()
            completion.whenComplete {
                if (completion.isSuccess) result.succeed() else result.fail()
            }
        } catch (_: Exception) {
            result.fail()
        }
    }
    return result
}
