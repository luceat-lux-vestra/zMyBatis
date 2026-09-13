package com.algorist.zMyBatis.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.service
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
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

@Remote("com.algorist.zMyBatis.settings.ZMyBatisSettings", plugin = "com.algorist.zMyBatis")
private interface ZMyBatisSettingsRemote {
    fun getAutoFormatSql(): Boolean
}

class ZMyBatisStarterDriverE2ETest {

    companion object {
        private val pluginArchive: Path = pathOf(System.getProperty("path.to.build.plugin")).toAbsolutePath()
        private val sampleProject: Path = pathOf("src/integrationTest/testProject").toAbsolutePath()

        init {
            // Starter and the IDE run in separate processes. Make IDE-side exceptions/freezes fail
            // the JUnit process instead of allowing a false green.
            di = DI {
                extend(di)
                bindSingleton<CIServer>(overrides = true) {
                    object : CIServer by NoCIServer {
                        override fun reportTestFailure(
                            testName: String,
                            message: String,
                            details: String,
                            linkToLogs: String?,
                        ) {
                            fail { "$testName fails: $message\n$details" }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun packagedPluginLoadsAndProductionServiceIsCallable(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("service-project"))

        starterContext("production-service", projectDir)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                // This is the shipping application service, reached over Driver JMX/RMI. No
                // test-only production hook participates in the assertion.
                assertTrue(service<ZMyBatisSettingsRemote>().getAutoFormatSql())
            }
    }

    @Test
    fun registeredActionReachesProductionFailClosedPath(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-project"))

        starterContext("action-no-datasource", projectDir)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                openFile("Query.xml")

                ideFrame {
                    // Query.xml places the caret inside a real MyBatis statement. With no
                    // datasource configured, the production action must stop before evaluation or
                    // execution and expose its real fail-closed datasource refusal.
                    invokeAction("zMyBatis.Execute", now = false)
                    dialog(title = "zMyBatis: No Data Source") {
                        button("OK").click()
                    }
                    waitForNoOpenedDialogs()
                }
            }
    }

    private fun starterContext(testName: String, projectDir: Path) =
        Starter.newContext(
            testName,
            TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectDir)),
        ).apply {
            System.getenv("LICENSE_KEY")
                ?.takeIf { it.isNotBlank() }
                ?.let { setLicense(it) }
            PluginConfigurator(this).installPluginFromPath(pluginArchive)
        }

    private fun copySampleProject(destination: Path): Path {
        require(Files.isDirectory(sampleProject)) { "Missing versioned E2E sample project: $sampleProject" }

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
}
