package com.algorist.zMyBatis.e2e

import com.intellij.driver.client.Driver
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
        private val pluginArchive: Path =
            pathOf(System.getProperty("path.to.build.plugin")).toAbsolutePath()
        private val sampleProject: Path =
            pathOf("src/integrationTest/testProject").toAbsolutePath()

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
                assertDataGripRuntimeReady()
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
                assertDataGripRuntimeReady()
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
        val starterLicense =
            System.getenv("LICENSE_KEY")
                ?.takeIf { it.isNotBlank() }

        val context =
            Starter.newContext(
                testName,
                TestCase(IdeInfo.DataGrip, LocalProjectInfo(projectDir)).useRelease(DATAGRIP_RELEASE),
            ).apply {
                starterLicense?.let { setLicense(it) }
                PluginConfigurator(this).installPluginFromPath(pluginArchive)
            }

        println(
            "ZMYBATIS_DATAGRIP_BOOTSTRAP_CONFIG host=DATAGRIP release=$DATAGRIP_RELEASE " +
                "starter_license_configured=${starterLicense != null}",
        )
        return context
    }

    private fun Driver.assertDataGripRuntimeReady() {
        waitFor("DataGrip plugins initialized", timeout = 2.minutes) {
            arePluginsInitialized()
        }

        val nativeTrialActionAvailable =
            service(TrialStateServiceRemote::class).getTrialAvailable().getValue()
        println(
            "ZMYBATIS_DATAGRIP_BOOTSTRAP host=DATAGRIP release=$DATAGRIP_RELEASE result=READY " +
                "native_trial_action_available=$nativeTrialActionAvailable",
        )
        assertTrue(
            nativeTrialActionAvailable != true,
            "DataGrip $DATAGRIP_RELEASE still reports a startable native trial; " +
                "the DataGrip harness must not invoke the IDEA-specific StartTrial action",
        )
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
