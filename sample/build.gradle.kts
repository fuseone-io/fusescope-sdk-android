plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.fusescope.android.mappings")
}

fun setting(
    name: String,
    default: String,
): String = providers.gradleProperty("fusescope.sample.$name").getOrElse(default)

android {
    namespace = "io.fusescope.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.fusescope.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "FUSESCOPE_ENDPOINT", "\"${setting("endpoint", "http://10.0.2.2:4399")}\"")
        buildConfigField("String", "FUSESCOPE_APPLICATION_ID", "\"${setting("applicationId", "0199c0de-0000-7000-8000-00000000a11d")}\"")
        buildConfigField("String", "FUSESCOPE_KEY", "\"${setting("key", "rum_pub_sample")}\"")
        buildConfigField("String", "API_URL", "\"${setting("apiUrl", "http://10.0.2.2:4399/api/ping")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The sample is never published; debug signing lets the release build run on an emulator.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

fusescope {
    endpoint.set(setting("endpoint", "http://localhost:4399").replace("10.0.2.2", "localhost"))
    applicationId.set(setting("applicationId", "0199c0de-0000-7000-8000-00000000a11d"))
    enabled.set(providers.gradleProperty("fusescope.sample.uploadMapping").map(String::toBoolean).orElse(false))
}

dependencies {
    implementation(project(":sdk"))
    implementation(project(":compose"))
    implementation(libs.androidx.fragment)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}
