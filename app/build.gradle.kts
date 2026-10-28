import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// CI can provide the integration value without writing it into the checkout.
// APK-embedded configuration is extractable; this is not secret storage.
val localConfiguration = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val partnerClientQuery = (providers.environmentVariable("AUDIOTALES_PARTNER_CLIENT_QUERY").orNull
    ?: localConfiguration.getProperty("audiotales.partnerClientQuery", ""))
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")

// Release builds use the validated tag; ordinary development builds keep their defaults.
val releaseTag = providers.environmentVariable("AUDIOTALES_RELEASE_TAG").orNull
val releaseVersion = releaseTag?.let { tag ->
    val match = Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(tag)
        ?: error("AUDIOTALES_RELEASE_TAG must be a stable vMAJOR.MINOR.PATCH tag")
    val (major, minor, patch) = match.destructured
    val components = listOf(major, minor, patch).map { it.toLongOrNull() ?: error("Release version is out of range") }
    require(components[0] in 0L..2099L && components[1] in 0L..999L && components[2] in 0L..999L) {
        "Release version components are out of range"
    }
    val code = components[0] * 1_000_000L + components[1] * 1_000L + components[2]
    require(code in 1L..2_100_000_000L) { "Release versionCode must be positive and at most 2100000000" }
    tag.removePrefix("v") to code.toInt()
}

android {
    namespace = "io.github.drolevar.audiotales"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.drolevar.audiotales"
        minSdk = 26
        targetSdk = 37
        resourceConfigurations += listOf("en", "ru", "uk", "nb")
        versionCode = releaseVersion?.second ?: 1
        versionName = releaseVersion?.first ?: "0.1.0-dev"
        buildConfigField("String", "PARTNER_CLIENT_QUERY", "\"$partnerClientQuery\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.framework)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    // Keep handlers for pending jobs from earlier builds; new restarts use PlatformScheduler.
    implementation(libs.androidx.media3.exoplayer.workmanager)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.guava)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // Compose's older transitive Espresso uses InputManager reflection removed on current Android.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
