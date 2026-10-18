package com.algorist.zMyBatis.e2e

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.materialization.PostgreSqlBigintExecutionMaterializer
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.ConventionalMyBatisDatabaseIds
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
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
import org.h2.Driver as H2Driver
import org.postgresql.Driver as PostgreSqlDriver
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
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
        private const val IDE_RELEASE = "2026.2.3"
        private const val E2E_DATA_SOURCE_UUID = "4c6e150e-3d84-4a71-9d85-0a6d05d42e01"
        private const val POSTGRES_E2E_DRIVER_NAME = "zMyBatis PostgreSQL 42.7.14"
        private const val POSTGRES_E2E_DATA_SOURCE_NAME = POSTGRES_E2E_DRIVER_NAME
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
    fun registeredActionExecutesExactStockMaterializedPostgreSqlArtifactWithNativeHistory(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-postgresql-project"))
        val dynamicMapper = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="fixture.QueryMapper">
            <select id="ping">SELECT #{value,jdbcType=BIGINT}::BIGINT AS RESULT_VALUE<if test="value > 0">, 1::BIGINT AS POSITIVE_MARKER</if></select>
            </mapper>
        """.trimIndent()
        Files.writeString(projectDir.resolve("PostgreSqlQuery.xml"), dynamicMapper)

        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-postgresql-bigint", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        var positiveHistorySql: String? = null
        var negativeHistorySql: String? = null

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(true)
                }

                val dataSourceId = createPostgreSqlDataSourceThroughDatabaseTools(projectDir)

                // Build both proof artifacts through stock MyBatis after Database Tools has
                // persisted the real datasource identity. This binds the independently
                // materialized artifact to the exact target selected by the shipping chooser.
                val positiveArtifact = stockPostgreSqlBigintArtifact(dynamicMapper, 7, dataSourceId)
                val negativeArtifact = stockPostgreSqlBigintArtifact(dynamicMapper, -7, dataSourceId)
                assertEquals(
                    "SELECT 7::BIGINT AS RESULT_VALUE , 1::BIGINT AS POSITIVE_MARKER",
                    positiveArtifact.executionSql,
                )
                assertEquals("SELECT -7::BIGINT AS RESULT_VALUE", negativeArtifact.executionSql)
                assertEquals(positiveArtifact.executionTargetId, negativeArtifact.executionTargetId)
                assertEquals(positiveArtifact.executionDialectId, negativeArtifact.executionDialectId)
                positiveHistorySql = positiveArtifact.executionSql
                negativeHistorySql = negativeArtifact.executionSql

                executePostgreSqlMapperCase(
                    fileName = "PostgreSqlQuery.xml",
                    input = "7",
                    expectedPreview = positiveArtifact.executionSql,
                    expectedCells = setOf("7", "1"),
                    chooseTarget = true,
                )
                executePostgreSqlMapperCase(
                    fileName = "PostgreSqlQuery.xml",
                    input = "-7",
                    expectedPreview = negativeArtifact.executionSql,
                    expectedCells = setOf("-7"),
                    chooseTarget = false,
                )

            }

        assertNativePostgreSqlHistoryPersisted(
            configDir = context.paths.configDir,
            positiveSql = checkNotNull(positiveHistorySql),
            negativeSql = checkNotNull(negativeHistorySql),
        )
    }

    private fun assertNativePostgreSqlHistoryPersisted(
        configDir: Path,
        positiveSql: String,
        negativeSql: String,
    ) {
        // ConsoleHistoryController persists native console history under the hidden
        // HistoryRootType rooted at <config>/consoles/.history when the console/IDE is disposed.
        val historyRoot = configDir.resolve("consoles/.history")
        assertTrue(
            Files.isDirectory(historyRoot),
            "Database Tools native history root must exist after IDE close: $historyRoot",
        )
        val historyFiles = Files.walk(historyRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList()
        }
        val matchingFile = historyFiles.firstOrNull { file ->
            runCatching { Files.readString(file) }
                .getOrNull()
                ?.let { content -> positiveSql in content && negativeSql in content }
                ?: false
        }
        assertTrue(
            matchingFile != null,
            "Database Tools native query history must persist both exact PostgreSQL executions; " +
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

                testConnection()
                waitFor(
                    "pinned local PostgreSQL driver result",
                    timeout = 3.minutes,
                ) {
                    isConnectionSuccessful(driverNameContains = "PostgreSQL") || downloadDriverFilesButton.present()
                }
                assertFalse(
                    downloadDriverFilesButton.present(),
                    "pinned pgJDBC 42.7.14 must be recognized without downloading another driver",
                )
                assertTrue(
                    isConnectionSuccessful(driverNameContains = "PostgreSQL"),
                    "PostgreSQL Test Connection must succeed with pinned pgJDBC 42.7.14",
                )
                waitFor(
                    "PostgreSQL Test Connection lifecycle completed",
                    timeout = 30.seconds,
                ) {
                    testConnectionButton.isEnabled()
                }
                driver.waitForIndicators(30.seconds, waitSmartLongEnough = true)
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

    private fun Driver.executePostgreSqlMapperCase(
        fileName: String,
        input: String,
        expectedPreview: String,
        expectedCells: Set<String>,
        chooseTarget: Boolean,
    ) {
        openFile(fileName)
        // Database Explorer refresh can retain focus after openFile selects the editor tab.
        // Click the statement itself so the action captures this editor and its caret.
        ui.codeEditorForFile(fileName).clickOnPosition(3, 20)
        ideFrame {
            invokeAction("zMyBatis.Execute", now = false)
        }

        if (chooseTarget) {
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
        }

        ui.dialog(title = "Enter MyBatis Parameters") {
            textField().text = input
            button("OK").click()
        }

        ui.dialog(title = "zMyBatis — SQL Preview") {
            assertEquals(
                expectedPreview,
                textArea().text.trim(),
                "preview must equal the exact SQL text submitted to Database Tools",
            )
            button("Execute").click()
        }

        waitFor(
            message = "PostgreSQL Database Tools result grid contains the expected value",
            timeout = 2.minutes,
        ) {
            try {
                val values = ui.accessibleTable {
                    byType("com.intellij.database.run.ui.table.TableResultView")
                }.content().values.flatMap { row -> row.values }.map { it.trim() }.toSet()
                expectedCells.all { it in values }
            } catch (_: com.intellij.driver.sdk.WaitForException) {
                false
            }
        }
        ui.waitForNoOpenedDialogs()
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

    private fun stockPostgreSqlBigintArtifact(
        mapperSource: String,
        input: Long,
        dataSourceId: String,
    ): MaterializedExecution {
        val configuration = Configuration().apply {
            databaseId = ConventionalMyBatisDatabaseIds.POSTGRESQL
        }
        val resource = "zmybatis-e2e-postgresql.xml"
        ByteArrayInputStream(mapperSource.toByteArray(StandardCharsets.UTF_8)).use { stream ->
            XMLMapperBuilder(stream, configuration, resource, configuration.sqlFragments).parse()
        }

        val statement = configuration.getMappedStatement("fixture.QueryMapper.ping")
        val boundSql = statement.getBoundSql(mapOf("value" to input))
        val mapping = boundSql.parameterMappings.single()
        assertEquals("value", mapping.property)
        assertEquals("BIGINT", mapping.jdbcType?.name)
        assertEquals("java.lang.Object", mapping.javaType.name)
        assertEquals("org.apache.ibatis.type.UnknownTypeHandler", mapping.typeHandler.javaClass.name)

        val fileId = SourceFileId("vfs:/PostgreSqlQuery.xml")
        val revision = SourceRevision(
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(mapperSource.toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        val token = "#{value,jdbcType=BIGINT}"
        val tokenStart = mapperSource.indexOf(token)
        check(tokenStart >= 0)
        val provenance = InputProvenance(
            listOf(
                InputEvidence.Placeholder(
                    kind = InputKind.BOUND,
                    expression = "value",
                    source = SourceEvidence(
                        fileId,
                        revision,
                        SourceRange(tokenStart, tokenStart + token.length),
                    ),
                ),
            ),
        )
        val prepared = PreparedExecution(
            statementId = XmlStatementId(fileId, "fixture.QueryMapper", "ping"),
            statementKind = StatementKind.SELECT,
            sourceRevisions = mapOf(fileId to revision),
            sqlWithPlaceholders = boundSql.sql,
            orderedBindings = listOf(
                PreparedBinding(
                    index = 0,
                    property = mapping.property,
                    value = InputValue.IntegerValue(BigInteger.valueOf(input)),
                    origin = PreparedBindingOrigin.CallerInput(
                        InputRequirementId("e2e-postgresql:value"),
                        provenance,
                    ),
                    metadata = PreparedBindingMetadata(
                        declaredJavaTypeIdentity = JavaTypeIdentity("long"),
                        mappingJavaTypeIdentity = mapping.javaType.name,
                        jdbcTypeIdentity = mapping.jdbcType?.name,
                        typeHandlerIdentity = mapping.typeHandler.javaClass.name,
                        parameterMode = mapping.mode.name,
                        numericScale = mapping.numericScale,
                    ),
                ),
            ),
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                engineIdentity = "org.mybatis:mybatis",
                engineVersion = "3.5.19",
                languageDriverIdentity = statement.lang.javaClass.name,
                databaseId = configuration.databaseId,
            ),
        )
        val targetId = ExecutionTargetId(
            StableDataSourceId(dataSourceId),
            ExplicitSchemaIdentity("public"),
        )
        val target = ResolvedExecutionTarget(
            ExecutionTargetDescriptor(targetId, POSTGRES_E2E_DATA_SOURCE_NAME),
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )

        return when (val result = PostgreSqlBigintExecutionMaterializer(target).materialize(prepared)) {
            is MaterializationResult.Success -> result.execution
            is MaterializationResult.Failed -> fail {
                "stock PostgreSQL BIGINT proof must materialize, got " +
                    result.failure.kind + " / " + result.failure.code
            }
        }
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
