import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately dependency-free apart from the Kotlin stdlib: everything in this module must run on
// the JVM, on Android, and inside a plain unit test without an emulator. No JSON library either --
// see util/MiniJson.kt for why.
dependencies {
    testImplementation(libs.junit)
}

// Targets Java 17 bytecode (what the Android toolchain consumes) without pinning a toolchain, so the
// module still builds on machines that only have a newer JDK installed.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
