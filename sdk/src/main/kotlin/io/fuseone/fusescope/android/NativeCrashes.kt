package io.fuseone.fusescope.android

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import io.opentelemetry.android.session.Session
import io.opentelemetry.android.session.SessionObserver
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.resources.Resource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun startNativeCrashes(
    application: Application,
    config: ResolvedConfig,
    sessions: RumSessionManager,
    resource: Resource,
    exporter: LogRecordExporter,
): AutoCloseable? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !config.instrumentations.crashes) return null
    return runCatching {
        NativeCrashes(application, config, sessions, resource, exporter).also { it.start() }
    }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.R)
private class NativeCrashes(
    private val application: Application,
    private val config: ResolvedConfig,
    private val sessions: RumSessionManager,
    private val resource: Resource,
    private val exporter: LogRecordExporter,
) : AutoCloseable {
    private val activityManager = application.getSystemService(ActivityManager::class.java)
    private val process = Application.getProcessName()
    private val store = NativeSnapshots(application, process)
    private val token = "fsn1:${newSessionId()}"
    private val active = AtomicBoolean(true)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "FuseScope-native").apply { isDaemon = true } }

    fun start() {
        try {
            updateSummary()
            sessions.addObserver(
                object : SessionObserver {
                    override fun onSessionStarted(
                        newSession: Session,
                        previousSession: Session,
                    ) {
                        if (active.get()) runCatching { updateSummary() }
                    }

                    override fun onSessionEnded(session: Session) = Unit
                },
            )
            worker.execute {
                runCatching {
                    val exits =
                        activityManager
                            .getHistoricalProcessExitReasons(application.packageName, 0, 16)
                            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.processName == process }
                            .map { exit ->
                                NativeExit(exit.timestamp, exit.pid, exit.status, exit.processStateSummary?.decodeToString()) {
                                    exit.traceInputStream
                                }
                            }
                    NativeExitRecovery(config.applicationId, store) { crash ->
                        active.get() && exportNativeCrash(crash, resource, exporter, config.queryPolicy)
                    }.recover(exits)
                }
            }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun updateSummary() {
        val session = sessions.get()
        val snapshot =
            NativeExitContext(
                config.applicationId,
                session.id,
                session.sampled,
                resource.getAttribute(stringKey(ResourceAttr.SERVICE_VERSION)),
                resource.getAttribute(stringKey(ResourceAttr.SERVICE_NAME)),
                resource.getAttribute(stringKey(ResourceAttr.ENVIRONMENT)),
            )
        if (store.save(token, snapshot)) activityManager.setProcessStateSummary(token.toByteArray())
    }

    override fun close() {
        active.set(false)
        runCatching { activityManager.setProcessStateSummary(byteArrayOf()) }
        worker.shutdownNow()
    }
}
