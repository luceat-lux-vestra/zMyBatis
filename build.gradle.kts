import org.jetbrains.changelog.Changelog

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
val effectivePluginVersion = providers.gradleProperty("pluginVersion").orElse("0.0.0-dev")
version = effectivePluginVersion.get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    gradlePluginPortal()

    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion")) {
            type.set(providers.gradleProperty("platformType").map(IntelliJPlatformType::valueOf))
        }

        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)

        implementation("org.mybatis:mybatis:3.5.19")
    }
}

// Configure IntelliJ Platform Gradle Plugin.
intellijPlatform {
    buildSearchableOptions = true
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = effectivePluginVersion

        val changelog = project.changelog // local variable for configuration cache compatibility
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (runCatching { getLatest() }.getOrNull() ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        vendor {
            name = "algorist"
            url = "https://github.com/luceat-lux-vestra/zMyBatis"
            email = "heathkimdev@gmail.com"
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = effectivePluginVersion.map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    // Keep compatibility verification deterministic. `recommended()` drifts as
    // JetBrains publishes new IDE builds and can turn the merge gate into a
    // moving target. Broader IDEA/DataGrip coverage must be added explicitly
    // with evidence rather than inferred from this single maintained target.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.3")
        }
    }
}

// Configure Gradle Changelog Plugin.
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

// Configure Gradle Kover Plugin.
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    patchPluginXml {
        val readme = projectDir.resolve("README.md").readText()
        val start = "<!-- Plugin description -->"
        val end = "<!-- Plugin description end -->"
        val lines = readme.lines()
        if (!lines.containsAll(listOf(start, end))) {
            throw GradleException("Plugin description section not found in README.md")
        }
        val description = lines.subList(lines.indexOf(start) + 1, lines.indexOf(end)).joinToString("\n").trim()
        pluginDescription.set(description)
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
