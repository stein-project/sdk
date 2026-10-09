// The Stein SDK. "./gradlew build" builds the SDK, checks the formatting and runs the tests; the Gradle plugin for mod
// authors (gradle-plugin/) is its own build, included here.
plugins {
    // JDK 25 for compiling: Gradle finds an installed one or downloads it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "stein-sdk"

includeBuild("gradle-plugin") { name = "stein-gradle" }
