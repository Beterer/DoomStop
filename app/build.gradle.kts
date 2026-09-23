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
        // 2 was the Gate D update-path build, installed on the phone but never committed.
        // 4 shipped 0.3.0 to the phone; 5 added Instagram messaging mode.
        // 6 fixes late usage-event reconciliation and boundary corrections.
        versionCode = 6
        versionName = "0.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Fixtures deliberately leave policy applied so a person can look at the result.
        // They must never run as part of the ordinary suite; invoke them by class#method.
        testInstrumentationRunnerArguments["notAnnotation"] = "dev.personal.doomstop.admin.ManualFixture"
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

    sourceSets {
        // Room's exported schemas have to be on the instrumentation classpath for
        // MigrationTestHelper to open version 1 and replay the migration against it.
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
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

/**
 * A release build that is not signed cannot be installed over the provisioned device-owner
 * app, so producing one silently is worse than failing. This makes the omission loud.
 *
 * `-PallowUnsignedRelease=true` is the deliberate escape hatch for inspecting an unsigned
 * artifact during development; it must never be used for the build that goes on the phone.
 */
val allowUnsignedRelease = providers.gradleProperty("allowUnsignedRelease")
    .map { it.toBoolean() }
    .getOrElse(false)

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails a release build when no signing key is configured."
    val configured = hasReleaseKeystore
    val allowed = allowUnsignedRelease
    doLast {
        if (!configured && !allowed) {
            throw GradleException(
                "No keystore.properties: this release APK would be unsigned and could not be " +
                    "installed over the provisioned build. Create the key as described in " +
                    "docs/setup-and-recovery.md, or pass -PallowUnsignedRelease=true for a " +
                    "development-only artifact."
            )
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    // AGP resolves the instrumentation classpath consistently with this one, and Room 2.8s
    // schema deserializer needs kotlinx-serialization >= 1.8. Aligning the whole graph here
    // is what stops a transitive 1.7 BOM from deciding it for the migration test.
    implementation(platform(libs.kotlinx.serialization.bom))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
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
    // Room 2.8 deserializes the exported schema with kotlinx-serialization; without an
    // explicit, current runtime the generated serializers hit an AbstractMethodError.
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
