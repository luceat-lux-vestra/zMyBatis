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
        private const val POSTGRES_E2E_DRIVER_NAME = "zMyBatis PostgreSQL 42.7.14"
        private const val POSTGRES_E2E_DATA_SOURCE_NAME = POSTGRES_E2E_DRIVER_NAME
        private const val KNOWN_ISLANDS_ISSUE = "IJPL-222870"
        private const val ISLANDS_FAILURE_PREFIX = "Theme Islands Dark refers to unknown color scheme"
        private const val ISLANDS_FAILURE_STACK =
            "com.intellij.openapi.editor.colors.impl.EditorColorsManagerImpl.getSchemeForCurrentUITheme"
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
    fun registeredLeapActionExecutesZeroBigintRawAndMutationThroughPostgreSql(@TempDir tempDir: Path) {
        val projectDir = copySampleProject(tempDir.resolve("action-postgresql-project"))
        val rawToken = buildString {
            append(36.toChar())
            append("{value}")
        }
        val mapper = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="fixture.QueryMapper">
            <select id="ping">SELECT #{value,jdbcType=BIGINT}::BIGINT AS RESULT_VALUE</select>
            <select id="zero">SELECT 11::BIGINT AS ZERO_VALUE</select>
            <select id="raw">SELECT $rawToken::BIGINT AS RAW_VALUE</select>
            <update id="mutate">SELECT 9::BIGINT AS MUTATION_DECLARATION_PROBE</update>
            </mapper>
        """.trimIndent()
        Files.writeString(projectDir.resolve("PostgreSqlQuery.xml"), mapper)
        writeLeapMapperAuthority(projectDir)

        val postgresJar = Path.of(PostgreSqlDriver::class.java.protectionDomain.codeSource.location.toURI())
        val context = starterContext("action-postgresql-leap", projectDir)
        writePostgreSqlDatabaseToolsDriverFixture(context.paths.configDir, postgresJar)

        val expectedHistory = linkedSetOf<String>()
        val cancelledMutationSql = "SELECT 9::BIGINT AS MUTATION_DECLARATION_PROBE"

        context
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)

                service(ZMyBatisSettingsRemote::class).apply {
                    setAutoFormatSql(false)
                    setSqlPreview(true)
                }

                val dataSourceId = createPostgreSqlDataSourceThroughDatabaseTools(projectDir)
                val positiveArtifact = stockPostgreSqlBigintArtifact(mapper, 7, dataSourceId)
                val negativeArtifact = stockPostgreSqlBigintArtifact(mapper, -7, dataSourceId)
                assertEquals("SELECT 7::BIGINT AS RESULT_VALUE", positiveArtifact.executionSql)
                assertEquals("SELECT -7::BIGINT AS RESULT_VALUE", negativeArtifact.executionSql)
                assertEquals(positiveArtifact.executionTargetId, negativeArtifact.executionTargetId)
                assertEquals(positiveArtifact.executionDialectId, negativeArtifact.executionDialectId)

                executePostgreSqlMapperCase(
                    fileName = "PostgreSqlQuery.xml",
                    line = 4,
                    input = null,
                    expectedPreview = "SELECT 11::BIGINT AS ZERO_VALUE",
                    expectedCells = setOf("11"),
                    chooseTarget = true,
                )
                expectedHistory += "SELECT 11::BIGINT AS ZERO_VALUE"

                executePostgreSqlMapperCase(
                    fileName = "PostgreSqlQuery.xml",
                    line = 3,
                    input = "7",
                    expectedPreview = positiveArtifact.executionSql,
                    expectedCells = setOf("7"),
                    chooseTarget = false,
                )
                expectedHistory += positiveArtifact.executionSql

                executePostgreSqlMapperCase(
                    fileName = "PostgreSqlQuery.xml",
                    line = 3,
                    input = "-7",
                    expectedPreview = negativeArtifact.executionSql,
                    expectedCells = setOf("-7"),
                    chooseTarget = false,
                )
                expectedHistory += negativeArtifact.executionSql

                service(ZMyBatisSettingsRemote::class).setSqlPreview(false)
                executePostgreSqlMapperCase(
                    fileName = "PostgreSqlQuery.xml",
                    line = 5,
                    input = "7",
                    rawInput = true,
                    expectedPreview = "SELECT 7::BIGINT AS RAW_VALUE",
                    expectedCells = setOf("7"),
                    chooseTarget = false,
                )
                expectedHistory += "SELECT 7::BIGINT AS RAW_VALUE"

                cancelPostgreSqlMapperCase(
                    fileName = "PostgreSqlQuery.xml",
                    line = 6,
                    expectedPreview = cancelledMutationSql,
                )
            }

        assertNativePostgreSqlHistoryPersisted(
            configDir = context.paths.configDir,
            expectedSql = expectedHistory,
            forbiddenSql = cancelledMutationSql,
        )
    }
    private fun assertNativePostgreSqlHistoryPersisted(
        configDir: Path,
        expectedSql: Set<String>,
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
        val historyText = historyFiles.mapNotNull { file ->
            runCatching { Files.readString(file) }.getOrNull()
        }.joinToString("\n")
        expectedSql.forEach { sql ->
            assertTrue(
                sql in historyText,
                "Database Tools native query history must persist exact executed SQL <$sql>; historyFiles=$historyFiles",
            )
        }
        assertFalse(
            forbiddenSql in historyText,
            "cancelled mutation must not enter native query history: <$forbiddenSql>",
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
        line: Int,
        input: String?,
        expectedPreview: String,
        expectedCells: Set<String>,
        chooseTarget: Boolean,
        rawInput: Boolean = false,
    ) {
        openFile(fileName)
        ui.codeEditorForFile(fileName).clickOnPosition(line, 20)
        ideFrame { invokeAction("zMyBatis.Execute", now = false) }

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

        if (input != null) {
            ui.dialog(title = "Enter MyBatis Parameters") {
                if (rawInput) {
                    textArea().text = input
                } else {
                    textField().text = input
                }
                button("OK").click()
            }
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

    private fun Driver.cancelPostgreSqlMapperCase(
        fileName: String,
        line: Int,
        expectedPreview: String,
    ) {
        openFile(fileName)
        ui.codeEditorForFile(fileName).clickOnPosition(line, 20)
        ideFrame { invokeAction("zMyBatis.Execute", now = false) }

        ui.dialog(title = "zMyBatis — SQL Preview") {
            assertEquals(
                expectedPreview,
                textArea().text.trim(),
                "mandatory mutation preview must show the exact immutable SQL artifact",
            )
            button("Cancel").click()
        }
        ui.waitForNoOpenedDialogs()
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

    private fun writeLeapMapperAuthority(projectDir: Path) {
        val paramSource = projectDir.resolve("org/apache/ibatis/annotations/Param.java")
        Files.createDirectories(paramSource.parent)
        Files.writeString(
            paramSource,
            """
            package org.apache.ibatis.annotations;
            public @interface Param { String value(); }
            """.trimIndent(),
        )

        val mapperSource = projectDir.resolve("fixture/QueryMapper.java")
        Files.createDirectories(mapperSource.parent)
        Files.writeString(
            mapperSource,
            """
            package fixture;

            import org.apache.ibatis.annotations.Param;

            public interface QueryMapper {
                Object ping(@Param("value") long value);
                Object zero();
                Object raw(@Param("value") String value);
                Object mutate();
            }
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
