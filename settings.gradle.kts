import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "xtsc-intellij-plugin"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.power-assert") version "2.4.10"
        id("org.jetbrains.changelog") version "2.5.0"
        id("com.gradleup.shadow") version "9.6.1"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        // Until xtsc is released, a locally built compiler comes first —
        // `publishToMavenLocal` in xemantic-typescript-compiler puts it there — with the
        // SNAPSHOT its main branch publishes to Maven Central as the fallback, which is
        // also all a CI runner has. Both are scoped to the compiler's group, so that
        // neither can shadow Maven Central for any other dependency.
        mavenLocal {
            content { includeGroup("com.xemantic.typescript") }
        }
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            name = "MavenCentralSnapshots"
            mavenContent {
                snapshotsOnly()
                includeGroup("com.xemantic.typescript")
            }
        }
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
