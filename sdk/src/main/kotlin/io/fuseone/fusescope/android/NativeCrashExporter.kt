package io.fuseone.fusescope.android

import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.resources.Resource

@Suppress("UNUSED_PARAMETER")
internal fun exportNativeCrash(
    crash: RecoveredNativeCrash,
    resource: Resource,
    exporter: LogRecordExporter,
    policy: QueryPolicy,
): Boolean = false
