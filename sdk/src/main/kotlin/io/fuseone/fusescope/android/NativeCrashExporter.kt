package io.fuseone.fusescope.android

import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import java.util.concurrent.TimeUnit

private val NATIVE_SIGNAL_NAME = Regex("SIG[A-Z0-9]{1,24}")

/** Uses the historical resource/session without current user, view, or sampling processors. */
internal fun exportNativeCrash(
    crash: RecoveredNativeCrash,
    resource: Resource,
    exporter: LogRecordExporter,
    policy: QueryPolicy,
): Boolean {
    val previous =
        resource
            .toBuilder()
            .removeIf {
                it.key == ResourceAttr.SERVICE_VERSION || it.key == ResourceAttr.SERVICE_NAME || it.key == ResourceAttr.ENVIRONMENT
            }
    crash.context.release?.let { previous.put(ResourceAttr.SERVICE_VERSION, it) }
    crash.context.service?.let { previous.put(ResourceAttr.SERVICE_NAME, it) }
    crash.context.environment?.let { previous.put(ResourceAttr.ENVIRONMENT, it) }
    var result: CompletableResultCode? = null
    val borrowed =
        object : LogRecordExporter by exporter {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode = exporter.export(logs).also { result = it }

            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }
    val provider =
        SdkLoggerProvider
            .builder()
            .setResource(previous.build())
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(RumLogExporter(borrowed, policy, 1.0)))
            .build()
    try {
        val message = "Native process terminated by signal ${crash.exit.status}"
        val signal = crash.tombstone?.signal?.takeIf(NATIVE_SIGNAL_NAME::matches) ?: nativeSignal(crash.exit.status)
        provider
            .get(SDK_NAME)
            .logRecordBuilder()
            .setContext(Context.root())
            .setEventName(Events.EXCEPTION)
            .setTimestamp(crash.exit.timestamp, TimeUnit.MILLISECONDS)
            .setSeverity(Severity.FATAL)
            .setBody(message)
            .setAttribute(Attr.SESSION_ID, crash.context.sessionId)
            .setAttribute(Attr.ERROR_SOURCE, "native")
            .setAttribute(Attr.EXCEPTION_TYPE, signal)
            .setAttribute(Attr.EXCEPTION_MESSAGE, message)
            .setAttribute(Attr.EXCEPTION_STACKTRACE, crash.tombstone?.stack.orEmpty())
            .setAttribute(stringKey("fusescope.rum.native.exit_id"), "${crash.exit.timestamp}:${crash.exit.pid}")
            .emit()
        return result?.join(5, TimeUnit.SECONDS)?.isSuccess == true
    } finally {
        provider.shutdown()
    }
}

private fun nativeSignal(status: Int): String =
    when (status) {
        4 -> "SIGILL"
        5 -> "SIGTRAP"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        11 -> "SIGSEGV"
        31 -> "SIGSYS"
        else -> "NativeCrash"
    }
