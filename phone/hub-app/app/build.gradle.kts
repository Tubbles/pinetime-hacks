import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.tubbles.hub"
    compileSdk = 36

    defaultConfig {
        // No ".debug" suffix on the debug build (the only one CI ships): the
        // Home Assistant nudge addresses exactly this package
        // (doc/DESIGN-hub.md, "The nudge from Home Assistant").
        applicationId = "com.tubbles.hub"

        // The watch and the Gadgetbridge that owns its BLE link, baked in so
        // there is nothing to type at install (doc/DESIGN-hub.md, Leg 3).
        buildConfigField("String", "WATCH_MAC", "\"DB:8F:A2:1A:A1:DB\"")
        buildConfigField(
            "String", "GADGETBRIDGE_PACKAGE", "\"nodomain.freeyourgadget.gadgetbridge.t\""
        )
        minSdk = 34
        targetSdk = 36
        versionName = project.property("VERSION_NAME").toString()
        // CI builds get a monotonically increasing versionCode (base * 1000 +
        // workflow run number) so every artifact can update the previous one.
        versionCode = System.getenv("PINETIME_CI_VERSION_CODE")
            ?.let { project.property("VERSION_CODE").toString().toInt() * 1000 + it.toInt() }
            ?: project.property("VERSION_CODE").toString().toInt()
        setProperty("archivesBaseName", "hub-$versionCode")
    }

    signingConfigs {
        // CI debug signing with the committed keystore, as in Phone T: a key
        // generated per runner would stop every artifact from updating the
        // previous install.
        System.getenv("PINETIME_DEBUG_KEYSTORE")?.let { keystorePath ->
            register("ciDebug") {
                storeFile = file(keystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            if (System.getenv("PINETIME_DEBUG_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
}
