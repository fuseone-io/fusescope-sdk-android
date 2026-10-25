package io.fusescope.android.mappings

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/** `io.fusescope.android.mappings`: uploads R8 `mapping.txt` after `assemble<Variant>` and `bundle<Variant>`. */
class FuseScopeMappingsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("fusescope", FuseScopeMappingsExtension::class.java)
        ext.endpoint.convention(project.setting("endpoint", "FUSESCOPE_ENDPOINT"))
        ext.applicationId.convention(project.setting("applicationId", "FUSESCOPE_APPLICATION_ID"))
        ext.apiKey.convention(project.setting("apiKey", "FUSESCOPE_API_KEY"))
        ext.uploadPath.convention(FuseScopeMappingsExtension.DEFAULT_UPLOAD_PATH)
        ext.enabled.convention(true)
        ext.failOnError.convention(true)

        project.plugins.withId("com.android.application") {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                if (!variant.isMinifyEnabled) return@onVariants
                val name = variant.name.replaceFirstChar { it.uppercase() }
                val output = variant.outputs.first()
                val upload =
                    project.tasks.register("uploadFuseScopeMapping$name", UploadMappingTask::class.java) {
                        group = "fusescope"
                        description = "Uploads the R8 mapping of the ${variant.name} variant to FuseScope."
                        mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                        endpoint.set(ext.endpoint)
                        applicationId.set(ext.applicationId)
                        apiKey.set(ext.apiKey)
                        release.set(ext.release.orElse(output.versionName))
                        uploadPath.set(ext.uploadPath)
                        versionCode.set(output.versionCode)
                        packageName.set(variant.applicationId)
                        variantName.set(variant.name)
                        failOnError.set(ext.failOnError)
                        onlyIf("fusescope.enabled is true") { ext.enabled.get() }
                    }
                project.tasks
                    .matching { it.name == "assemble$name" || it.name == "bundle$name" }
                    .configureEach { finalizedBy(upload) }
            }
        }
    }

    private fun Project.setting(
        name: String,
        env: String,
    ): Provider<String> = providers.gradleProperty("fusescope.$name").orElse(providers.environmentVariable(env))
}
