import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is read from keystore.properties (or the environment), so
// the credentials are not hardcoded in the build script.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(property: String, environment: String): String? =
    (System.getenv(environment) ?: keystoreProperties.getProperty(property))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.alex.cam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alex.cam"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "RECORDER_STORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "RECORDER_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RECORDER_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RECORDER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: code shrinking, optimisation and obfuscation.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val store = signingValue("storeFile", "RECORDER_STORE_FILE")
            if (store != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // The live stream encodes the camera frames with MediaCodec and muxes them
    // into fragmented MP4 (HLS) using media3.
    implementation("androidx.media3:media3-common:1.9.0")
    implementation("androidx.media3:media3-muxer:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // The real JSON implementation, so the endpoints can be tested on the JVM
    // (the one in android.jar is only a stub).
    testImplementation("org.json:json:20180813")
}
