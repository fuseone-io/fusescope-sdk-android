import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.ReportRenderer

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.license.report) apply false
}

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.cli)
    }
}

// Runtime dependencies of the published libraries must be MIT, BSD, ISC or Apache-2.0.
subprojects {
    pluginManager.withPlugin("com.android.library") {
        apply(plugin = "com.github.jk1.dependency-license-report")
        configure<LicenseReportExtension> {
            configurations = arrayOf("releaseRuntimeClasspath")
            allowedLicensesFile = rootProject.file("config/allowed-licenses.json")
            filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
            renderers = arrayOf<ReportRenderer>(InventoryMarkdownReportRenderer("licenses.md"))
        }
    }
}
