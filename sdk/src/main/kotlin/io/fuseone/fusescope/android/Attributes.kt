package io.fuseone.fusescope.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.AttributeKey.doubleKey
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey

internal object Attr {
    val SESSION_ID: AttributeKey<String> = stringKey("session.id")
    val SESSION_PREVIOUS_ID: AttributeKey<String> = stringKey("session.previous_id")
    val USER_ID: AttributeKey<String> = stringKey("user.id")
    val EVENT_NAME: AttributeKey<String> = stringKey("event.name")
    val VIEW_ROUTE: AttributeKey<String> = stringKey("fusescope.rum.view.route")
    val VIEW_LOADING_TYPE: AttributeKey<String> = stringKey("fusescope.rum.view.loading_type")
    val VIEW_NAVIGATION_TYPE: AttributeKey<String> = stringKey("fusescope.rum.view.navigation_type")
    val ERROR_SOURCE: AttributeKey<String> = stringKey("fusescope.rum.error.source")
    val ACTION_TYPE: AttributeKey<String> = stringKey("fusescope.rum.action.type")
    val ACTION_NAME: AttributeKey<String> = stringKey("fusescope.rum.action.name")
    val VITAL_NAME: AttributeKey<String> = stringKey("fusescope.rum.vital.name")
    val VITAL_VALUE: AttributeKey<Double> = doubleKey("fusescope.rum.vital.value")
    val VITAL_UNIT: AttributeKey<String> = stringKey("fusescope.rum.vital.unit")
    val VITAL_RATING: AttributeKey<String> = stringKey("fusescope.rum.vital.rating")
    val START_TYPE: AttributeKey<String> = stringKey("start.type")
    val EXCEPTION_TYPE: AttributeKey<String> = stringKey("exception.type")
    val EXCEPTION_MESSAGE: AttributeKey<String> = stringKey("exception.message")
    val EXCEPTION_STACKTRACE: AttributeKey<String> = stringKey("exception.stacktrace")
    val THREAD_ID: AttributeKey<Long> = longKey("thread.id")
}

internal object ResourceAttr {
    const val PLATFORM = "fusescope.rum.platform"
    const val APPLICATION_ID = "fusescope.rum.application.id"
    const val VERSION_CODE = "fusescope.rum.android.version_code"
    const val PACKAGE_NAME = "app.package.name"
    const val INSTALLATION_ID = "app.installation.id"
    const val DISTRO_NAME = "telemetry.distro.name"
    const val DISTRO_VERSION = "telemetry.distro.version"
    const val SERVICE_NAME = "service.name"
    const val SERVICE_VERSION = "service.version"
    const val ENVIRONMENT = "deployment.environment.name"
}

internal object Events {
    const val EXCEPTION = "exception"
    const val VITAL = "rum.vital"
    const val ACTION = "fusescope.rum.action"
    const val UPSTREAM_CRASH = "app.crash"
    const val UPSTREAM_CRASH_LEGACY = "device.crash"
    const val UPSTREAM_ANR = "device.anr"
}

internal object ErrorSource {
    const val CRASH = "crash"
    const val ANR = "anr"
    const val MANUAL = "manual"
}

internal const val SDK_NAME = "io.fuseone.fusescope:android"
internal const val PLATFORM_ANDROID = "android"
