package com.algorist.zMyBatis.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.arePluginsInitialized
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.Finder
import com.intellij.driver.sdk.ui.components.ComponentData
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.accessibleTable
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.popups
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.ui.ui
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
import com.intellij.ide.starter.runner.startIdeWithoutProject
import com.intellij.ide.starter.ide.IDETestContext
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
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path as pathOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Remote("com.algorist.zMyBatis.settings.ZMyBatisSettings", plugin = "com.algorist.zMyBatis")
interface ZMyBatisSettingsRemote {
    fun getAutoFormatSql(): Boolean
    fun setAutoFormatSql(value: Boolean)
    fun setSqlPreview(value: Boolean)
}

@Remote("javax.swing.text.JTextComponent")
interface JTextComponentRemote {
    fun getText(): String
}

@Remote(
    "com.intellij.platform.trialPromotion.common.TrialStateService",
    plugin = "com.intellij/intellij.platform.trialPromotion.common",
)
interface TrialStateServiceRemote {
    fun getTrialAvailable(): TrialAvailabilityRemote
}

@Remote("kotlinx.coroutines.flow.StateFlow")
interface TrialAvailabilityRemote {
    fun getValue(): Boolean?
}

private class JTextAreaUi(data: ComponentData) : UiComponent(data) {
    val text: String
        get() = driver.cast(component, JTextComponentRemote::class).getText()
}

private fun Finder.textArea(): JTextAreaUi =
    x("//div[@class='JBTextArea']", JTextAreaUi::class.java)

class ZMyBatisStarterDriverE2ETest {

    companion object {
        private const val IDE_RELEASE = "2026.2.3"
        private const val E2E_DATA_SOURCE_UUID = "4c6e150e-3d84-4a71-9d85-0a6d05d42e01"
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
         * IDEA 2026.2 reported IJPL-222870 while initializing the bundled Islands Dark
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
        writeH2DataSourceFixture(projectDir)

        val context = starterContext("action-real-database", projectDir)
        writeH2DatabaseToolsDriverFixture(context.paths.configDir, h2Jar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

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
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem("zMyBatis E2E", fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard {
                    // The datasource row is an action group. Opening it selects its first enabled
                    // child, which is the production "Use Default Schema" action.
                    right()
                    enter()
                }

                ui.dialog(title = "Enter MyBatis Parameters") {
                    textField().text = "7"
                    button("OK").click()
                }

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    val previewSql = textArea().text
                    assertTrue(
                        previewSql.contains("SELECT 7 AS RESULT_VALUE"),
                        "shipping preview must expose the resolved parameterized SQL, got: <$previewSql>",
                    )
                    button("Execute").click()
                }

                waitFor(
                    message = "Database Tools result grid contains the executed value",
                    timeout = 2.minutes,
                ) {
                    // Driver resolves the component lazily from content(). Its default component
                    // lookup is only 15s, so let the outer 2-minute readiness loop own retries
                    // while Database Tools creates and attaches the native result grid.
                    try {
                        ui.accessibleTable {
                            byType("com.intellij.database.run.ui.table.TableResultView")
                        }.content().values.any { row ->
                            row.values.any { cell -> cell.trim() == "7" }
                        }
                    } catch (_: com.intellij.driver.sdk.WaitForException) {
                        false
                    }
                }
                ui.waitForNoOpenedDialogs()
            }
    }

    @Test
    fun registeredRawInterpolationForcesPreviewWhenSettingDisabled(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-raw-confirmation-project"))
        val rawToken = buildString {
            append(36.toChar())
            append("{value}")
        }
        Files.writeString(
            projectDir.resolve("Query.xml"),
            """
            <mapper namespace="fixture.QueryMapper">
            <select id="ping">SELECT $rawToken AS RESULT_VALUE</select>
            </mapper>
            """.trimIndent(),
        )

        val h2Jar = Path.of(H2Driver::class.java.protectionDomain.codeSource.location.toURI())
        writeH2DataSourceFixture(projectDir)

        val context = starterContext("action-raw-confirmation", projectDir)
        writeH2DatabaseToolsDriverFixture(context.paths.configDir, h2Jar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(false)
                }

                openFile("Query.xml")
                ideFrame {
                    invokeAction("EditorDown")
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem("zMyBatis E2E", fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard {
                    right()
                    enter()
                }

                ui.dialog(title = "Enter MyBatis Parameters") {
                    textField().text = "7"
                    button("OK").click()
                }

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    val previewSql = textArea().text
                    assertTrue(
                        previewSql.contains("SELECT 7 AS RESULT_VALUE"),
                        "raw interpolation must force final-SQL preview even when sqlPreview=false, got: <$previewSql>",
                    )
                    button("Cancel").click()
                }
                ui.waitForNoOpenedDialogs()

                val resultGridOpened = try {
                    ui.accessibleTable {
                        byType("com.intellij.database.run.ui.table.TableResultView")
                    }.content()
                    true
                } catch (_: com.intellij.driver.sdk.WaitForException) {
                    false
                }
                assertFalse(
                    resultGridOpened,
                    "cancelling the mandatory raw-interpolation preview must not execute the query",
                )
            }
    }

    @Test
    fun registeredMutationDeclarationForcesPreviewWhenSettingDisabled(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-mutation-confirmation-project"))
        Files.writeString(
            projectDir.resolve("Query.xml"),
            """
            <mapper namespace="fixture.QueryMapper">
            <update id="ping">SELECT 9 AS MUTATION_DECLARATION_PROBE</update>
            </mapper>
            """.trimIndent(),
        )

        val h2Jar = Path.of(H2Driver::class.java.protectionDomain.codeSource.location.toURI())
        writeH2DataSourceFixture(projectDir)

        val context = starterContext("action-mutation-confirmation", projectDir)
        writeH2DatabaseToolsDriverFixture(context.paths.configDir, h2Jar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(false)
                }

                openFile("Query.xml")
                ideFrame {
                    invokeAction("EditorDown")
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem("zMyBatis E2E", fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard {
                    right()
                    enter()
                }

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    val previewSql = textArea().text
                    assertTrue(
                        previewSql.contains("SELECT 9 AS MUTATION_DECLARATION_PROBE"),
                        "mutation declaration must force final-SQL preview even when sqlPreview=false, got: <$previewSql>",
                    )
                    button("Cancel").click()
                }
                ui.waitForNoOpenedDialogs()

                val resultGridOpened = try {
                    ui.accessibleTable {
                        byType("com.intellij.database.run.ui.table.TableResultView")
                    }.content()
                    true
                } catch (_: com.intellij.driver.sdk.WaitForException) {
                    false
                }
                assertFalse(
                    resultGridOpened,
                    "cancelling the mandatory mutation preview must not execute the query",
                )
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
    ) =
        Starter.newContext(
            testName,
            TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectDir)).useRelease(IDE_RELEASE),
        ).apply {
            System.getenv("LICENSE_KEY")
                ?.takeIf { it.isNotBlank() }
                ?.let { setLicense(it) }
            PluginConfigurator(this).installPluginFromPath(pluginArchive)
            activateBeforeOpeningProject(this)
        }

    private fun activateBeforeOpeningProject(context: IDETestContext) {
        // Keep the normal license/trial flow, but complete it before project-tree reads begin.
        // On 2026.2.3 activation dynamically reloads schemes; overlapping project startup races them.
        context.runIdeWithDriver(commandLine = ::startIdeWithoutProject, launchName = "activation")
            .useDriverAndCloseIde {
                waitFor("application plugins initialized", timeout = 2.minutes) { arePluginsInitialized() }
                if (!isPluginLoaded("com.intellij.modules.ultimate")) {
                    waitFor("native trial action available", timeout = 2.minutes) {
                        service(TrialStateServiceRemote::class).getTrialAvailable().getValue() == true
                    }
                    // The release's native action starts its normal trial without a project.
                    // Register opens subscription management, which has no trial tab in 2026.2.3.
                    invokeAction("StartTrial", now = false)
                }
                waitFor("Ultimate activation complete before project startup", timeout = 2.minutes) {
                    isPluginLoaded("com.intellij.modules.ultimate") && arePluginsInitialized()
                }
            }
        assertTrue(
            Files.isRegularFile(context.paths.configDir.resolve("idea.key")),
            "activation must persist its state before the project run",
        )
    }

    private fun writeH2DatabaseToolsDriverFixture(configDir: Path, h2Jar: Path) {
        // Database Tools does not use the IDE process classpath as its JDBC driver library.
        // Seed the Starter config exactly as a user-supplied local Driver Files JAR so the
        // native Database Tools driver classloader owns the real H2 connection.
        val driverDir = Files.createDirectories(configDir.resolve("jdbc-drivers"))
        val driverJar = driverDir.resolve(h2Jar.fileName)
        Files.copy(h2Jar, driverJar, StandardCopyOption.REPLACE_EXISTING)

        val optionsDir = Files.createDirectories(configDir.resolve("options"))
        Files.writeString(
            optionsDir.resolve("databaseDrivers.xml"),
            """
            <application>
              <component name="LocalDatabaseDriverManager" version="201">
                <driver id="h2.unified">
                  <artifact use="false" />
                  <option name="auto-sync" value="true" />
                  <library>
                    <url>${driverJar.toUri().toASCIIString()}</url>
                  </library>
                </driver>
              </component>
            </application>
            """.trimIndent(),
        )
    }

    private fun writeH2DataSourceFixture(projectDir: Path) {
        val ideaDir = Files.createDirectories(projectDir.resolve(".idea"))
        val projectFileDirMacro =
            buildString {
                append(36.toChar())
                append("ProjectFileDir")
                append(36.toChar())
            }
        Files.writeString(
            ideaDir.resolve("dataSources.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="DataSourceManagerImpl" format="xml" multifile-model="true">
                <data-source source="LOCAL" name="zMyBatis E2E" uuid="$E2E_DATA_SOURCE_UUID">
                  <driver-ref>h2.unified</driver-ref>
                  <synchronize>true</synchronize>
                  <jdbc-driver>org.h2.Driver</jdbc-driver>
                  <jdbc-url>jdbc:h2:mem:zmybatis_e2e;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=</jdbc-url>
                  <working-dir>$projectFileDirMacro</working-dir>
                </data-source>
              </component>
            </project>
            """.trimIndent(),
        )
        Files.writeString(
            ideaDir.resolve("dataSources.local.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="dataSourceStorageLocal">
                <data-source name="zMyBatis E2E" uuid="$E2E_DATA_SOURCE_UUID">
                  <auth-provider>no-auth</auth-provider>
                </data-source>
              </component>
            </project>
            """.trimIndent(),
        )
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
