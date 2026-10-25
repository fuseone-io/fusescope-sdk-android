package io.fusescope.android.mappings

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class MappingUploaderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var path: String? = null
    private var key: String? = null
    private var contentType: String? = null
    private var body: String = ""

    private fun serve(status: Int) {
        server.createContext("/") { exchange ->
            path = exchange.requestURI.toString()
            key = exchange.requestHeaders.getFirst("SigNoz-Api-Key")
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            body = exchange.requestBody.readBytes().decodeToString()
            val out = """{"status":"ok"}""".toByteArray()
            exchange.sendResponseHeaders(status, out.size.toLong())
            exchange.responseBody.use { it.write(out) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun upload(): MappingUpload {
        val mapping =
            File
                .createTempFile(
                    "mapping",
                    ".txt",
                ).apply { writeText("# compiler: R8\n# pg_map_id: 260eabab\ncom.example.Main -> a:\n") }
        return MappingUpload(
            endpoint = "http://127.0.0.1:${server.address.port}/",
            uploadPath = FuseScopeMappingsExtension.DEFAULT_UPLOAD_PATH,
            applicationId = "app 1",
            apiKey = "editor-key",
            release = "1.4.0+42",
            versionCode = 42,
            packageName = "com.example.shop",
            variant = "release",
            mapping = mapping,
        )
    }

    @Test
    fun `posts the mapping as multipart with the API key`() {
        serve(201)
        val result = MappingUploader.upload(upload())
        assertEquals(201, result.status)
        assertEquals("/api/v1/rum/applications/app+1/mappings?release=1.4.0%2B42", path)
        assertEquals("editor-key", key)
        assertTrue(contentType!!.startsWith("multipart/form-data; boundary="))
        assertTrue(
            body.contains(
                "name=\"file\"; filename=\"mapping.txt\"\r\nContent-Type: text/plain\r\n\r\n# compiler: R8\n# pg_map_id: 260eabab\ncom.example.Main -> a:\n\r\n",
            ),
        )
        assertTrue(body.contains("name=\"version_code\"\r\n\r\n42\r\n"))
        assertTrue(body.contains("name=\"map_id\"\r\n\r\n260eabab\r\n"))
        assertTrue(body.contains("name=\"package_name\"\r\n\r\ncom.example.shop\r\n"))
        assertTrue(body.trimEnd().endsWith("--"))
        val parts = Regex("name=\"([a-z_]+)\"").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(listOf("release", "file", "package_name", "variant", "version_code", "map_id"), parts)
    }

    @Test
    fun `returns the error status and body`() {
        serve(403)
        assertEquals(UploadResult(403, """{"status":"ok"}"""), MappingUploader.upload(upload()))
    }
}
