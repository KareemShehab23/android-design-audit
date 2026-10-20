plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.kareem.designaudit"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compile against IntelliJ Community; the plugin uses only platform APIs, so it runs in Android Studio
        // Ladybug (2024.2) and newer. To compile against Android Studio itself instead, replace with e.g.
        //   androidStudio("2025.1.3.7")   // versions: https://plugins.jetbrains.com/docs/intellij/android-studio-releases-list.html
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
    }
}

// IntelliJ / Android Studio 2024.2+ run on Java 21, so both Java and Kotlin target 21.
// Build with any JDK 21+ (Android Studio's bundled JBR works).
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

intellijPlatform {
    pluginConfiguration {
        name = "Android Design Audit"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}
