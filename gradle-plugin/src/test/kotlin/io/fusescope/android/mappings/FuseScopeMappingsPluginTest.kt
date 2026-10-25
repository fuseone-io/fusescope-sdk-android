package io.fusescope.android.mappings

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FuseScopeMappingsPluginTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun `configures the extension from Gradle properties without the Android plugin`() {
        dir.newFile("settings.gradle.kts").writeText("rootProject.name = \"app\"")
        dir.newFile("gradle.properties").writeText("fusescope.endpoint=https://fusescope.example.com\n")
        dir.newFile("build.gradle.kts").writeText(
            """
            plugins { id("io.fusescope.android.mappings") }
            tasks.register("show") {
                val ext = project.extensions.getByType(io.fusescope.android.mappings.FuseScopeMappingsExtension::class.java)
                val endpoint = ext.endpoint
                val path = ext.uploadPath
                doLast { println("endpoint=" + endpoint.get() + " path=" + path.get()) }
            }
            """.trimIndent(),
        )
        val result =
            GradleRunner
                .create()
                .withProjectDir(dir.root)
                .withPluginClasspath()
                .withArguments("show", "-q")
                .build()
        assertTrue(
            result.output,
            result.output.contains("endpoint=https://fusescope.example.com path=/api/v1/rum/applications/{applicationId}/mappings"),
        )
        assertEquals(0, result.tasks.count { it.path.startsWith(":uploadFuseScopeMapping") })
    }
}
