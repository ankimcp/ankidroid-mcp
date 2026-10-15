import java.io.StringReader
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// google-services.json is not in the repo (it lives in Bitwarden, see README). Without it the
// plugin is skipped, Firebase never initialises, and the app runs with push wake-ups disabled.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
} else {
    logger.lifecycle("app/google-services.json not found: building without Firebase push")
}

// version.properties is the single source of truth for the version (docs/RELEASING.md).
// versionCode is derived from it so rebuilding a tag always gives the same code.
val appVersionName: String =
    Properties()
        .apply {
            load(
                StringReader(
                    providers.fileContents(rootProject.layout.projectDirectory.file("version.properties"))
                        .asText.get(),
                ),
            )
        }
        .getProperty("versionName")
        ?: error("version.properties has no versionName")
val appVersionCode: Int = run {
    // Same shape as SHAPE_RE in .github/scripts/resolve_version.py.
    val match = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""").matchEntire(appVersionName)
        ?: error("versionName '$appVersionName' must be MAJOR.MINOR.PATCH")
    val (major, minor, patch) = match.destructured.toList().map(String::toInt)
    require(minor <= 99 && patch <= 99) { "minor and patch must be <= 99 for versionCode" }
    major * 10_000 + minor * 100 + patch
}

// Release signing comes from the environment (CI secrets, docs/RELEASING.md). Without all four
// variables the release build stays unsigned, as before.
val releaseSigningEnv: Map<String, String>? = listOf(
    "ANKIMCP_KEYSTORE_PATH",
    "ANKIMCP_KEYSTORE_PASSWORD",
    "ANKIMCP_KEY_ALIAS",
    "ANKIMCP_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull.orEmpty() }
    .takeIf { env -> env.values.all { it.isNotEmpty() } }
if (releaseSigningEnv == null) {
    logger.lifecycle("ANKIMCP_KEYSTORE_* / ANKIMCP_KEY_* not all set: release build is unsigned")
}

val defaultTunnelUrl = "wss://ankidroid.ankimcp.ai"
val devTunnelUrl: String =
    providers.gradleProperty("ankimcp.tunnelUrl").orElse(defaultTunnelUrl).get()

android {
    namespace = "ai.ankimcp.ankidroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.ankimcp.ankidroid"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseSigningEnv != null) {
            create("release") {
                storeFile = file(releaseSigningEnv.getValue("ANKIMCP_KEYSTORE_PATH"))
                storePassword = releaseSigningEnv.getValue("ANKIMCP_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("ANKIMCP_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("ANKIMCP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "TUNNEL_URL", "\"$devTunnelUrl\"")
        }
        release {
            buildConfigField("String", "TUNNEL_URL", "\"$defaultTunnelUrl\"")
            if (releaseSigningEnv != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
