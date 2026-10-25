plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.fuseone.fusescope.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.property("VERSION_NAME")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    api(platform(libs.otel.instrumentation.bom))
    api(libs.otel.api)
    api(libs.okhttp)
    implementation(libs.otel.android.agent)
    implementation(libs.otel.android.core)
    implementation(libs.otel.android.session)
    api(libs.otel.android.common.api)
    implementation(libs.otel.sdk)
    implementation(libs.otel.exporter.otlp)
    implementation(libs.otel.okhttp)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.otel.sdk.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.otel.proto)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(artifactId = "android")
    pom {
        name.set("FuseScope Android SDK")
        description.set("FuseScope Real User Monitoring for Android: a thin OpenTelemetry Android distribution.")
        inceptionYear.set("2026")
    }
}
