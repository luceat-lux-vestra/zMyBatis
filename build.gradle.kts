import org.jetbrains.changelog.Changelog

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
// Ordinary development builds retain the historical timestamp version. Release
// workflows must pass -PbuildVersion=<release-tag> so the reviewed tag, plugin
// metadata, Marketplace publication, and uploaded distribution share one identity.
val currentVersion = providers.gradleProperty("buildVersion").orNull
    ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy.MM.dd.HHmmss"))
version = currentVersion

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

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

intellijPlatform {
    buildSearchableOptions = true
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = currentVersion

        val changelog = project.changelog
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
            url = "https://github.com/luceat-lux-vestra"
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
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

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

    publishPlugin {
        dependsOn(patchChangelog)
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
