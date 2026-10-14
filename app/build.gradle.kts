import java.util.Properties

// Release signing comes from an untracked keystore.properties (see docs/DEVELOPMENT.md); without it the release APK is unsigned.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}
fun keystoreValue(key: String): String = keystore?.getProperty(key)?.takeIf { it.isNotBlank() }
    ?: throw GradleException("keystore.properties: '$key' is missing or empty")

// Bump only this. versionCode is derived from it so the in-app updater (which compares names) and Android
// (which requires a higher versionCode) always agree: MAJOR.MINOR.PATCH[-alpha|-beta|-rc[.N]].
val appVersion = "0.3.3"
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.substringBefore('-').split('.').map { it.toInt() }
    val pre = version.substringAfter('-', "")
    val preRank = if (pre.isEmpty()) 99 else {
        val number = pre.substringAfter('.', "0").toInt().also { require(it in 0..9) { "Pre-release number must be 0-9: $version" } }
        number + when (pre.substringBefore('.')) { "alpha" -> 10; "beta" -> 30; "rc" -> 60; else -> throw GradleException("Unsupported pre-release in $version") }
    }
    require(minor < 100 && patch < 100) { "Minor and patch must be below 100: $version" }
    return ((major * 100 + minor) * 100 + patch) * 100 + preRank
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.flip2layout.switcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.flip2layout.switcher"
        minSdk = 33
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // "owner/repo" whose GitHub Releases provide updates; empty disables the update check.
        buildConfigField("String", "UPDATE_REPO", "\"${providers.gradleProperty("updateRepo").getOrElse("")}\"")
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystoreValue("storeFile"))
            storePassword = keystoreValue("storePassword")
            keyAlias = keystoreValue("keyAlias")
            keyPassword = keystoreValue("keyPassword")
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("release") } }
}
// APKs are named Flip2LayoutSwitcher-<version>-<buildType>.apk.
base { archivesName = "Flip2LayoutSwitcher-${android.defaultConfig.versionName}" }
// The GitHub release asset: build/dist/Flip2LayoutSwitcher-<version>.apk, without the build-type suffix.
val releaseAssetName = "Flip2LayoutSwitcher-$appVersion.apk"
val releaseAsset by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*-release.apk") }
    into(layout.buildDirectory.dir("dist"))
    rename { releaseAssetName }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(releaseAsset) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the Android stub throws).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
