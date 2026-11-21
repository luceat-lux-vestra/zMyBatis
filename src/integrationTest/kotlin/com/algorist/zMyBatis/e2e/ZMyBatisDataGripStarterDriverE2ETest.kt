package com.algorist.zMyBatis.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.arePluginsInitialized
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.Finder
import com.intellij.driver.sdk.ui.components.ComponentData
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.ultimate.dataSourcesAndDriversDialog
import com.intellij.driver.sdk.ui.components.elements.accessibleTable
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.isDialogOpened
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
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.TestMetadata
import com.intellij.tools.ide.starter.product.datagrip.DataGrip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.postgresql.Driver as PostgreSqlDriver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path as pathOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Remote("javax.swing.text.JTextComponent")
private interface DataGripJTextComponentRemote {
    fun getText(): String
    fun setText(value: String)
}

private class DataGripJTextAreaUi(data: ComponentData) : UiComponent(data) {
    var text: String
        get() = driver.cast(component, DataGripJTextComponentRemote::class).getText()
        set(value) {
            driver.cast(component, DataGripJTextComponentRemote::class).setText(value)
        }
}

private fun Finder.dataGripTextArea(): DataGripJTextAreaUi =
    x("//div[@class='JBTextArea']", DataGripJTextAreaUi::class.java)

/**
 * Separate DataGrip host evidence.
 *
 * IDEA Ultimate evidence is intentionally not reused as proof here. This class launches the exact
 * packaged plugin in DataGrip 2026.3 through Starter-managed licensing/bootstrap, explicitly avoids
 * the IDEA-specific StartTrial assumption, and exercises only the XML/Database Tools action boundary
 * that DataGrip can credibly own. Java-source parity remains
 * outside this host proof.
 */
class ZMyBatisDataGripStarterDriverE2ETest {

    companion object {
        private const val DATAGRIP_RELEASE = "2026.3"
        private const val POSTGRES_E2E_DRIVER_NAME = "zMyBatis DataGrip PostgreSQL 42.7.14"
        private const val POSTGRES_E2E_DATA_SOURCE_NAME = POSTGRES_E2E_DRIVER_NAME
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
                            additionalMetadata: List<TestMetadata>,
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

    @Test
    fun registeredXmlBoundExecutionUsesNativeDatabaseToolsResultOnDataGrip(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("datagrip-prepared-postgresql-project"))
        Files.writeString(
            projectDir.resolve("DataGripPreparedQuery.xml"),
            """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="fixture.DataGripPreparedMapper">
              <select id="ping">
                <bind name="value" value="41L"/>
                SELECT #{value,jdbcType=BIGINT}::BIGINT AS RESULT_VALUE
              </select>
            </mapper>
            """.trimIndent(),
        )

        val postgresJar =
            Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val startedAt = System.nanoTime()
        val context = dataGripStarterContext(
            "datagrip-xml-prepared-postgresql",
            projectDir,
        )
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                assertDataGripRuntimeReady()
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(true)
                }

                createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                openFile("DataGripPreparedQuery.xml")
                ui.codeEditorForFile("DataGripPreparedQuery.xml").clickOnPosition(3, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis PostgreSQL datasource chooser on DataGrip", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem(POSTGRES_E2E_DATA_SOURCE_NAME, fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard { right() }
                waitFor("DataGrip PostgreSQL explicit public schema submenu", timeout = 3.minutes) {
                    ui.popups().list().last().hasText("public")
                }
                ui.popups().list().last().waitOneText("public").click()

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    val preview = dataGripTextArea().text.trim()
                    val executableSql = preview.substringBefore(
                        "\n\nBinding metadata (values materialized in SQL above):",
                    )
                    assertEquals(
                        "SELECT 41::BIGINT AS RESULT_VALUE",
                        executableSql,
                        "DataGrip XML bound acceptance must approve the exact native SQL artifact",
                    )
                    assertTrue(preview.contains("#1 property=value"))
                    assertTrue(preview.contains("jdbcType=BIGINT"))
                    button("Execute").click()
                }

                waitFor(
                    message = "DataGrip native Database Tools result grid contains bound value",
                    timeout = 2.minutes,
                ) {
                    try {
                        ui.accessibleTable {
                            byType("com.intellij.database.run.ui.table.TableResultView")
                        }.content().values.any { row ->
                            row.values.any { cell -> cell.trim() == "41" }
                        }
                    } catch (_: com.intellij.driver.sdk.WaitForException) {
                        false
                    }
                }
                assertFalse(
                    ui.isDialogOpened("//div[@title='zMyBatis: Query Result']"),
                    "DataGrip bound XML execution must not open the retired zMyBatis result modal",
                )
                ui.waitForNoOpenedDialogs()
            }

        println(
            "ZMYBATIS_HOST_EVIDENCE host=DATAGRIP release=$DATAGRIP_RELEASE " +
                "scenario=xml-bound-native-postgresql result=PASS action_registered=true " +
                "native_result=true clean_shutdown=true duration_ms=${elapsedMs(startedAt)}",
        )
    }

    private fun Driver.createPostgreSqlDataSourceThroughDatabaseTools(projectDir: Path) {
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)

            // DataGrip 2026.3 exposes the activated host surface as "Database Explorer".
            // The shared IU Driver helper searches for accessible name "Database Tool Window",
            // which is not present on this host. Use the visible built-in Create Data Source card
            // instead of widening production code or depending on the IU-only locator.
            x(
                "//div[@visible_text='PostgreSQL']",
                UiComponent::class.java,
            ).click()

            dataSourcesAndDriversDialog {
                button {
                    and(
                        byClass("ComboBoxButton"),
                        contains(byAccessibleName("PostgreSQL")),
                    )
                }.click()
                waitFor("custom pinned DataGrip PostgreSQL driver popup", timeout = 30.seconds) {
                    ui.popups().list().lastOrNull()?.hasText(POSTGRES_E2E_DRIVER_NAME) == true
                }
                ui.popups().list().last().waitOneText(
                    POSTGRES_E2E_DRIVER_NAME,
                    message = "custom pinned DataGrip PostgreSQL driver must be selectable",
                    timeout = 30.seconds,
                ).click()
                textField(
                    "//div[contains(@visible_text, 'jdbc:postgresql:')]",
                ).text = "jdbc:postgresql://127.0.0.1:5432/zmybatis?user=zmybatis"
                textField(
                    "//div[@accessiblename='User:' and @class='JBTextField']",
                ).text = "zmybatis"
                textField(
                    "//div[@accessiblename='Password:' and @class='JBPasswordField']",
                ).text = "zmybatis"
                okButton.click()
            }
        }

        waitFor("DataGrip Data Sources and Drivers dialog is closed", timeout = 30.seconds) {
            !ui.isDialogOpened("//div[@title='Data Sources and Drivers']")
        }
        waitFor("DataGrip PostgreSQL datasource persisted", timeout = 30.seconds) {
            runCatching { readPostgreSqlDataSourceId(projectDir) }.isSuccess
        }

        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            waitFor("DataGrip DatabaseView.Refresh is accepted", timeout = 30.seconds) {
                runCatching {
                    invokeAction("DatabaseView.Refresh", now = false)
                    true
                }.getOrDefault(false)
            }
        }
    }

    private fun readPostgreSqlDataSourceId(projectDir: Path): String {
        val dataSourcesFile = projectDir.resolve(".idea/dataSources.xml")
        check(Files.isRegularFile(dataSourcesFile)) {
            "DataGrip Database Tools must persist .idea/dataSources.xml before target binding"
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = Files.newInputStream(dataSourcesFile).use { input ->
            factory.newDocumentBuilder().parse(input)
        }
        val dataSources = document.getElementsByTagName("data-source")
        for (index in 0 until dataSources.length) {
            val attributes = dataSources.item(index).attributes
            if (attributes.getNamedItem("name")?.nodeValue == POSTGRES_E2E_DATA_SOURCE_NAME) {
                val uuid = attributes.getNamedItem("uuid")?.nodeValue
                check(!uuid.isNullOrBlank()) {
                    "DataGrip PostgreSQL datasource must have a stable UUID"
                }
                return uuid
            }
        }
        error("DataGrip PostgreSQL datasource '$POSTGRES_E2E_DATA_SOURCE_NAME' was not persisted")
    }

    private fun writePostgreSqlDatabaseToolsDriverFixture(configDir: Path, postgresJar: Path) {
        val driverDir = Files.createDirectories(configDir.resolve("jdbc-drivers"))
        val driverJar = driverDir.resolve(postgresJar.fileName)
        Files.copy(postgresJar, driverJar, StandardCopyOption.REPLACE_EXISTING)

        val optionsDir = Files.createDirectories(configDir.resolve("options"))
        Files.writeString(
            optionsDir.resolve("databaseDrivers.xml"),
            """
            <application>
              <component name="LocalDatabaseDriverManager" version="201">
                <driver
                    id="zmybatis-datagrip-postgresql-42.7.14"
                    name="$POSTGRES_E2E_DRIVER_NAME"
                    dialect="PostgreSQL"
                    driver-class="org.postgresql.Driver"
                    icon="AllIcons.Providers.Postgresql">
                  <artifact use="false" />
                  <option name="auto-sync" value="true" />
                  <option name="schema-control" value="AUTOMATIC" />
                  <library>
                    <url>${driverJar.toUri().toASCIIString()}</url>
                  </library>
                </driver>
              </component>
            </application>
            """.trimIndent(),
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
