import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

android {
    namespace = "org.rab1d.petal.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.rab1d.petal.music"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // rom: petalOS prebuilt (platform-signed, BridgeFX HAL, island hooks,
    // on-device Apollo via ONNX Runtime + QNN). fdroid: none of that; AutoMix
    // runs on the internal DSP and Restore upscales only over Device Link.
    // fdroid release key lives outside the tree (~/.android/petal-fdroid);
    // without it (e.g. on F-Droid's own build server) the APK is left unsigned.
    // Override with -PfdroidSigning=/path/to/signing.properties
    val fdroidSigning = File(
        providers.gradleProperty("fdroidSigning")
            .orElse("${System.getProperty("user.home")}/.android/petal-fdroid/signing.properties").get(),
    )
    val fdroidKey = if (fdroidSigning.isFile) {
        val props = Properties().apply { fdroidSigning.inputStream().use { load(it) } }
        signingConfigs.create("fdroid") {
            storeFile = File(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else null

    flavorDimensions += "distribution"
    productFlavors {
        create("rom") {
            dimension = "distribution"
            buildConfigField("boolean", "PETAL_ROM", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "PETAL_ROM", "false")
            // Debug keeps the debug key (build-type config wins); release gets this.
            fdroidKey?.let { signingConfig = it }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "*.proto"
            excludes += "google/protobuf/*"
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Override the retired Mercury token exchange without modifying the vendor jar.
// The replacement source retains the 1.6.5 API for its precompiled callers.
val androidLibrespot by tasks.registering(Jar::class) {
    archiveFileName.set("librespot-android.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(zipTree("libs/librespot-player-1.6.5-patched.jar")) {
        exclude("xyz/gianlu/librespot/core/TokenProvider*.class")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-datasource:$media3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.tony19:logback-android:3.0.0")
    // logback-android 3 uses SLF4J 2; zeroconf otherwise pulls in 1.7,
    // which silently disables all librespot diagnostics.
    implementation("org.slf4j:slf4j-api:2.0.7")

    // On-device audio upscaling (bandwidth extension) for downloaded tracks.
    // 1.30 matches the exporter that produced the Apollo graph (its one-sided
    // inverse DFT is rejected by ORT 1.20's stricter shape inference).
    // rom only: the QNN libraries in this AAR are proprietary.
    "romImplementation"(files("libs/onnxruntime-qnn-1.30.0.aar"))

    // Unofficial service clients.
    // librespot-player from JitPack is a fat jar (shaded kotlin/gson/protobuf/...)
    // that cannot dex alongside real deps — use the stripped jar + plain deps.
    implementation(files(androidLibrespot))
    implementation("org.jcraft:jorbis:0.0.17")
    implementation("com.badlogicgames.jlayer:jlayer:1.0.2-gdx")
    // Full runtime also serves the extractor's lite-generated messages; keep
    // its version aligned with the extractor to avoid duplicate protobuf types.
    implementation("com.google.protobuf:protobuf-java:4.35.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("xyz.gianlu.zeroconf:zeroconf:1.3.2")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.electronwill.night-config:toml:3.6.7")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5") {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("org.mozilla:rhino:1.7.15")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}

// JitPack fat-jar fallout: keep only what librespot modules genuinely need.
configurations.all {
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "org.apache.logging.log4j")
    exclude(group = "com.lmax", module = "disruptor")
}

// Sign the release APK with the petalOS platform keys (vendor/petal-priv/keys).
// Platform signature = hidden-API exempt = BridgeFX attach works on the ROM.
// Override with -PpetalKeys=/path/to/keys -Papksigner=/path/to/apksigner
val petalKeys = File(
    providers.gradleProperty("petalKeys").orElse("/android/vendor/petal-priv/keys").get(),
)
val apksigner = providers.gradleProperty("apksigner")
    .orElse(
        listOf(
            "${System.getenv("ANDROID_SDK_ROOT") ?: ""}/build-tools/36.0.0/apksigner",
            "${System.getenv("ANDROID_SDK_ROOT") ?: ""}/build-tools/35.0.0/apksigner",
            "${System.getenv("ANDROID_HOME") ?: ""}/build-tools/36.0.0/apksigner",
            "${System.getenv("ANDROID_HOME") ?: ""}/build-tools/35.0.0/apksigner",
            "$rootDir/../Android/Sdk/build-tools/36.0.0/apksigner",
            "$rootDir/../Android/Sdk/build-tools/35.0.0/apksigner",
        ).firstOrNull { File(it).canExecute() } ?: "apksigner",
    ).get()

tasks.register("signPetal") {
    group = "build"
    description = "Signs the rom release APK with the petalOS platform key into prebuilt/."
    dependsOn("assembleRomRelease")
    doLast {
        val unsigned = file("${layout.buildDirectory.get()}/outputs/apk/rom/release/app-rom-release-unsigned.apk")
        require(unsigned.exists()) { "unsigned release apk missing" }
        require(petalKeys.resolve("platform.pk8").exists() && petalKeys.resolve("platform.x509.pem").exists()) {
            "petal platform keys not found in ${petalKeys.absolutePath}"
        }
        val outDir = rootProject.file("prebuilt").apply { mkdirs() }
        val signed = File(outDir, "app-release.apk")
        exec {
            commandLine(
                apksigner, "sign",
                "--key", petalKeys.resolve("platform.pk8").absolutePath,
                "--cert", petalKeys.resolve("platform.x509.pem").absolutePath,
                "--out", signed.absolutePath,
                unsigned.absolutePath,
            )
        }
        exec {
            commandLine(apksigner, "verify", signed.absolutePath)
        }
        println("PetalMusic signed -> ${signed.absolutePath}")
    }
}
