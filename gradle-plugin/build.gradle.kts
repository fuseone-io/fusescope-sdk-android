import java.util.Properties

plugins {
    `kotlin-dsl`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.ktlint)
}

val sdkProperties = Properties().apply { rootDir.parentFile.resolve("gradle.properties").inputStream().use(::load) }
group = sdkProperties.getProperty("GROUP")
version = providers.gradleProperty("VERSION_NAME").getOrElse(sdkProperties.getProperty("VERSION_NAME"))

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    website.set("https://github.com/fuseone-io/fusescope-sdk-android")
    vcsUrl.set("https://github.com/fuseone-io/fusescope-sdk-android")
    plugins {
        create("mappings") {
            id = "io.fusescope.android.mappings"
            implementationClass = "io.fusescope.android.mappings.FuseScopeMappingsPlugin"
            displayName = "FuseScope R8 mapping upload"
            description = "Uploads R8/ProGuard mapping.txt files to FuseScope after release builds."
        }
    }
}

dependencies {
    compileOnly(libs.agp.api)
    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

ktlint {
    version.set(libs.versions.ktlint.cli)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(group.toString(), "android-mappings-gradle-plugin", version.toString())
    pom {
        name.set("FuseScope Android mappings Gradle plugin")
        description.set("Uploads R8/ProGuard mapping.txt files to FuseScope after release builds.")
        inceptionYear.set("2026")
        url.set(sdkProperties.getProperty("POM_URL"))
        licenses {
            license {
                name.set(sdkProperties.getProperty("POM_LICENSE_NAME"))
                url.set(sdkProperties.getProperty("POM_LICENSE_URL"))
                distribution.set(sdkProperties.getProperty("POM_LICENSE_DIST"))
            }
        }
        developers {
            developer {
                id.set(sdkProperties.getProperty("POM_DEVELOPER_ID"))
                name.set(sdkProperties.getProperty("POM_DEVELOPER_NAME"))
                url.set(sdkProperties.getProperty("POM_DEVELOPER_URL"))
            }
        }
        scm {
            url.set(sdkProperties.getProperty("POM_SCM_URL"))
            connection.set(sdkProperties.getProperty("POM_SCM_CONNECTION"))
            developerConnection.set(sdkProperties.getProperty("POM_SCM_DEV_CONNECTION"))
        }
    }
}
