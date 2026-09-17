pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-agent"

// :core is a platform-free Kotlin library. It builds and tests anywhere a JVM and
// Maven Central are available, which is what keeps the assistant's language
// understanding, scheduling and reporting logic verifiable outside an emulator.
include(":core")

// :app needs the Android SDK. Including it unconditionally makes every Gradle
// invocation fail on machines (and CI images) without one, so it is opt-in on
// the SDK actually being present.
val androidSdkPresent =
    !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkPresent) {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle(
            "Android SDK not found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties): " +
                "skipping :app. ':core' still builds and tests."
        )
    }
}
