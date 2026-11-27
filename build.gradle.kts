import org.jetbrains.changelog.Changelog

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.testing.Test
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

val integrationTestImplementation = configurations.getByName("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly = configurations.getByName("integrationTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

// The native materialization runtime proof uses an isolated resolvable PostgreSQL driver artifact.
// Do not resolve integrationTestRuntimeClasspath during build configuration: doing so
// freezes its parent configurations before the IntelliJ Starter task finishes wiring them.
val postgresMaterializationDriverClasspath = configurations.create("postgresMaterializationDriverClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isVisible = false
}

// The exact approved versions live only in the native Gradle catalog.
// Direct security constraints and the graph gate share the same dependency objects.
val starterSecurityConstraints = listOf(
    libs.bcprov.get() to "Starter tooling previously resolved a security-affected Bouncy Castle release",
    libs.bcpkix.get() to "keep Bouncy Castle Starter tooling modules version-aligned",
    libs.bcutil.get() to "keep Bouncy Castle Starter tooling modules version-aligned",
    libs.starterLz4.get() to "retain the security fix for GHSA-48mj-8f7c-7qfm",
    libs.starterJsoup.get() to "retain the XmlTreeBuilder resource-consumption security fix (862ba2f)",
)
val expectedPostgresDriver = libs.postgresDriver.get()
val starterSecurityModules = starterSecurityConstraints.map { it.first } + listOf(
    expectedPostgresDriver,
    libs.jackson2Core.get(),
    libs.jackson2Databind.get(),
    libs.jackson3Core.get(),
    libs.jackson3Databind.get(),
    libs.nettyHandler.get(),
    libs.nettyCompression.get(),
)

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

    // The root plugin packages the approved stock-MyBatis preparation boundary consumed by
    // the registered Leap execution authority.
    implementation(project(":mybatis-engine")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    testImplementation(libs.junit)
    testImplementation(platform(libs.jackson2Bom))
    testImplementation(platform(libs.jackson3Bom))
    testImplementation(platform(libs.otelBom))
    add(postgresMaterializationDriverClasspath.name, libs.postgresDriver)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
    intellijPlatform {
        val localPlatformPath = providers.gradleProperty("localPlatformPath")
        if (localPlatformPath.isPresent) {
            local(localPlatformPath.get())
        } else {
            intellijIdea(providers.gradleProperty("platformVersion")) {
                type.set(providers.gradleProperty("platformType").map(IntelliJPlatformType::valueOf))
            }
        }

        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")

        implementation(libs.mybatis)
    }

    // Keep the process-level Starter/JUnit 6 stack isolated from the existing JUnit 4 fixture
    // suite until the process harness is characterized and promoted.
    // Keep Jupiter and Platform artifacts on one tested release line. A BOM avoids
    // independent Dependabot PRs that can temporarily skew the Starter test stack.
    integrationTestImplementation(platform(libs.starterJunitBom))
    integrationTestImplementation("org.junit.jupiter:junit-jupiter")
    integrationTestImplementation(kotlin("stdlib"))
    integrationTestImplementation(libs.starterKodein)
    // JetBrains Starter uses CommonScope/EventsBus coroutines internally at test runtime.
    // This dependency is intentional even though project source does not import coroutines:
    // removing it fails Starter E2E with NoClassDefFoundError for kotlinx/coroutines/SupervisorKt.
    integrationTestImplementation(libs.starterCoroutines)
    // Process-E2E fixture only. pgJDBC is copied into the isolated Starter config
    // and registered as a local Database Tools driver; it is never part of the shipped plugin artifact.
    integrationTestImplementation(libs.postgresDriver)
    // JetBrains Starter's CommonScope loads kotlinx.coroutines.SupervisorKt at runtime, but
    // the Starter test framework does not supply coroutines transitively on this configuration.
    // Keep this explicit runtime prerequisite; removing it fails Starter / Driver E2E.

    // Security-align only the process-level Starter/E2E tooling graph. These are not plugin
    // runtime dependencies; remove the constraints when JetBrains' Starter graph carries
    // equivalent-or-newer fixed versions natively.
    integrationTestImplementation(platform(libs.nettyBom))
    integrationTestImplementation(platform(libs.jackson2Bom))
    integrationTestImplementation(platform(libs.jackson3Bom))
    integrationTestImplementation(platform(libs.otelBom))
    constraints {
        starterSecurityConstraints.forEach { (dependency, reason) ->
            add("integrationTestImplementation", dependency) {
                because(reason)
            }
        }
    }

    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
    integrationTestRuntimeOnly(libs.teamcityMessages)
    // The plugin build deliberately opts out of bundling Kotlin stdlib. Integration-test source
    // compiles against the matched stdlib above; reflection is needed only at runtime.
    integrationTestRuntimeOnly(kotlin("reflect"))
}

val verifyStarterSecurityGraph = tasks.register("verifyStarterSecurityGraph") {
    group = "verification"
    description = "Fail if the executable Starter/E2E runtime resolves security-stale tooling dependencies."
    // ResolutionResult is configuration-cache compatible. Capture it in task configuration,
    // not the surrounding build script/Project from the execution-time doLast action.
    // The exact resolved runtime dependency graph is still checked on every invocation.
    val securityResolutionResult = configurations.getByName("integrationTestRuntimeClasspath")
        .incoming.resolutionResult

    val expected = starterSecurityModules.associate { dependency ->
        "${dependency.module.group}:${dependency.module.name}" to dependency.versionConstraint.requiredVersion
    }
    require(expected.size == starterSecurityModules.size && expected.values.all { it.isNotBlank() }) {
        "Starter security catalog must define distinct modules with explicit versions"
    }

    doLast {
        fun verifyGraph(resolved: Map<String, String>) {
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

        val resolved = securityResolutionResult.allComponents
            .mapNotNull { component ->
                component.moduleVersion?.let { id -> "${id.group}:${id.name}" to id.version }
            }
            .toMap()

        // Real resolution is mandatory; negative controls cannot replace it.
        verifyGraph(resolved)
        val (module, version) = expected.entries.first()
        val missing = runCatching { verifyGraph(resolved - module) }.exceptionOrNull()
        check(missing is GradleException && missing.message == "Starter security graph is missing expected module $module")
        val wrong = "invalid-negative-control-version"
        val drift = runCatching { verifyGraph(resolved + (module to wrong)) }.exceptionOrNull()
        check(drift is GradleException &&
            drift.message == "Starter security graph drift for $module: expected $version, resolved $wrong")
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

    // Pin each maintained host explicitly. The 263-only editor-open contract
    // does not support the older 262 binary APIs. Do not weaken the verifier's
    // internal/override-only API failure levels to make this baseline pass.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, providers.gradleProperty("platformVersion").get())
            create(IntelliJPlatformType.DataGrip, providers.gradleProperty("dataGripPlatformVersion").get())
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

// Real-PostgreSQL native materialization proof. The task participates in
// verification, but is executable only when the E2E workflow explicitly enables the
// PostgreSQL service.
//
// Resolve the already-declared integration-test pgJDBC artifact to a plain String during
// configuration. Do not capture a Gradle Configuration/Provider in the TestIdeTask action:
// Gradle 9 configuration-cache rejects that task graph, while the transformed IDE test
// process also does not expose external test artifacts through java.class.path.
val postgresMaterializationDriverJarPath: String = postgresMaterializationDriverClasspath
    .incoming.artifacts.artifacts
    .single { artifact ->
        val component = artifact.id.componentIdentifier
        component is ModuleComponentIdentifier &&
            component.group == expectedPostgresDriver.module.group &&
            component.module == expectedPostgresDriver.module.name &&
            component.version == expectedPostgresDriver.versionConstraint.requiredVersion &&
            artifact.file.extension == "jar"
    }
    .file.absolutePath

val postgresMaterializationRuntimeTest = intellijPlatformTesting.testIde.register("postgresMaterializationRuntimeTest") {
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.Plugin.Java)
    plugins {
        disablePlugin("org.jetbrains.plugins.vue")
    }
    task {
        // IntelliJ Platform testIde registrations participate in the verification lifecycle.
        // The PostgreSQL service exists only in the dedicated E2E job, so ordinary check must
        // see this task as non-applicable rather than trying to connect to localhost:5432.
        onlyIf("PostgreSQL materialization runtime service is explicitly enabled") {
            System.getenv("ZMYBATIS_POSTGRES_MATERIALIZATION_RUNTIME") == "1"
        }
        systemProperty("zmybatis.postgresql.materialization.driverJar", postgresMaterializationDriverJarPath)
        filter {
            includeTestsMatching(
                "com.algorist.zMyBatis.execution.NativePostgreSqlMaterializationRuntimeTest",
            )
        }
        testLogging {
            events("passed", "failed")
            showStandardStreams = true
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
intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        dependsOn(verifyStarterSecurityGraph)
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
        // Starter 263 loads JetBrains MultiRoutingFsPath while unpacking IDE 262.
        // That class implements JDK-internal BasicFileAttributesHolder, so its
        // separate Gradle test JVM needs this exact export before any IDE starts.
        // Keep strict CIServer error reporting and all runtime assertions intact.
        jvmArgs("--add-exports=java.base/sun.nio.fs=ALL-UNNAMED")
        useJUnitPlatform()
        filter {
            includeTestsMatching("com.algorist.zMyBatis.e2e.ZMyBatisStarterDriverE2ETest")
        }
        testLogging {
            events("passed", "failed")
            showStandardStreams = true
        }
    }
}

// DataGrip is validated as a separate host rather than by re-labeling IDEA Ultimate evidence.
// The Starter dependencies supplied by IntelliJ Platform Gradle Plugin 2.19 include the 262+
// DataGrip product module, so the process test can use IdeInfo.DataGrip directly.
intellijPlatformTesting.testIdeUi.register("dataGripIntegrationTest") {
    type = IntelliJPlatformType.DataGrip
    version = providers.gradleProperty("dataGripPlatformVersion").get()

    task {
        dependsOn(verifyStarterSecurityGraph)
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
        // Starter 263 loads JetBrains MultiRoutingFsPath while unpacking IDE 262.
        // That class implements JDK-internal BasicFileAttributesHolder, so its
        // separate Gradle test JVM needs this exact export before any IDE starts.
        // Keep strict CIServer error reporting and all runtime assertions intact.
        jvmArgs("--add-exports=java.base/sun.nio.fs=ALL-UNNAMED")
        useJUnitPlatform()
        filter {
            includeTestsMatching("com.algorist.zMyBatis.e2e.ZMyBatisDataGripStarterDriverE2ETest")
        }
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
            disabledForTestTasks.add("dataGripIntegrationTest")
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
tasks.withType<Test>().configureEach {
    systemProperty("LowMemoryWatcherManager.REGULAR_TRACKER_UPDATE_PERIOD_MS", "-1")
    systemProperty("intellij.platform.log.sync", "true")
}

// IDEA 263: do not disable the Ultimate platform module in unit fixtures.
// The registered zMyBatis plugin depends on com.intellij.database; hiding the
// host's Ultimate module can prevent loading DatabaseTools and the plugin's
// own Java PSI capability extension points. Preserve genuine host-plugin loading.

// Keep the unit/PSI fixtures independent of a desktop session on every host.
// The separate Starter/Driver launcher retains its real UI process.
tasks.withType<Test>().configureEach {
    if (name != "integrationTest" && name != "dataGripIntegrationTest") {
        systemProperty("java.awt.headless", "true")
    }
}

tasks {
    test {
        filter {
            excludeTestsMatching("com.algorist.zMyBatis.JavaActionContextDiskFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.KotlinActionContextBoundaryTest")
            excludeTestsMatching("com.algorist.zMyBatis.execution.NativePostgreSqlMaterializationRuntimeTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.ActiveEditorSourceSnapshotAdapterProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.DependentMapperSourceSnapshotAdapterProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureAdapterProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest")
            excludeTestsMatching("com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureKotlinBoundaryTest")
        }
        testLogging {
            events("passed", "failed")
        }
        addTestOutputListener { _, event ->
            event.message
                .lineSequence()
                .filter {
                    it.startsWith("ZMYBATIS_XML_SCALE ") ||
                        it.startsWith("ZMYBATIS_INPUT_SCALE ") ||
                        it.startsWith("ZMYBATIS_RESOURCE_LIFECYCLE ")
                }
                .forEach { logger.lifecycle(it) }
        }
    }

    check {
        dependsOn(javaParserIndexTest, kotlinBoundaryTest, verifyStarterSecurityGraph)
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
