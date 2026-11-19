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
import com.intellij.driver.sdk.ui.components.common.databaseToolWindow
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.ultimate.dataSourcesAndDriversDialog
import com.intellij.driver.sdk.ui.components.elements.accessibleTable
import com.intellij.driver.sdk.ui.components.elements.actionButton
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
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.ide.starter.runner.startIdeWithoutProject
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.TestMetadata
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import org.postgresql.Driver as PostgreSqlDriver
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.xml.parsers.DocumentBuilderFactory
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
    fun setText(value: String)
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
    var text: String
        get() = driver.cast(component, JTextComponentRemote::class).getText()
        set(value) {
            driver.cast(component, JTextComponentRemote::class).setText(value)
        }
}

private fun Finder.textArea(): JTextAreaUi =
    x("//div[@class='JBTextArea']", JTextAreaUi::class.java)

private fun Finder.rawConfirmation(): UiComponent =
    x(
        "//div[@visible_text='I understand this value is inserted as raw SQL text']",
        UiComponent::class.java,
    )

class ZMyBatisStarterDriverE2ETest {

    private var testStartedAtNanos: Long = 0

    @BeforeEach
    fun recordScenarioStart(testInfo: TestInfo) {
        testStartedAtNanos = System.nanoTime()
        println("ZMYBATIS_E2E_TEST_START name=${testInfo.displayName}")
    }

    @AfterEach
    fun recordScenarioEnd(testInfo: TestInfo) {
        val durationMs = (System.nanoTime() - testStartedAtNanos) / 1_000_000
        println("ZMYBATIS_E2E_TEST_END name=${testInfo.displayName} duration_ms=$durationMs")
    }

    companion object {
        private const val IDE_RELEASE = "2026.3"
        private const val POSTGRES_E2E_DRIVER_NAME = "zMyBatis PostgreSQL 42.7.14"
        private const val POSTGRES_E2E_DATA_SOURCE_NAME = POSTGRES_E2E_DRIVER_NAME
        private val pluginArchive: Path = pathOf(System.getProperty("path.to.build.plugin")).toAbsolutePath()
        private val sampleProject: Path = pathOf("src/integrationTest/testProject").toAbsolutePath()
        private val activationSeedLock = Any()

        @Volatile
        private var sharedActivationSeedConfigDir: Path? = null

        init {
            // Starter and the IDE run in separate processes. Make IDE-side exceptions/freezes fail
            // the JUnit process instead of allowing a false green.
            di = DI {
                extend(di)
                bindSingleton<CIServer>(overrides = true) { strictCIServer() }
            }
        }

        // Starter 263 adds TeamCity TestMetadata to the mandatory CIServer failure callback
        // alongside SyntheticTestKind. Keep this unstable API scoped to the test adapter;
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
                    additionalMetadata: List<TestMetadata>,
                ) {
                    fail { "$testName fails: $message\n$details" }
                }
            }

    }

    @Test
    fun known262ThemeFailureIsStrictlyReportedOn263() {
        // The 262 platform-specific Islands Dark exemption is deliberately
        // removed with the 263 minimum. No IDE Logger.error may be ignored.
        val message = "Theme Islands Dark refers to unknown color scheme Islands Dark"
        val details = "java.lang.Throwable: platform theme initialization"
        val failure = org.junit.jupiter.api.Assertions.assertThrows(
            org.opentest4j.AssertionFailedError::class.java,
        ) {
            strictCIServer().reportTestFailure(
                testName = "legacy-platform-theme",
                message = message,
                details = details,
                linkToLogs = null,
                kind = SyntheticTestKind.IDE_EXCEPTION,
                generifyTestName = true,
                additionalMetadata = emptyList(),
            )
        }
        assertTrue(failure.message?.contains("legacy-platform-theme fails: $message") == true)
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
    fun registeredActionExecutesZeroBindingQueryThroughRealDatabaseTools(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-zero-binding-postgresql-project"))
        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-zero-binding-postgresql", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(true)
                }

                createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                openFile("Query.xml")
                ui.codeEditorForFile("Query.xml").clickOnPosition(2, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis PostgreSQL datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem(POSTGRES_E2E_DATA_SOURCE_NAME, fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard { right() }
                waitFor("PostgreSQL explicit public schema submenu", timeout = 3.minutes) {
                    ui.popups().list().last().hasText("public")
                }
                ui.popups().list().last().waitOneText("public").click()

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    assertEquals(
                        "SELECT 7 AS RESULT_VALUE",
                        textArea().text.trim(),
                        "zero-binding preview must equal the exact SQL submitted to Database Tools",
                    )
                    button("Execute").click()
                }

                waitFor(
                    message = "PostgreSQL Database Tools result grid contains the zero-binding value",
                    timeout = 2.minutes,
                ) {
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
    fun registeredActionExecutesMultiBindingPlanThroughPreparedResultPresentation(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-postgresql-prepared-project"))
        val mapper = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="fixture.QueryMapper">
            <select id="ping">SELECT #{value,jdbcType=BIGINT}::BIGINT AS RESULT_VALUE, #{value,jdbcType=BIGINT}::BIGINT + 1 AS SECOND_VALUE</select>
            </mapper>
        """.trimIndent()
        Files.writeString(projectDir.resolve("PostgreSqlQuery.xml"), mapper)
        writeMapperInterface(projectDir, "long")

        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-postgresql-prepared-multi-binding", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(true)
                }

                createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                openFile("PostgreSqlQuery.xml")
                ui.codeEditorForFile("PostgreSqlQuery.xml").clickOnPosition(3, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis PostgreSQL datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem(POSTGRES_E2E_DATA_SOURCE_NAME, fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard { right() }
                waitFor("PostgreSQL explicit public schema submenu", timeout = 3.minutes) {
                    ui.popups().list().last().hasText("public")
                }
                ui.popups().list().last().waitOneText("public").click()

                ui.dialog(title = "Enter MyBatis Parameters") {
                    textField().text = "7"
                    button("OK").click()
                }

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    val preview = textArea().text.trim()
                    val preparedSql = preview.substringBefore("\n\nBindings (values hidden):")
                    assertEquals(
                        2,
                        preparedSql.count { it == '?' },
                        "registered multi-binding execution must preview two JDBC placeholders",
                    )
                    assertTrue(preparedSql.contains("RESULT_VALUE"))
                    assertTrue(preparedSql.contains("SECOND_VALUE"))
                    assertEquals(
                        2,
                        Regex("""#\d+ property=value""").findAll(preview).count(),
                        "prepared preview must expose both ordered bindings without their values",
                    )
                    assertTrue(preview.contains("jdbcType=BIGINT"))
                    assertTrue(preview.contains("handler=org.apache.ibatis.type.LongTypeHandler"))
                    assertFalse(
                        preview.contains("SELECT 7::BIGINT"),
                        "prepared approval must not literalize the bound value",
                    )
                    button("Execute").click()
                }

                waitFor(
                    message = "zMyBatis prepared-result table contains both multi-binding values",
                    timeout = 2.minutes,
                ) {
                    try {
                        if (!ui.isDialogOpened("//div[@title='zMyBatis: Query Result']")) {
                            false
                        } else {
                            val values = ui.accessibleTable {
                                byType("com.intellij.ui.table.JBTable")
                            }.content().values.flatMap { row -> row.values }.map { it.trim() }.toSet()
                            setOf("7", "8").all { it in values }
                        }
                    } catch (_: com.intellij.driver.sdk.WaitForException) {
                        false
                    }
                }

                ui.dialog(title = "zMyBatis: Query Result") {
                    button("Close").click()
                }
                ui.waitForNoOpenedDialogs()

                val nativeGridOpened = try {
                    ui.accessibleTable {
                        byType("com.intellij.database.run.ui.table.TableResultView")
                    }.content()
                    true
                } catch (_: com.intellij.driver.sdk.WaitForException) {
                    false
                }
                assertFalse(
                    nativeGridOpened,
                    "bound registered execution must use zMyBatis prepared-result presentation, not native SQL-text result delivery",
                )
            }
    }

    @Test
    fun registeredActionExecutesBuiltInParameterTypeThroughPreparedRoute(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-postgresql-parameter-type-project"))
        val mapper = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="fixture.QueryMapper">
            <select id="ping" parameterType="long">SELECT #{value,jdbcType=BIGINT}::BIGINT AS RESULT_VALUE</select>
            </mapper>
        """.trimIndent()
        Files.writeString(projectDir.resolve("PostgreSqlParameterTypeQuery.xml"), mapper)
        writeMapperInterface(projectDir, "long")

        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-postgresql-parameter-type", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(true)
                }

                createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                openFile("PostgreSqlParameterTypeQuery.xml")
                ui.codeEditorForFile("PostgreSqlParameterTypeQuery.xml").clickOnPosition(3, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis PostgreSQL datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem(POSTGRES_E2E_DATA_SOURCE_NAME, fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard { right() }
                waitFor("PostgreSQL explicit public schema submenu", timeout = 3.minutes) {
                    ui.popups().list().last().hasText("public")
                }
                ui.popups().list().last().waitOneText("public").click()

                ui.dialog(title = "Enter MyBatis Parameters") {
                    textField().text = "41"
                    button("OK").click()
                }

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    val preview = textArea().text.trim()
                    val preparedSql = preview.substringBefore("\n\nBindings (values hidden):")
                    assertEquals(
                        "SELECT ?::BIGINT AS RESULT_VALUE",
                        preparedSql,
                        "built-in parameterType metadata must not change the prepared SQL identity",
                    )
                    assertTrue(preview.contains("#1 property=value"))
                    assertTrue(preview.contains("javaType=java.lang.Long"))
                    assertTrue(preview.contains("jdbcType=BIGINT"))
                    assertTrue(preview.contains("handler=org.apache.ibatis.type.LongTypeHandler"))
                    assertFalse(
                        preview.contains("SELECT 41::BIGINT"),
                        "parameterType parity must not reintroduce literalized bound SQL",
                    )
                    button("Execute").click()
                }

                waitFor(
                    message = "zMyBatis prepared-result table contains parameterType-backed value",
                    timeout = 2.minutes,
                ) {
                    try {
                        if (!ui.isDialogOpened("//div[@title='zMyBatis: Query Result']")) {
                            false
                        } else {
                            ui.accessibleTable {
                                byType("com.intellij.ui.table.JBTable")
                            }.content().values.any { row ->
                                row.values.any { cell -> cell.trim() == "41" }
                            }
                        }
                    } catch (_: com.intellij.driver.sdk.WaitForException) {
                        false
                    }
                }

                ui.dialog(title = "zMyBatis: Query Result") {
                    button("Close").click()
                }
                ui.waitForNoOpenedDialogs()

                val nativeGridOpened = try {
                    ui.accessibleTable {
                        byType("com.intellij.database.run.ui.table.TableResultView")
                    }.content()
                    true
                } catch (_: com.intellij.driver.sdk.WaitForException) {
                    false
                }
                assertFalse(
                    nativeGridOpened,
                    "parameterType-backed bound execution must remain on the prepared-result route",
                )
            }
    }

    @Test
    fun registeredActionSelectsTargetDatabaseIdVariant(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-postgresql-database-id-project"))
        val mapper = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="fixture.QueryMapper">
            <select id="ping" databaseId="PostgreSQL">SELECT 2620 AS RESULT_VALUE</select>
            <select id="ping">SELECT 2600 AS RESULT_VALUE</select>
            </mapper>
        """.trimIndent()
        Files.writeString(projectDir.resolve("PostgreSqlDatabaseIdQuery.xml"), mapper)

        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-postgresql-database-id", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(true)
                }

                createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                openFile("PostgreSqlDatabaseIdQuery.xml")
                ui.codeEditorForFile("PostgreSqlDatabaseIdQuery.xml").clickOnPosition(3, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis PostgreSQL datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem(POSTGRES_E2E_DATA_SOURCE_NAME, fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard { right() }
                waitFor("PostgreSQL explicit public schema submenu", timeout = 3.minutes) {
                    ui.popups().list().last().hasText("public")
                }
                ui.popups().list().last().waitOneText("public").click()

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    assertEquals(
                        "SELECT 2620 AS RESULT_VALUE",
                        textArea().text.trim(),
                        "target-derived PostgreSQL databaseId variant must win over the default variant",
                    )
                    button("Execute").click()
                }

                waitFor(
                    message = "PostgreSQL Database Tools result grid contains databaseId-selected value",
                    timeout = 2.minutes,
                ) {
                    try {
                        val values = ui.accessibleTable {
                            byType("com.intellij.database.run.ui.table.TableResultView")
                        }.content().values.flatMap { row -> row.values }.map { it.trim() }.toSet()
                        "2620" in values && "2600" !in values
                    } catch (_: com.intellij.driver.sdk.WaitForException) {
                        false
                    }
                }
                ui.waitForNoOpenedDialogs()
            }
    }

    @Test
    fun registeredMutationDeclarationForcesPreviewWhenSettingDisabled(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-postgresql-mutation-approval-project"))
        val mapper = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="fixture.QueryMapper">
            <update id="ping">SELECT 0 AS RESULT_VALUE</update>
            </mapper>
        """.trimIndent()
        Files.writeString(projectDir.resolve("PostgreSqlMutationQuery.xml"), mapper)

        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-postgresql-mutation-approval", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(false)
                }

                createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                openFile("PostgreSqlMutationQuery.xml")
                ui.codeEditorForFile("PostgreSqlMutationQuery.xml").clickOnPosition(3, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis PostgreSQL datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem(POSTGRES_E2E_DATA_SOURCE_NAME, fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard { right() }
                waitFor("PostgreSQL explicit public schema submenu", timeout = 3.minutes) {
                    ui.popups().list().last().hasText("public")
                }
                ui.popups().list().last().waitOneText("public").click()

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    assertEquals(
                        "SELECT 0 AS RESULT_VALUE",
                        textArea().text.trim(),
                        "mutation declaration must force final-plan approval when sqlPreview=false",
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
                    "cancelling mandatory mutation approval must not execute the declared mutation",
                )
            }
    }

    @Test
    fun registeredProviderAnnotationFailsClosedWithExplicitNotice(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-provider-unsupported-project"))
        writeDirectJavaProviderMapper(projectDir)

        starterContext("action-provider-unsupported", projectDir)
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                openFile("JavaProviderMapper.java")
                ui.codeEditorForFile("JavaProviderMapper.java").clickOnPosition(7, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                ui.dialog(title = "zMyBatis: Provider Not Supported") {
                    button("OK").click()
                }
                ui.waitForNoOpenedDialogs()
            }
    }

    private fun assertNativePostgreSqlHistoryContainsAndExcludes(
        configDir: Path,
        requiredSql: String,
        forbiddenSql: String,
    ) {
        val historyRoot = configDir.resolve("consoles/.history")
        assertTrue(
            Files.isDirectory(historyRoot),
            "Database Tools native history root must exist after IDE close: $historyRoot",
        )
        val historyFiles = Files.walk(historyRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList()
        }
        val historyContents = historyFiles.mapNotNull { file ->
            runCatching { Files.readString(file) }.getOrNull()
        }
        assertTrue(
            historyContents.any { requiredSql in it },
            "Database Tools native query history must persist the exact approved direct-Java raw SQL; " +
                "historyFiles=$historyFiles",
        )
        assertFalse(
            historyContents.any { forbiddenSql in it },
            "cancelled direct-Java raw SQL must not enter native Database Tools history; " +
                "historyFiles=$historyFiles",
        )
    }

    private fun Driver.createPostgreSqlDataSourceThroughDatabaseTools(projectDir: Path): String {
        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                actionButton {
                    and(byClass("ActionButton"), byAccessibleName("New"))
                }.performAction()
            }

            val addList = ui.popup().list()
            addList.waitOneText(
                "Data Source",
                message = "Database Tools New menu must expose Data Source",
                timeout = 30.seconds,
            ).click()
            waitFor("Database Tools datasource type submenu", timeout = 30.seconds) {
                ui.popups().list().size >= 2
            }
            val dataSourceTypeList = ui.popups().list().last().list()
            dataSourceTypeList.setFocus()
            dataSourceTypeList.keyboard {
                typeText("PostgreSQL")
                enter()
            }

            dataSourcesAndDriversDialog {
                button {
                    and(
                        byClass("ComboBoxButton"),
                        contains(byAccessibleName("PostgreSQL")),
                    )
                }.click()
                waitFor("custom pinned PostgreSQL driver popup", timeout = 30.seconds) {
                    ui.popups().list().lastOrNull()?.hasText(POSTGRES_E2E_DRIVER_NAME) == true
                }
                ui.popups().list().last().waitOneText(
                    POSTGRES_E2E_DRIVER_NAME,
                    message = "custom pinned PostgreSQL driver must be selectable",
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

                // Persist the configured datasource directly. Connectivity is proven by the
                // downstream packaged zMyBatis execution against native Database Tools results
                // and history, which is stronger product evidence than Database Tools' separate
                // asynchronous Test Connection UI lifecycle.
                okButton.click()
            }
        }
        waitFor("Data Sources and Drivers dialog is closed", timeout = 30.seconds) {
            !ui.isDialogOpened("//div[@title='Data Sources and Drivers']")
        }

        waitFor("Database Tools-created PostgreSQL datasource persisted", timeout = 30.seconds) {
            runCatching { readPostgreSqlDataSourceId(projectDir) }.isSuccess
        }
        val dataSourceId = readPostgreSqlDataSourceId(projectDir)

        ideFrame {
            invokeAction("ActivateDatabaseToolWindow", now = false)
            databaseToolWindow {
                waitFor("DatabaseView.Refresh is accepted", timeout = 30.seconds) {
                    runCatching {
                        driver.invokeAction(
                            "DatabaseView.Refresh",
                            now = false,
                            component = component,
                        )
                        true
                    }.getOrDefault(false)
                }
            }
        }
        return dataSourceId
    }

    private fun readPostgreSqlDataSourceId(projectDir: Path): String {
        val dataSourcesFile = projectDir.resolve(".idea/dataSources.xml")
        check(Files.isRegularFile(dataSourcesFile)) {
            "Database Tools must persist .idea/dataSources.xml before target binding"
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
                    "Database Tools-created PostgreSQL datasource must have a stable UUID"
                }
                return uuid
            }
        }
        error("Database Tools-created PostgreSQL datasource '$POSTGRES_E2E_DATA_SOURCE_NAME' was not persisted")
    }

    @Test
    fun registeredRawInterpolationForcesPreviewWhenSettingDisabled(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-raw-confirmation-postgresql-project"))
        val rawToken = buildString {
            append(36.toChar())
            append("{value}")
        }
        Files.writeString(
            projectDir.resolve("Query.xml"),
            """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd"><mapper namespace="fixture.QueryMapper">
            <select id="ping">SELECT $rawToken AS RESULT_VALUE</select>
            </mapper>
            """.trimIndent(),
        )
        writeMapperInterface(projectDir, "java.lang.String")

        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-raw-confirmation-postgresql", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(false)
                }

                createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                openFile("Query.xml")
                ui.codeEditorForFile("Query.xml").clickOnPosition(2, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis PostgreSQL datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem(POSTGRES_E2E_DATA_SOURCE_NAME, fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard { right() }
                waitFor("PostgreSQL explicit public schema submenu", timeout = 3.minutes) {
                    ui.popups().list().last().hasText("public")
                }
                ui.popups().list().last().waitOneText("public").click()

                ui.dialog(title = "Enter MyBatis Parameters") {
                    textField().text = "7"
                    rawConfirmation().click()
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
    fun registeredJavaAnnotationRawInterpolationExecutesExactApprovedSql(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-java-raw-postgresql-project"))
        writeDirectJavaRawMapper(projectDir)

        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-java-raw-postgresql", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        val cancelledSql = "SELECT 45001 AS RESULT_VALUE"
        val executedSql = "SELECT 45002 AS RESULT_VALUE"

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(false)
                }

                createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                openFile("JavaRawQueryMapper.java")
                ui.codeEditorForFile("JavaRawQueryMapper.java").clickOnPosition(8, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                waitFor("zMyBatis PostgreSQL datasource chooser", timeout = 30.seconds) {
                    ui.popups().list().isNotEmpty()
                }
                val dataSourceList = ui.popup().list()
                dataSourceList.clickItem(POSTGRES_E2E_DATA_SOURCE_NAME, fullMatch = false)
                dataSourceList.setFocus()
                dataSourceList.keyboard { right() }
                waitFor("PostgreSQL explicit public schema submenu", timeout = 3.minutes) {
                    ui.popups().list().last().hasText("public")
                }
                ui.popups().list().last().waitOneText("public").click()

                ui.dialog(title = "Enter MyBatis Parameters") {
                    assertTrue(textField().text.isBlank(), "raw Java input must start without retained history")
                    textField().text = "45001"
                    rawConfirmation().click()
                    button("OK").click()
                }

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    assertEquals(
                        cancelledSql,
                        textArea().text.trim(),
                        "direct Java raw interpolation must force the exact final SQL preview when sqlPreview=false",
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
                    "cancelling direct Java raw approval must not execute the query",
                )

                openFile("JavaRawQueryMapper.java")
                ui.codeEditorForFile("JavaRawQueryMapper.java").clickOnPosition(8, 20)
                ideFrame {
                    invokeAction("zMyBatis.Execute", now = false)
                }

                ui.dialog(title = "Enter MyBatis Parameters") {
                    assertTrue(
                        textField().text.isBlank(),
                        "raw Java input must never be retained across registered-action invocations",
                    )
                    textField().text = "45002"
                    rawConfirmation().click()
                    button("OK").click()
                }

                ui.dialog(title = "zMyBatis — SQL Preview") {
                    assertEquals(
                        executedSql,
                        textArea().text.trim(),
                        "approved preview must equal the exact SQL submitted to Database Tools",
                    )
                    button("Execute").click()
                }

                waitFor(
                    message = "PostgreSQL Database Tools result grid contains the direct Java raw value",
                    timeout = 2.minutes,
                ) {
                    try {
                        ui.accessibleTable {
                            byType("com.intellij.database.run.ui.table.TableResultView")
                        }.content().values.any { row ->
                            row.values.any { cell -> cell.trim() == "45002" }
                        }
                    } catch (_: com.intellij.driver.sdk.WaitForException) {
                        false
                    }
                }
                ui.waitForNoOpenedDialogs()
            }

        assertNativePostgreSqlHistoryContainsAndExcludes(
            configDir = context.paths.configDir,
            requiredSql = executedSql,
            forbiddenSql = cancelledSql,
        )
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
                    dialog(title = "zMyBatis: Execution Target Unavailable") {
                        button("OK").click()
                    }
                    waitForNoOpenedDialogs()
                }
            }
    }

    private fun starterContext(
        testName: String,
        projectDir: Path,
    ): IDETestContext {
        val context =
            Starter.newContext(
                testName,
                TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectDir)).useRelease(IDE_RELEASE),
            ).apply {
                System.getenv("LICENSE_KEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setLicense(it) }
                PluginConfigurator(this).installPluginFromPath(pluginArchive)
            }

        val restoreStartedAtNanos = System.nanoTime()
        val seedKey = activationSeedConfigDir().resolve("idea.key")
        Files.createDirectories(context.paths.configDir)
        Files.copy(
            seedKey,
            context.paths.configDir.resolve("idea.key"),
            StandardCopyOption.REPLACE_EXISTING,
        )
        assertTrue(
            Files.isRegularFile(context.paths.configDir.resolve("idea.key")),
            "activation seed must materialize an independent licensed scenario config",
        )
        val restoreDurationMs = (System.nanoTime() - restoreStartedAtNanos) / 1_000_000
        println(
            "ZMYBATIS_E2E_ACTIVATION_RESTORE test=$testName duration_ms=$restoreDurationMs",
        )
        return context
    }

    private fun activationSeedConfigDir(): Path {
        sharedActivationSeedConfigDir?.let { return it }
        return synchronized(activationSeedLock) {
            sharedActivationSeedConfigDir
                ?: createActivationSeedConfig().also { sharedActivationSeedConfigDir = it }
        }
    }

    private fun createActivationSeedConfig(): Path {
        val seedContext =
            Starter.newContext(
                "activation-seed",
                TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(sampleProject)).useRelease(IDE_RELEASE),
            ).apply {
                System.getenv("LICENSE_KEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setLicense(it) }
                PluginConfigurator(this).installPluginFromPath(pluginArchive)
            }

        val seedStartedAtNanos = System.nanoTime()
        activateBeforeOpeningProject(seedContext)
        val regularFiles =
            Files.walk(seedContext.paths.configDir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.count()
            }
        val seedDurationMs = (System.nanoTime() - seedStartedAtNanos) / 1_000_000
        println(
            "ZMYBATIS_E2E_ACTIVATION_SEED_READY duration_ms=$seedDurationMs files=$regularFiles",
        )
        return seedContext.paths.configDir
    }

    private fun activateBeforeOpeningProject(context: IDETestContext) {
        val activationStartedAtNanos = System.nanoTime()
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
        val durationMs = (System.nanoTime() - activationStartedAtNanos) / 1_000_000
        println(
            "ZMYBATIS_E2E_ACTIVATION config=${context.paths.configDir.fileName} duration_ms=$durationMs",
        )
    }

    private fun writeDirectJavaProviderMapper(projectDir: Path) {
        val annotationDir = Files.createDirectories(
            projectDir.resolve("org/apache/ibatis/annotations"),
        )
        Files.writeString(
            annotationDir.resolve("SelectProvider.java"),
            """
            package org.apache.ibatis.annotations;

            public @interface SelectProvider {
                Class<?> type();
                String method();
            }
            """.trimIndent(),
        )

        Files.writeString(
            projectDir.resolve("SqlProvider.java"),
            """
            package fixture;

            public final class SqlProvider {
                public static String sql() {
                    return "SELECT 1";
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            projectDir.resolve("JavaProviderMapper.java"),
            """
            package fixture;

            import org.apache.ibatis.annotations.SelectProvider;

            public interface JavaProviderMapper {
                @SelectProvider(type = SqlProvider.class, method = "sql")
                Object ping();
            }
            """.trimIndent(),
        )
    }

    private fun writeDirectJavaRawMapper(projectDir: Path) {
        // The Starter sample is intentionally build-tool-free. Minimal source stubs give Java PSI
        // resolvable MyBatis annotation FQNs; real MyBatis 3.6.0 annotation resolution is already
        // covered by JavaAnnotationSourceCaptureAdapterProjectFixtureTest.
        val annotationDir = Files.createDirectories(
            projectDir.resolve("org/apache/ibatis/annotations"),
        )
        Files.writeString(
            annotationDir.resolve("Select.java"),
            """
            package org.apache.ibatis.annotations;

            public @interface Select {
                String[] value();
                String databaseId() default "";
                boolean affectData() default false;
            }
            """.trimIndent(),
        )
        Files.writeString(
            annotationDir.resolve("Param.java"),
            """
            package org.apache.ibatis.annotations;

            public @interface Param {
                String value();
            }
            """.trimIndent(),
        )

        val rawToken = buildString {
            append(36.toChar())
            append("{value}")
        }
        Files.writeString(
            projectDir.resolve("JavaRawQueryMapper.java"),
            """
            package fixture;

            import org.apache.ibatis.annotations.Param;
            import org.apache.ibatis.annotations.Select;

            public interface JavaRawQueryMapper {
                @Select(value = "SELECT $rawToken AS RESULT_VALUE")
                Object ping(@Param(value = "value") String value);
            }
            """.trimIndent(),
        )
    }

    private fun writeMapperInterface(
        projectDir: Path,
        parameterType: String,
    ) {
        val packageDir = Files.createDirectories(projectDir.resolve("fixture"))
        Files.writeString(
            packageDir.resolve("QueryMapper.java"),
            """
            package fixture;

            public interface QueryMapper {
                Object ping($parameterType value);
            }
            """.trimIndent(),
        )
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
                    id="zmybatis-postgresql-42.7.14"
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
