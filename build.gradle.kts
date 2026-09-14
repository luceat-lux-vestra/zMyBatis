import org.jetbrains.changelog.Changelog

import org.gradle.jvm.toolchain.JavaLanguageVersion
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

// Java 25 is the single supported build/runtime target for the plugin and test harness.
kotlin {
    jvmToolchain(25)
}

// Keep process-level Starter/Driver tests isolated from the existing JUnit 4 fixture suite.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
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

dependencies {
    implementation(project(":core")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

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
        testFramework(TestFrameworkType.Plugin.Java)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")

        implementation("org.mybatis:mybatis:3.5.19")
    }

    // Starter is JUnit 5-only. Pin the small integration-test stack independently from the
    // existing JUnit 4 fixture suite until the process harness is characterized and promoted.
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
    integrationTestRuntimeOnly("org.jetbrains.teamcity:serviceMessages:2024.12")
    // The plugin build deliberately opts out of bundling Kotlin stdlib. Starter/JUnit5 runs in a
    // separate test JVM and requires a matched stdlib/reflect pair there, so add both only to that
    // runtime using the Kotlin plugin's exact version.
    integrationTestRuntimeOnly(kotlin("stdlib"))
    integrationTestRuntimeOnly(kotlin("reflect"))
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
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2")
        }
    }
}

// Run the real Java PSI/project fixtures in their own sandbox. IDEA Ultimate
// bundles Vue support, whose resource lookup is unrelated to this Java boundary
// and currently fails under the transformed Gradle test distribution.
val javaParserIndexTest = intellijPlatformTesting.testIde.register("javaParserIndexTest") {
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.Plugin.Java)
    plugins {
        disablePlugin("org.jetbrains.plugins.vue")
    }
    task {
        filter {
            includeTestsMatching("com.algorist.zMyBatis.AnnotationSqlExtractorProjectFixtureTest")
            includeTestsMatching("com.algorist.zMyBatis.JavaActionContextProjectFixtureTest")
            includeTestsMatching("com.algorist.zMyBatis.JavaActionContextDiskFixtureTest")
            includeTestsMatching("com.algorist.zMyBatis.source.ActiveEditorSourceSnapshotAdapterProjectFixtureTest")
            includeTestsMatching("com.algorist.zMyBatis.source.DependentMapperSourceSnapshotAdapterProjectFixtureTest")
            includeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureAdapterProjectFixtureTest")
            includeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest")
        }
        testLogging {
            events("passed", "failed")
        }
    }
}

// Run the Kotlin boundary characterization with the real bundled Kotlin plugin,
// without adding Kotlin as a production plugin dependency or claiming support.
val kotlinBoundaryTest = intellijPlatformTesting.testIde.register("kotlinBoundaryTest") {
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.Plugin.Java)
    plugins {
        bundledPlugin("org.jetbrains.kotlin")
        disablePlugin("org.jetbrains.plugins.vue")
    }
    task {
        filter {
            includeTestsMatching("com.algorist.zMyBatis.KotlinActionContextBoundaryTest")
            includeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureKotlinBoundaryTest")
        }
        testLogging {
            events("passed", "failed")
        }
    }
}

// Launch an actual IDE process with the exact buildPlugin archive installed. Keep this task
// separate from `check`: #130 requires process-level evidence to remain independently visible.
val integrationTest by intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
        useJUnitPlatform()
        testLogging {
            events("passed", "failed")
            showStandardStreams = true
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
    currentProject {
        instrumentation {
            // Process-level Starter/Driver evidence is independent from the normal `check`
            // lifecycle and must not be pulled into Kover's default "all JVM test tasks" graph.
            disabledForTestTasks.add("integrationTest")
        }
    }
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

// IntelliJ's LowMemoryWatcherManager schedules a periodic GC tracker on the
// application scheduler. In the test harness that task can race AsyncLog teardown
// after all assertions have passed. Disable only that periodic tracker in test
// JVMs; logging and genuine Logger.error failures remain untouched.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("LowMemoryWatcherManager.REGULAR_TRACKER_UPDATE_PERIOD_MS", "-1")
    systemProperty("intellij.platform.log.sync", "true")
}

tasks {
    test {
        filter {
            excludeTestsMatching("com.algorist.zMyBatis.AnnotationSqlExtractorProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.JavaActionContextProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.JavaActionContextDiskFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.KotlinActionContextBoundaryTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.ActiveEditorSourceSnapshotAdapterProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.DependentMapperSourceSnapshotAdapterProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureAdapterProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureKotlinBoundaryTest")
        }
        testLogging {
            events("passed", "failed")
        }
    }

    check {
        dependsOn(javaParserIndexTest, kotlinBoundaryTest)
    }

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
