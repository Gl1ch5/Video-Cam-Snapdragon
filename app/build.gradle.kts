import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes VERSION_CODE / VERSION_SUFFIX (nightly builds); local builds get dev values.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val buildDate: String = SimpleDateFormat("yyyyMMdd").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
val ciVersionSuffix = System.getenv("VERSION_SUFFIX") ?: "dev.$buildDate"

android {
    namespace = "com.gl1ch5.stabcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gl1ch5.stabcam"
        minSdk = 31
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = "0.1.0-$ciVersionSuffix"
    }

    signingConfigs {
        // Release signing from env (GitHub secrets); otherwise the shared dev key in ci/.
        create("release") {
            val ks = System.getenv("SIGNING_KEYSTORE_PATH")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                // Shared dev key committed in ci/: the same on every machine and every CI run, so updates install over each other.
                storeFile = rootProject.file("ci/stabcam-ci.jks")
                storePassword = "stabcam-ci"
                keyAlias = "stabcam"
                keyPassword = "stabcam-ci"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
