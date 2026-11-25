import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

// Windows skiko natives for the cross-built fat jar (see packageWindowsFatJar).
val windowsSkiko by configurations.creating

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Same decoder stacks the Android app ships (already plain-JVM jars).
    implementation("org.jcraft:jorbis:0.0.17")
    implementation("com.badlogicgames.jlayer:jlayer:1.0.2-gdx")

    // Device Link payloads (playlist/track metadata) over HTTP.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Dispatchers.Main for Swing/AWT (used by the desktop installer UI).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    windowsSkiko("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.18")

    testImplementation(kotlin("test"))
}

// jpackage cannot cross-build Windows installers from Linux, but the Compose
// stack runs fine from a fat jar: bundle the app + all dependencies + the
// Windows skiko natives into one runnable jar (needs a JDK 17+ on Windows).
tasks.register<Jar>("packageWindowsFatJar") {
    group = "build"
    description = "Runnable Windows fat jar (app + deps + skiko windows natives)."
    archiveBaseName.set("PetalMusic")
    archiveClassifier.set("windows")
    archiveVersion.set("2.0.0")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "org.rab1d.petal.music.desktop.MainKt") }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/*/module-info.class", "module-info.class")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    dependsOn(windowsSkiko)
    from({ windowsSkiko.map { if (it.isDirectory) it else zipTree(it) } })
}

tasks.withType<Test> { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "org.rab1d.petal.music.desktop.MainKt"

        nativeDistributions {
            // Packaging is per-host: run on Windows for Msi/Exe, on Linux for
            // Deb/Rpm/AppImage. The BridgeFXWin engine itself is the same code.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "PetalMusic"
            packageVersion = "2.0.0"
            description = "Petal Music — BridgeFXWin edition"
            vendor = "rab1d"
            includeAllModules = true

            linux {
                iconFile.set(project.file("icons/petal_music.png"))
            }

            windows {
                iconFile.set(project.file("icons/petal_music.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}
