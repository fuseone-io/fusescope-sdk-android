package io.fusescope.android.mappings

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID

/** One mapping upload: `POST <endpoint><uploadPath>?release=<release>` with a multipart body. */
data class MappingUpload(
    val endpoint: String,
    val uploadPath: String,
    val applicationId: String,
    val apiKey: String,
    val release: String,
    val versionCode: Int?,
    val packageName: String,
    val variant: String,
    val mapping: File,
) {
    fun url(): URI {
        val path = uploadPath.replace("{applicationId}", URLEncoder.encode(applicationId, UTF_8))
        return URI.create("${endpoint.trimEnd('/')}$path?release=${URLEncoder.encode(release, UTF_8)}")
    }
}

data class UploadResult(
    val status: Int,
    val body: String,
)

private val MAP_ID = Regex("""^# pg_map_id: (\S+)""")

/**
 * The `pg_map_id` R8 writes in the mapping header. Stack frames of that build name their source file
 * `r8-map-id-<id>`, so it identifies the exact mapping a trace needs.
 */
fun mapId(mapping: File): String? =
    mapping.useLines { lines ->
        lines.take(64).takeWhile { it.startsWith("#") }.firstNotNullOfOrNull { MAP_ID.find(it)?.groupValues?.get(1) }
    }

object MappingUploader {
    const val API_KEY_HEADER = "SigNoz-Api-Key"

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    fun upload(u: MappingUpload): UploadResult {
        val boundary = "fusescope-" + UUID.randomUUID()
        val fields =
            buildMap {
                put("release", u.release)
                put("package_name", u.packageName)
                put("variant", u.variant)
                u.versionCode?.let { put("version_code", it.toString()) }
                mapId(u.mapping)?.let { put("map_id", it) }
            }
        val head =
            buildString {
                for ((name, value) in fields) {
                    append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"mapping.txt\"\r\n")
                append("Content-Type: text/plain\r\n\r\n")
            }
        val body =
            HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(head),
                HttpRequest.BodyPublishers.ofFile(u.mapping.toPath()),
                HttpRequest.BodyPublishers.ofString("\r\n--$boundary--\r\n"),
            )
        val request =
            HttpRequest
                .newBuilder(u.url())
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header(API_KEY_HEADER, u.apiKey)
                .POST(body)
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return UploadResult(response.statusCode(), response.body().take(2_000))
    }
}
