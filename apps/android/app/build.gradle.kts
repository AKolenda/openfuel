// SPDX-License-Identifier: AGPL-3.0-only
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val publicApiBase = providers.gradleProperty("OPENFUEL_API_BASE_URL")
    .orElse(providers.environmentVariable("OPENFUEL_API_BASE_URL"))
    .getOrElse("https://openfuel.ca/api/v1")
val parsedApi = URI(publicApiBase)
val emulatorTest = providers.gradleProperty("OPENFUEL_EMULATOR_TEST").getOrElse("false") == "true"
val localTestHost = emulatorTest && parsedApi.scheme == "http" && parsedApi.host == "10.0.2.2"
val compactApk = !emulatorTest && providers.gradleProperty("OPENFUEL_COMPACT_APK").getOrElse("true") == "true"
require(!emulatorTest || gradle.startParameter.taskNames.none { it.contains("release", ignoreCase = true) }) {
    "Emulator test URLs cannot be used in release builds."
}
require((parsedApi.scheme == "https" || localTestHost) && parsedApi.host != null && parsedApi.userInfo == null && parsedApi.query == null && parsedApi.fragment == null) {
    "OPENFUEL_API_BASE_URL must be a public HTTPS base URL without credentials or query parameters."
}
val dataMode = providers.gradleProperty("OPENFUEL_DATA_MODE").getOrElse("live")
require(dataMode == "live") { "This app uses real station geography." }
android {
    namespace = "ca.openfuel.prototype"
    compileSdk = 35
    defaultConfig {
        applicationId = "ca.openfuel.prototype"
        manifestPlaceholders["openfuelCleartext"] = localTestHost.toString()
        minSdk = 26
        targetSdk = 35 // Development APK; re-evaluate Play requirements before a store submission.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"$publicApiBase\"")
        buildConfigField("String", "DATA_MODE", "\"$dataMode\"")
        versionCode = 5
        versionName = "0.3.1-live"
    }
    sourceSets["main"].assets.srcDir(rootProject.file("../web/preview/vendor"))
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Compress native map libraries and remove unused Compose icons so the universal
    // downloadable APK fits Cloudflare's 25 MiB static asset limit.
    packaging { jniLibs.useLegacyPackaging = true }
    buildTypes {
        debug {
            isMinifyEnabled = compactApk
            isShrinkResources = compactApk
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        release { isMinifyEnabled = false }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    // Inspection/test-only UI tooling is not shipped in the downloadable APK.
    if (!compactApk) debugImplementation("androidx.compose.ui:ui-test-manifest")
}
