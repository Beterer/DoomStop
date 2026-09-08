import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Required since Kotlin 2.0 whenever buildFeatures.compose is on. Its version must
    // track the Kotlin version that AGP's built-in Kotlin support bundles.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// Release signing is configured from a keystore.properties file that is deliberately
// NOT in source control (see .gitignore). Losing this key means future versions can no
// longer be installed in place over the provisioned device-owner build, so keep a backup
// somewhere safe and outside the repository.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "dev.personal.doomstop"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // One stable application ID for debug and release. A device-owner app cannot
        // change its package name without being de-provisioned, so no applicationIdSuffix.
        applicationId = "dev.personal.doomstop"
        // The Pixel 9 (tokay) launched on API 34; nothing older will ever run this build,
        // so the minimum matches the device and avoids dead compatibility branches.
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Kept debuggable on purpose: development and recovery testing require it.
            isDebuggable = true
        }
        release {
            isDebuggable = false
            // R8 is left off. This app is tiny, and the components that matter
            // (DeviceAdminReceiver, boot receivers, Room entities) are exactly the kind of
            // reflection/manifest-driven code that silent over-optimisation breaks.
            optimization {
                enable = false
            }
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // The delivered build must be lint-clean for correctness categories.
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }
}

ksp {
    // Exported schemas are committed so Room migrations can be written and tested
    // explicitly instead of relying on destructive fallback.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
