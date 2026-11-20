import java.util.Properties

// Signing material. Never committed: put it in keystore.properties at this project's root, or
// export the same four values as environment variables on CI.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

// Push relay credential (user:password). Never committed: put fcmAuth in secrets.properties at
// this project's root (see secrets.properties.example), or export FCM_AUTH on CI.
val secretsProperties = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val fcmAuth: String = secretsProperties.getProperty("fcmAuth") ?: System.getenv("FCM_AUTH") ?: ""

android {
    namespace = "id.larus.mqttgroup"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.larus.mqttgroup"
        // Background location and scoped storage behave predictably from 10 onwards, which is
        // the whole point of this app's location engine.
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "1.8"
        buildConfigField("String", "FCM_AUTH", "\"$fcmAuth\"")
    }

    // Optional single-ABI build: ./gradlew assembleRelease -Pabi=arm64-v8a
    //
    // The default stays universal, which is what a sideloaded APK has to be when you do not know
    // what it is going to be installed on. But MapLibre's renderer is ~16 MB of native code per
    // architecture, so a universal build carries four copies of it and three of them are dead
    // weight on any given phone — enough to matter when the APK has to travel over a chat app.
    // Every phone sold in years is arm64-v8a.
    (findProperty("abi") as String?)?.let { requested ->
        defaultConfig {
            ndk { abiFilters += requested.split(",").map(String::trim) }
        }
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "ANDROID_KEYSTORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to an unsigned build when no keystore is configured, so a plain
            // checkout still builds.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    androidResources { localeFilters += listOf("en", "in") }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Schemas are checked in so migrations can be written against a known starting point.
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Netty, which HiveMQ builds on, ships service-loader indexes and native-image
        // metadata that mean nothing in an APK and collide across its modules.
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/native-image/**",
        )
    }
}

dependencies {
    // Wake-up signal only: FCM tells the app a message is waiting so it can bring up the
    // MQTT connection. The message content itself never leaves the broker's ciphertext path.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Room talks to SQLCipher through the support-SQLite interfaces, so the whole database
    // file is encrypted at rest and the DAOs never know about it.
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    // MQTT 5 with reconnect, backpressure and TLS already solved. Paho's Android service is
    // effectively abandoned and fights modern background limits.
    implementation(libs.hivemq.mqtt.client)
    // QR encode and decode locally. ML Kit would be smaller to write against but drags in
    // Play services, and an app whose selling point is that nothing leaves the phone should
    // not need Google's libraries to read its own invite codes.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime.ktx)
    // The map. MapLibre is the community fork of Mapbox GL from before it went proprietary:
    // real vector tiles — rotate, tilt, smooth zoom, styled labels — with no API key, no
    // account and no telemetry call-home. Paired with OpenFreeMap for tiles (see MapStyle), it
    // keeps the promise the rest of this app makes: no vendor is told who is looking at what.
    // The -opengl variant rather than the combined artifact, which would ship a second Vulkan
    // renderer this app's minSdk 29 never selects.
    implementation(libs.maplibre.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // The real org.json rather than the stub in android.jar, so the invite and control codecs
    // can be tested against actual parsing.
    testImplementation(libs.json)
}
