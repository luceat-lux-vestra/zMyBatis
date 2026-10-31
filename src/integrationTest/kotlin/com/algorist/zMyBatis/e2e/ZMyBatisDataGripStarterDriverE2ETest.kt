package com.algorist.zMyBatis.e2e

import com.intellij.driver.sdk.arePluginsInitialized
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.ide.starter.runner.startIdeWithoutProject
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import com.intellij.tools.ide.starter.product.datagrip.DataGrip
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path as pathOf
import kotlin.time.Duration.Companion.minutes

/**
 * Separate DataGrip host evidence.
 *
 * IDEA Ultimate evidence is intentionally not reused as proof here. This class launches the exact
 * packaged plugin in DataGrip 2026.2.3, uses a DataGrip-specific activation seed, and exercises only
 * the XML/Database Tools action boundary that DataGrip can credibly own. Java-source parity remains
 * outside this host proof.
 */
class ZMyBatisDataGripStarterDriverE2ETest {

    companion object {
        private const val DATAGRIP_RELEASE = "2026.2.3"
        private const val DATAGRIP_LICENSE_FILE = "datagrip.key"
        private val pluginArchive: Path =
            pathOf(System.getProperty("path.to.build.plugin")).toAbsolutePath()
        private val sampleProject: Path =
            pathOf("src/integrationTest/testProject").toAbsolutePath()
        private val activationSeedLock = Any()

        @Volatile
        private var sharedActivationSeedConfigDir: Path? = null

        init {
            // DataGrip gets the strict failure boundary with no IDEA-only exception exemption.
            di = DI {
                extend(di)
                bindSingleton<CIServer>(overrides = true) {
                    object : CIServer by NoCIServer {
                        override fun reportTestFailure(
                            testName: String,
                            message: String,
                            details: String,
                            linkToLogs: String?,
                            kind: SyntheticTestKind,
                            generifyTestName: Boolean,
                        ) {
                            fail {
                                "DataGrip $DATAGRIP_RELEASE: $testName fails: $message\n$details"
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun packagedPluginLoadsAndProductionServiceIsCallableOnDataGrip(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("datagrip-service-project"))
        val startedAt = System.nanoTime()

        dataGripStarterContext("datagrip-production-service", projectDir)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                waitFor("zMyBatis plugin loaded in DataGrip", timeout = 2.minutes) {
                    isPluginLoaded("com.algorist.zMyBatis")
                }
                assertTrue(
                    service(ZMyBatisSettingsRemote::class).getAutoFormatSql(),
                    "shipping zMyBatis settings service must be callable in DataGrip",
                )
            }

        println(
            "ZMYBATIS_HOST_EVIDENCE host=DATAGRIP release=$DATAGRIP_RELEASE " +
                "scenario=service-load result=PASS plugin_loaded=true service_callable=true " +
                "clean_shutdown=true duration_ms=${elapsedMs(startedAt)}",
        )
    }

    @Test
    fun registeredXmlActionReachesTypedFailClosedBoundaryOnDataGrip(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("datagrip-action-project"))
        val startedAt = System.nanoTime()

        dataGripStarterContext("datagrip-action-no-datasource", projectDir)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openFile("Query.xml")

                ideFrame {
                    // Exercise the same registered production action boundary as the maintained IU
                    // refusal case. No datasource is created: DataGrip must remain responsive and
                    // fail closed at target resolution instead of crashing or inventing execution.
                    invokeAction("EditorDown")
                    invokeAction("zMyBatis.Execute", now = false)
                    dialog(title = "zMyBatis: Execution Target Unavailable") {
                        button("OK").click()
                    }
                    waitForNoOpenedDialogs()
                }
            }

        println(
            "ZMYBATIS_HOST_EVIDENCE host=DATAGRIP release=$DATAGRIP_RELEASE " +
                "scenario=xml-action-no-datasource result=PASS action_registered=true " +
                "typed_refusal=true clean_shutdown=true duration_ms=${elapsedMs(startedAt)}",
        )
    }

    private fun dataGripStarterContext(
        testName: String,
        projectDir: Path,
    ): IDETestContext {
        val context =
            Starter.newContext(
                testName,
                TestCase(IdeInfo.DataGrip, LocalProjectInfo(projectDir)).useRelease(DATAGRIP_RELEASE),
            ).apply {
                System.getenv("LICENSE_KEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setLicense(it) }
                PluginConfigurator(this).installPluginFromPath(pluginArchive)
            }

        val seedKey = dataGripActivationSeedConfigDir().resolve(DATAGRIP_LICENSE_FILE)
        Files.createDirectories(context.paths.configDir)
        Files.copy(
            seedKey,
            context.paths.configDir.resolve(DATAGRIP_LICENSE_FILE),
            StandardCopyOption.REPLACE_EXISTING,
        )
        assertTrue(
            Files.isRegularFile(context.paths.configDir.resolve(DATAGRIP_LICENSE_FILE)),
            "DataGrip activation seed must materialize an independent licensed scenario config",
        )
        return context
    }

    private fun dataGripActivationSeedConfigDir(): Path {
        sharedActivationSeedConfigDir?.let { return it }
        return synchronized(activationSeedLock) {
            sharedActivationSeedConfigDir
                ?: createDataGripActivationSeedConfig().also {
                    sharedActivationSeedConfigDir = it
                }
        }
    }

    private fun createDataGripActivationSeedConfig(): Path {
        val seedContext =
            Starter.newContext(
                "datagrip-activation-seed",
                TestCase(IdeInfo.DataGrip, LocalProjectInfo(sampleProject))
                    .useRelease(DATAGRIP_RELEASE),
            ).apply {
                System.getenv("LICENSE_KEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setLicense(it) }
                PluginConfigurator(this).installPluginFromPath(pluginArchive)
            }

        activateDataGripBeforeOpeningProject(seedContext)
        assertTrue(
            Files.isRegularFile(seedContext.paths.configDir.resolve(DATAGRIP_LICENSE_FILE)),
            "DataGrip activation must persist before project scenarios start",
        )
        return seedContext.paths.configDir
    }

    private fun activateDataGripBeforeOpeningProject(context: IDETestContext) {
        context.runIdeWithDriver(
            commandLine = ::startIdeWithoutProject,
            launchName = "datagrip-activation",
        ).useDriverAndCloseIde {
            waitFor("DataGrip plugins initialized", timeout = 2.minutes) {
                arePluginsInitialized()
            }

            if (!Files.isRegularFile(context.paths.configDir.resolve(DATAGRIP_LICENSE_FILE))) {
                waitFor("DataGrip native trial action available", timeout = 2.minutes) {
                    service(TrialStateServiceRemote::class).getTrialAvailable().getValue() == true
                }
                invokeAction("StartTrial", now = false)
                waitFor("DataGrip trial activation complete", timeout = 2.minutes) {
                    service(TrialStateServiceRemote::class).getTrialAvailable().getValue() != true
                }
            }
        }
    }

    private fun copySampleProject(destination: Path): Path {
        require(Files.isDirectory(sampleProject)) {
            "Missing versioned E2E sample project: $sampleProject"
        }

        Files.walk(sampleProject).use { paths ->
            paths.forEach { source ->
                val relative = sampleProject.relativize(source)
                val target = destination.resolve(relative.toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    target.parent?.let(Files::createDirectories)
                    Files.copy(source, target)
                }
            }
        }
        return destination
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000
}
