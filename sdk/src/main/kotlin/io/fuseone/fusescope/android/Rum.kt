package io.fuseone.fusescope.android

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import io.opentelemetry.android.AndroidResource
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.RumBuilder
import io.opentelemetry.android.config.OtelRumConfig
import io.opentelemetry.android.features.diskbuffering.DiskBufferingConfig
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.instrumentation.okhttp.v3_0.OkHttpTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.export.SpanExporter
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class Exporters(
    val spans: SpanExporter,
    val logs: LogRecordExporter,
)

/** A running FuseScope SDK: upstream OpenTelemetry Android wired to the FuseScope data model. */
internal class Rum private constructor(
    private val application: Application,
    private val config: ResolvedConfig,
    private val otelRum: OpenTelemetryRum,
    private val sdk: OpenTelemetrySdk,
    private val state: RumState,
    private val autoViews: AutoViews,
) {
    val openTelemetry: OpenTelemetry get() = otelRum.openTelemetry
    private val logger: Logger = otelRum.openTelemetry.logsBridge.get(SDK_NAME)
    private val okHttp by lazy { OkHttpTelemetry.create(otelRum.openTelemetry) }

    fun setUser(id: String?) {
        state.userId = userId(id, config.hashUserId)
    }

    fun startView(route: String) = autoViews.startManual(route)

    fun addError(
        throwable: Throwable,
        attributes: Attributes,
    ) {
        logger
            .logRecordBuilder()
            .setEventName(Events.EXCEPTION)
            .setSeverity(Severity.ERROR)
            .setBody(throwable.message.orEmpty())
            .setAllAttributes(attributes)
            .setAttribute(Attr.EXCEPTION_TYPE, throwable.javaClass.name)
            .apply { throwable.message?.let { setAttribute(Attr.EXCEPTION_MESSAGE, it) } }
            .setAttribute(Attr.EXCEPTION_STACKTRACE, throwable.stackTraceToString())
            .setAttribute(Attr.ERROR_SOURCE, ErrorSource.MANUAL)
            .emit()
    }

    fun addAction(
        name: String,
        attributes: Attributes,
    ) {
        logger
            .logRecordBuilder()
            .setEventName(Events.ACTION)
            .setSeverity(Severity.INFO)
            .setAllAttributes(attributes)
            .setAttribute(Attr.ACTION_TYPE, "custom")
            .setAttribute(Attr.ACTION_NAME, name)
            .emit()
    }

    fun callFactory(client: OkHttpClient): Call.Factory = okHttp.createCallFactory(client)

    fun flush(timeoutMillis: Long = FLUSH_TIMEOUT_MS) {
        sdk.sdkTracerProvider.forceFlush().join(timeoutMillis, TimeUnit.MILLISECONDS)
        sdk.sdkLoggerProvider.forceFlush().join(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Flushes on the calling thread; upstream's shutdown removes lifecycle observers, so it runs on the main thread. */
    fun shutdown() {
        application.unregisterActivityLifecycleCallbacks(autoViews)
        flush()
        if (Looper.myLooper() == Looper.getMainLooper()) otelRum.shutdown() else Handler(Looper.getMainLooper()).post(otelRum::shutdown)
    }

    companion object {
        private const val FLUSH_TIMEOUT_MS = 5_000L

        fun start(
            application: Application,
            config: ResolvedConfig,
            exporters: Exporters = otlpExporters(config),
        ): Rum {
            val sessions =
                RumSessionManager(config.session, config.sessionSampleRate, sharedPreferencesStore(application))
            val state = RumState(sessions)
            val processStart = processStartEpochNanos()
            val vitalsLogger = AtomicReference<Logger?>()
            val sdk = AtomicReference<OpenTelemetrySdk>()
            val otelRum =
                RumBuilder
                    .builder(application, rumConfig(config))
                    .setSessionProvider(sessions)
                    .setResource(resource(application, config))
                    .addPropagatorCustomizer { SampledOnlyPropagator(W3CTraceContextPropagator.getInstance()) }
                    .addTracerProviderCustomizer { builder, _ ->
                        builder
                            .setSampler(SessionSampler { sessions.get().sampled })
                            .addSpanProcessor(
                                RumSpanProcessor(state) { span ->
                                    val logger = vitalsLogger.get()
                                    if (config.instrumentations.appStart && logger != null) emitAppStartVital(logger, span, processStart)
                                },
                            )
                    }.addLoggerProviderCustomizer { builder, _ -> builder.addLogRecordProcessorFirst(RumLogProcessor(state)) }
                    .addSpanExporterCustomizer {
                        RumSpanExporter(
                            exporters.spans,
                            config.queryPolicy,
                            config.instrumentations.lifecycleSpans,
                        )
                    }.addLogRecordExporterCustomizer { RumLogExporter(exporters.logs, config.queryPolicy, config.sessionSampleRate) }
                    .addOtelReadyListener { sdk.set(it as OpenTelemetrySdk) }
                    .build()
            vitalsLogger.set(otelRum.openTelemetry.logsBridge.get(SDK_NAME))
            val tracer = otelRum.openTelemetry.getTracer(SDK_NAME, BuildConfig.SDK_VERSION)
            val autoViews = AutoViews(ViewController(tracer, state), sessions, config.instrumentations.views)
            application.registerActivityLifecycleCallbacks(autoViews)
            return Rum(application, config, otelRum, sdk.get(), state, autoViews)
        }

        private fun processStartEpochNanos(): Long? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
            val sinceStart = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
            return TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis() - sinceStart)
        }

        private fun otlpExporters(c: ResolvedConfig): Exporters {
            val spans = OtlpHttpSpanExporter.builder().setEndpoint(c.tracesUrl).setCompression("gzip")
            val logs = OtlpHttpLogRecordExporter.builder().setEndpoint(c.logsUrl).setCompression("gzip")
            c.headers.forEach { (k, v) ->
                spans.addHeader(k, v)
                logs.addHeader(k, v)
            }
            return Exporters(spans.build(), logs.build())
        }

        private fun rumConfig(c: ResolvedConfig): OtelRumConfig =
            OtelRumConfig().apply {
                setMetricsDisabled()
                disableSdkInitializationEvents()
                suppressInstrumentation("startup")
                if (!c.instrumentations.crashes) suppressInstrumentation("crash")
                if (!c.instrumentations.anrs) suppressInstrumentation("anr")
                if (!c.instrumentations.appStart && !c.instrumentations.lifecycleSpans) suppressInstrumentation("activity")
                if (!c.instrumentations.lifecycleSpans) suppressInstrumentation("fragment")
                if (!c.instrumentations.slowRendering) suppressInstrumentation("slowrendering")
                setDiskBufferingConfig(DiskBufferingConfig.create(enabled = c.diskBuffering))
            }

        internal fun resource(
            application: Application,
            c: ResolvedConfig,
        ): Resource {
            val b = AndroidResource.createDefault(application).toBuilder()
            if (!c.installationId) b.removeIf { it.key == ResourceAttr.INSTALLATION_ID }
            b.put(ResourceAttr.SERVICE_NAME, c.service)
            c.release?.let { b.put(ResourceAttr.SERVICE_VERSION, it) }
            c.environment?.let { b.put(ResourceAttr.ENVIRONMENT, it) }
            versionCode(application)?.let { b.put(ResourceAttr.VERSION_CODE, it) }
            return b
                .put(ResourceAttr.PLATFORM, PLATFORM_ANDROID)
                .put(ResourceAttr.APPLICATION_ID, c.applicationId)
                .put(ResourceAttr.PACKAGE_NAME, application.packageName)
                .put(ResourceAttr.DISTRO_NAME, SDK_NAME)
                .put(ResourceAttr.DISTRO_VERSION, BuildConfig.SDK_VERSION)
                .build()
        }

        @Suppress("DEPRECATION")
        private fun versionCode(application: Application): Long? =
            try {
                val info = application.packageManager.getPackageInfo(application.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
    }
}
