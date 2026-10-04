package com.algorist.zMyBatis.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.accessibleTable
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.popups
import com.intellij.driver.sdk.ui.components.elements.textComponent
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.waitFor
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
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import org.h2.Driver as H2Driver
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path as pathOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Remote("com.algorist.zMyBatis.settings.ZMyBatisSettings", plugin = "com.algorist.zMyBatis")
interface ZMyBatisSettingsRemote {
    fun getAutoFormatSql(): Boolean
    fun setAutoFormatSql(value: Boolean)
    fun setSqlPreview(value: Boolean)
}

@Remote("com.intellij.database.dataSource.LocalDataSource", plugin = "com.intellij.database")
interface LocalDataSourceRemote {
    fun getName(): String
    fun setUsername(username: String)
}

@Remote("com.intellij.database.dataSource.LocalDataSource", plugin = "com.intellij.database")
interface LocalDataSourceFactoryRemote {
    fun create(
        name: String,
        driverClass: String,
        url: String,
        uniqueName: String,
    ): LocalDataSourceRemote
}

@Remote("com.intellij.database.dataSource.LocalDataSourceManager", plugin = "com.intellij.database")
interface LocalDataSourceManagerRemote {
    fun addDataSource(dataSource: LocalDataSourceRemote)
}

@Remote("com.intellij.database.dataSource.LocalDataSourceManager", plugin = "com.intellij.database")
interface LocalDataSourceManagerFactoryRemote {
    fun getInstance(project: Project): LocalDataSourceManagerRemote
}

class ZMyBatisStarterDriverE2ETest {

    companion object {
        private const val IDE_RELEASE = "2026.2"
        private const val KNOWN_ISLANDS_ISSUE = "IJPL-222870"
        private const val ISLANDS_FAILURE_PREFIX = "Theme Islands Dark refers to unknown color scheme"
        private const val ISLANDS_FAILURE_STACK =
            "com.intellij.openapi.editor.colors.impl.EditorColorsManagerImpl.getSchemeForCurrentUITheme"
        private val pluginArchive: Path = pathOf(System.getProperty("path.to.build.plugin")).toAbsolutePath()
        private val sampleProject: Path = pathOf("src/integrationTest/testProject").toAbsolutePath()

        init {
            // Starter and the IDE run in separate processes. Make IDE-side exceptions/freezes fail
            // the JUnit process instead of allowing a false green.
            di = DI {
                extend(di)
                bindSingleton<CIServer>(overrides = true) { strictCIServer() }
            }
        }

        // Starter 262 exposes SyntheticTestKind from its internal TeamCity reporter as a required
        // CIServer method parameter. Keep the unavoidable unstable API usage inside this adapter;
        // production code and the rest of the E2E harness do not depend on that internal type.
        @Suppress("UnstableApiUsage")
        private fun strictCIServer(): CIServer =
            object : CIServer by NoCIServer {
                override fun reportTestFailure(
                    testName: String,
                    message: String,
                    details: String,
                    linkToLogs: String?,
                    kind: SyntheticTestKind,
                    generifyTestName: Boolean,
                ) {
                    if (isKnownPinnedIdePlatformFailure(message, details)) {
                        System.err.println(
                            "Ignoring known IntelliJ IDEA $IDE_RELEASE platform failure $KNOWN_ISLANDS_ISSUE; " +
                                "the exact exception remains preserved in Starter diagnostics.",
                        )
                        return
                    }
                    fail { "$testName fails: $message\n$details" }
                }
            }

        /**
         * IDEA 2026.2 currently reports IJPL-222870 while initializing the bundled Islands Dark
         * theme: the UI theme can refer to a color scheme that has not been registered yet. This
         * is an upstream platform startup defect, not a zMyBatis exception.
         *
         * Keep the mapping deliberately two-factor: both the exact failure family and the platform
         * stack frame must be present. Any other Logger.error, crash, freeze, or Driver failure
         * continues through the strict CIServer failure path.
         */
        private fun isKnownPinnedIdePlatformFailure(message: String, details: String): Boolean {
            val combined = "$message\n$details"
            return combined.contains(ISLANDS_FAILURE_PREFIX) &&
                combined.contains("Islands Dark") &&
                details.contains(ISLANDS_FAILURE_STACK)
        }
    }

    @Test
    fun knownIslandsPlatformFailureIsNarrowlyMapped() {
        assertTrue(
            isKnownPinnedIdePlatformFailure(
                "Theme Islands Dark refers to unknown color scheme Islands Dark",
                "java.lang.Throwable\n\tat $ISLANDS_FAILURE_STACK(EditorColorsManagerImpl.kt:266)",
            ),
        )
        assertFalse(
            isKnownPinnedIdePlatformFailure(
                "Theme Islands Dark refers to unknown color scheme Islands Dark",
                "java.lang.Throwable\n\tat com.algorist.zMyBatis.PluginCode.fail(PluginCode.kt:1)",
            ),
        )
        assertFalse(
            isKnownPinnedIdePlatformFailure(
                "Unrelated IDE failure",
                "java.lang.Throwable\n\tat $ISLANDS_FAILURE_STACK(EditorColorsManagerImpl.kt:266)",
            ),
        )
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
                assertTrue(service(ZMyBatisSettingsRemote::class).getAutoFormatSql())
            }
    }

    @Test
    fun registeredActionExecutesParameterizedQueryThroughRealDatabaseTools(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-database-project"))
        val h2Jar = Path.of(H2Driver::class.java.protectionDomain.codeSource.location.toURI())

        starterContext("action-real-database", projectDir, additionalIdeClasspath = h2Jar)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                val dataSource = utility<LocalDataSourceFactoryRemote>().create(
                    "zMyBatis E2E",
                    "org.h2.Driver",
                    "jdbc:h2:mem:zmybatis_e2e;DB_CLOSE_DELAY=-1",
                    "zmybatis-e2e",
                )
                dataSource.setUsername("sa")
                utility<LocalDataSourceManagerFactoryRemote>()
                    .getInstance(singleProject())
                    .addDataSource(dataSource)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(true)
                }

                openFile("Query.xml")
                ideFrame {
                    invokeAction("EditorDown")
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                ui.popup().list().clickItem("zMyBatis E2E", fullMatch = false)

                waitFor("zMyBatis datasource schema chooser", timeout = 30.seconds) {
                    ui.popups().list().size >= 2
                }
                ui.popups().list().last().list().clickItem("Use Default Schema", fullMatch = true)

                dialog(title = "Enter MyBatis Parameters") {
                    textField().text = "7"
                    button("OK").click()
                }

                dialog(title = "zMyBatis — SQL Preview") {
                    val previewSql = textComponent().text
                    assertTrue(
                        previewSql.contains("SELECT 7 AS RESULT_VALUE"),
                        "shipping preview must expose the resolved parameterized SQL, got: <$previewSql>",
                    )
                    button("Execute").click()
                }

                val resultTable = accessibleTable {
                    byType("com.intellij.database.run.ui.table.TableResultView")
                }
                resultTable.findCell(timeout = 2.minutes) { cell ->
                    cell.trim() == "7"
                }
                waitForNoOpenedDialogs()
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
                    // openFile starts at the document origin. Move through the real editor action
                    // to the canonical mapper statement before invoking the context-gated plugin
                    // action, then prove the production no-datasource refusal is still reached.
                    invokeAction("EditorDown")
                    invokeAction("zMyBatis.Execute", now = false)
                    dialog(title = "zMyBatis: No Data Source") {
                        button("OK").click()
                    }
                    waitForNoOpenedDialogs()
                }
            }
    }

    private fun starterContext(
        testName: String,
        projectDir: Path,
        additionalIdeClasspath: Path? = null,
    ) =
        Starter.newContext(
            testName,
            TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectDir)).useRelease(IDE_RELEASE),
        ).apply {
            additionalIdeClasspath?.let { classpath ->
                applyVMOptionsPatch {
                    addSystemProperty("idea.additional.classpath", classpath)
                }
            }
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
