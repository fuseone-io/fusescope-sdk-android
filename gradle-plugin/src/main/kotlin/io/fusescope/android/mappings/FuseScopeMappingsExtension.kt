package io.fusescope.android.mappings

import org.gradle.api.provider.Property

/**
 * `fusescope { … }` in an Android application module. Unset values fall back to the `fusescope.*` Gradle
 * properties and then to the `FUSESCOPE_*` environment variables.
 */
abstract class FuseScopeMappingsExtension {
    /** FuseScope base URL, e.g. `https://fusescope.example.com`. `fusescope.endpoint` / `FUSESCOPE_ENDPOINT`. */
    abstract val endpoint: Property<String>

    /** RUM application id from the Setup screen. `fusescope.applicationId` / `FUSESCOPE_APPLICATION_ID`. */
    abstract val applicationId: Property<String>

    /** API key with the Editor role, sent as `SigNoz-Api-Key`; never the public RUM key. `FUSESCOPE_API_KEY`. */
    abstract val apiKey: Property<String>

    /** Release the mapping belongs to; defaults to the variant's `versionName`, like the SDK's `release`. */
    abstract val release: Property<String>

    /** Upload path under [endpoint]; `{applicationId}` is replaced. */
    abstract val uploadPath: Property<String>

    /** Upload after `assemble<Variant>` and `bundle<Variant>` of minified variants. Default `true`. */
    abstract val enabled: Property<Boolean>

    /** Fail the build when the upload fails. Default `true`. */
    abstract val failOnError: Property<Boolean>

    companion object {
        const val DEFAULT_UPLOAD_PATH = "/api/v1/rum/applications/{applicationId}/mappings"
    }
}
