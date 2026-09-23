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

    // TeamCity's serviceMessages artifact is published only in JetBrains' TeamCity repository.
    // Restrict this repository to that group so normal dependency resolution is unaffected.
    maven {
        url = uri("https://download.jetbrains.com/teamcity-repository/")
        content {
            includeGroup("org.jetbrains.teamcity")
        }
    }

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
    testImplementation(platform("com.fasterxml.jackson:jackson-bom:2.21.6"))
    testImplementation(platform("tools.jackson:jackson-bom:3.1.6"))
    testImplementation(platform("io.opentelemetry:opentelemetry-bom:1.62.0"))

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

    // Keep the process-level Starter/JUnit 6 stack isolated from the existing JUnit 4 fixture
    // suite until the process harness is characterized and promoted.
    // Keep Jupiter and Platform artifacts on one tested release line. A BOM avoids
    // independent Dependabot PRs that can temporarily skew the Starter test stack.
    integrationTestImplementation(platform("org.junit:junit-bom:6.1.3"))
    integrationTestImplementation("org.junit.jupiter:junit-jupiter")
    integrationTestImplementation(kotlin("stdlib"))
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.33.0")
    // JetBrains Starter uses CommonScope/EventsBus coroutines internally at test runtime.
    // This dependency is intentional even though project source does not import coroutines:
    // removing it fails Starter E2E with NoClassDefFoundError for kotlinx/coroutines/SupervisorKt.
    // JetBrains Starter's CommonScope loads kotlinx.coroutines.SupervisorKt at runtime, but
    // the Starter test framework does not supply coroutines transitively on this configuration.
    // Keep this explicit runtime prerequisite; removing it fails Starter / Driver E2E.

    // Security-align only the process-level Starter/E2E tooling graph. These are not plugin
    // runtime dependencies; remove the constraints when JetBrains' Starter graph carries
    // equivalent-or-newer fixed versions natively.
    integrationTestImplementation(platform("io.netty:netty-bom:4.2.18.Final"))
    integrationTestImplementation(platform("com.fasterxml.jackson:jackson-bom:2.21.6"))
    integrationTestImplementation(platform("tools.jackson:jackson-bom:3.1.6"))
    integrationTestImplementation(platform("io.opentelemetry:opentelemetry-bom:1.62.0"))
    constraints {
        add("integrationTestImplementation", "org.bouncycastle:bcprov-jdk18on:1.86") {
            because("Starter tooling currently resolves a security-affected 1.84")
        }
        add("integrationTestImplementation", "org.bouncycastle:bcpkix-jdk18on:1.86") {
            because("keep Bouncy Castle Starter tooling modules version-aligned")
        }
        add("integrationTestImplementation", "org.bouncycastle:bcutil-jdk18on:1.86") {
            because("keep Bouncy Castle Starter tooling modules version-aligned")
        }
        add("integrationTestImplementation", "at.yawk.lz4:lz4-java:1.11.3") {
            because("1.11.3 includes the security fixes released in 1.11.2")
        }
        add("integrationTestImplementation", "org.jsoup:jsoup:1.23.2") {
            because("1.23.2 contains the XmlTreeBuilder resource-consumption fix commit 862ba2f")
        }
    }

    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
    integrationTestRuntimeOnly("org.jetbrains.teamcity:serviceMessages:2024.12")
    // The plugin build deliberately opts out of bundling Kotlin stdlib. Integration-test source
    // compiles against the matched stdlib above; reflection is needed only at runtime.
    integrationTestRuntimeOnly(kotlin("reflect"))
}

val verifyStarterSecurityGraph = tasks.register("verifyStarterSecurityGraph") {
    group = "verification"
    description = "Fail if the executable Starter/E2E runtime resolves security-stale tooling dependencies."
    // This proof intentionally resolves a live Gradle configuration during task execution.
    // Gradle recommends explicitly opting such tasks out of configuration-cache storage
    // rather than hiding serialization problems with configuration-cache warning mode.
    notCompatibleWithConfigurationCache(
        "Resolves integrationTestRuntimeClasspath at execution time for security evidence",
    )

    doLast {
        val expected = mapOf(
            "org.jsoup:jsoup" to "1.23.2",
            "com.fasterxml.jackson.core:jackson-core" to "2.21.6",
            "com.fasterxml.jackson.core:jackson-databind" to "2.21.6",
            "tools.jackson.core:jackson-core" to "3.1.6",
            "tools.jackson.core:jackson-databind" to "3.1.6",
            "io.netty:netty-handler" to "4.2.18.Final",
            "io.netty:netty-codec-compression" to "4.2.18.Final",
            "org.bouncycastle:bcprov-jdk18on" to "1.86",
            "org.bouncycastle:bcpkix-jdk18on" to "1.86",
            "org.bouncycastle:bcutil-jdk18on" to "1.86",
            "at.yawk.lz4:lz4-java" to "1.11.3",
        )
        val resolved = configurations.getByName("integrationTestRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                component.moduleVersion?.let { id -> "${id.group}:${id.name}" to id.version }
            }
            .toMap()

        expected.forEach { (module, version) ->
            val actual = resolved[module]
                ?: throw GradleException("Starter security graph is missing expected module $module")
            if (actual != version) {
                throw GradleException(
                    "Starter security graph drift for $module: expected $version, resolved $actual",
                )
            }
        }
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
        dependsOn(verifyStarterSecurityGraph)
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
        dependsOn(javaParserIndexTest, kotlinBoundaryTest, verifyStarterSecurityGraph)
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
