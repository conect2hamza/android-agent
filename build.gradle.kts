// Intentionally minimal. Plugin versions come from gradle/libs.versions.toml and are
// applied per module. Declaring the Android plugins here with `apply false` would force
// Gradle to resolve them from Google's Maven even when :app is excluded for lack of an
// SDK, which breaks `:core` builds on plain JVM machines and CI images.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
