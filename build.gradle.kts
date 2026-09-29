import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Git4Idea is not on the platform classpath by default. It supplies GitRepositoryManager,
        // which is how the plugin enumerates the repositories of the open project, and plugin.xml
        // declares a hard dependency on it so the tool window is hidden rather than broken when the
        // Git plugin is disabled.
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        // The idea-version block is written by buildSearchableOptions/buildPlugin, so the floor
        // lives in gradle.properties and stays in step with the compiled platform. No until-build:
        // since 2024.3 an until-build is what stops a plugin installing into a newer IDE, and there
        // is no API here that is expected to break in the next release.
        ideaVersion {
            sinceBuild = providers.gradleProperty("plugin.sinceBuild")
        }
    }
}
