package io.fuseone.fusescope.android.mappings

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.IOException

@UntrackedTask(because = "It uploads to a server")
abstract class UploadMappingTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    @get:Input @get:Optional
    abstract val endpoint: Property<String>

    @get:Input @get:Optional
    abstract val applicationId: Property<String>

    @get:Internal
    abstract val apiKey: Property<String>

    @get:Input @get:Optional
    abstract val release: Property<String>

    @get:Input
    abstract val uploadPath: Property<String>

    @get:Input @get:Optional
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val failOnError: Property<Boolean>

    @TaskAction
    fun upload() {
        val mapping = mappingFile.orNull?.asFile
        if (mapping == null || !mapping.isFile) {
            logger.warn("FuseScope: no R8 mapping for ${variantName.get()}; nothing uploaded")
            return
        }
        val missing =
            listOf("endpoint" to endpoint, "applicationId" to applicationId, "apiKey" to apiKey, "release" to release)
                .filter { (_, p) -> p.orNull.isNullOrBlank() }
                .map { it.first }
        if (missing.isNotEmpty()) {
            logger.warn("FuseScope: mapping upload for ${variantName.get()} skipped, set ${missing.joinToString()} in fusescope { }")
            return
        }
        val upload =
            MappingUpload(
                endpoint = endpoint.get(),
                uploadPath = uploadPath.get(),
                applicationId = applicationId.get(),
                apiKey = apiKey.get(),
                release = release.get(),
                versionCode = versionCode.orNull,
                packageName = packageName.get(),
                variant = variantName.get(),
                mapping = mapping,
            )
        val result =
            try {
                MappingUploader.upload(upload)
            } catch (e: IOException) {
                fail("FuseScope: mapping upload to ${upload.url()} failed: ${e.message}")
                return
            }
        if (result.status in 200..299) {
            logger.lifecycle("FuseScope: uploaded the R8 mapping of ${variantName.get()} for release ${upload.release}")
        } else {
            fail("FuseScope: mapping upload to ${upload.url()} returned ${result.status}: ${result.body}")
        }
    }

    private fun fail(message: String) {
        if (failOnError.get()) throw GradleException(message)
        logger.warn(message)
    }
}
