package com.algorist.zMyBatis

import com.algorist.zMyBatis.settings.ZMyBatisSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.apache.ibatis.builder.BuilderException

class MyBatisEvaluatorNegativeBoundaryTest : BasePlatformTestCase() {

    private lateinit var settings: ZMyBatisSettings
    private var originalIgnoreUnknownTags = false
    private var originalStrictOgnlMode = false

    override fun setUp() {
        super.setUp()
        settings = ZMyBatisSettings.getInstance()
        originalIgnoreUnknownTags = settings.ignoreUnknownTags
        originalStrictOgnlMode = settings.strictOgnlMode
        settings.ignoreUnknownTags = false
        settings.strictOgnlMode = false
    }

    override fun tearDown() {
        try {
            if (::settings.isInitialized) {
                settings.ignoreUnknownTags = originalIgnoreUnknownTags
                settings.strictOgnlMode = originalStrictOgnlMode
            }
        } finally {
            super.tearDown()
        }
    }

    fun testUnknownTagDefaultModeReturnsSqlLookingPluginErrorText() {
        val xml = """
            <select>
            SELECT * FROM users
            <where>
                <custom-filter>AND active = 1</custom-filter>
            </where>
            </select>
        """.trimIndent()

        val result = MyBatisEvaluator.evaluate(xml, emptyMap())

        assertTrue("actual result: <$result>", result.startsWith("-- [MyBatis Plugin Error]"))
        assertTrue("actual result: <$result>", result.contains("custom-filter"))
        assertTrue("actual result: <$result>", result.contains("-- Input:"))
    }

    fun testIgnoreUnknownTagsStripsUnknownWrapperAndPreservesInnerSql() {
        settings.ignoreUnknownTags = true
        val xml = """
            <select>
            SELECT * FROM users
            <where>
                <custom-filter>AND active = 1</custom-filter>
            </where>
            </select>
        """.trimIndent()

        assertEquals(
            "SELECT * FROM users WHERE active = 1",
            MyBatisEvaluator.evaluate(xml, emptyMap())
        )
    }

    fun testIgnoreUnknownTagsStripsUnresolvedIncludeIntoTruncatedSql() {
        settings.ignoreUnknownTags = true
        val xml = """
            <select id="findUser">
            SELECT <include refid="Base_Column_List" /> FROM users
            </select>
        """.trimIndent()

        assertEquals(
            "SELECT FROM users",
            MyBatisEvaluator.evaluate(xml, emptyMap())
        )
    }

    fun testOgnlCoercionFailureStrictOffReturnsSqlLookingPluginErrorText() {
        val result = MyBatisEvaluator.evaluate(ognlCoercionFailureXml(), mapOf("kind" to "B"))

        assertTrue("actual result: <$result>", result.startsWith("-- [MyBatis Plugin Error]"))
        assertTrue("actual result: <$result>", result.contains("For input string: \"B\""))
    }

    fun testOgnlCoercionFailureStrictOnStillReturnsSqlLookingPluginErrorText() {
        settings.strictOgnlMode = true

        val result = MyBatisEvaluator.evaluate(ognlCoercionFailureXml(), mapOf("kind" to "B"))

        assertTrue("actual result: <$result>", result.startsWith("-- [MyBatis Plugin Error]"))
        assertTrue("actual result: <$result>", result.contains("For input string: \"B\""))
    }

    fun testClassifiedOgnlFailureStrictOffReturnsSqlLookingPluginErrorText() {
        val result = MyBatisEvaluator.evaluate(classifiedOgnlFailureXml(), mapOf("kind" to "B"))

        assertTrue("actual result: <$result>", result.startsWith("-- [MyBatis Plugin Error]"))
        assertTrue("actual result: <$result>", result.contains("Error evaluating expression"))
    }

    fun testClassifiedOgnlFailureStrictOnRethrowsBuilderException() {
        settings.strictOgnlMode = true

        try {
            MyBatisEvaluator.evaluate(classifiedOgnlFailureXml(), mapOf("kind" to "B"))
            fail("strict OGNL mode must rethrow failures classified by isOgnlError")
        } catch (e: BuilderException) {
            assertTrue("actual message: <${e.message}>", e.message?.startsWith("Error evaluating expression") == true)
        }
    }

    fun testExecutionEvaluationUnknownTagFailsClosedWithCompatibilitySettingOn() {
        settings.ignoreUnknownTags = true
        val xml = """
            <select>
            SELECT * FROM users
            <where>
                <custom-filter>AND active = 1</custom-filter>
            </where>
            </select>
        """.trimIndent()

        val shipping = MyBatisEvaluator.evaluateForExecution(xml, emptyMap())

        assertTrue(
            "shipping execution must ignore compatibility stripping and fail closed: <$shipping>",
            shipping is LegacyExecutionEvaluationResult.Failed,
        )
        assertEquals(
            "SELECT * FROM users WHERE active = 1",
            MyBatisEvaluator.evaluate(xml, emptyMap()),
        )
    }

    fun testExecutionEvaluationSupportedDynamicTagStillEvaluatesWithCompatibilitySettingOn() {
        settings.ignoreUnknownTags = true
        val xml = """
            <select>
            SELECT * FROM users
            <where>
                <if test="active">AND active = 1</if>
            </where>
            </select>
        """.trimIndent()

        assertEquals(
            LegacyExecutionEvaluationResult.Evaluated("SELECT * FROM users WHERE active = 1"),
            MyBatisEvaluator.evaluateForExecution(xml, mapOf("active" to true)),
        )
    }

    fun testExecutionEvaluationUnknownTagFailsClosedInsteadOfReturningCompatibilitySql() {
        val xml = """
            <select>
            SELECT * FROM users
            <where>
                <custom-filter>AND active = 1</custom-filter>
            </where>
            </select>
        """.trimIndent()

        val result = MyBatisEvaluator.evaluateForExecution(xml, emptyMap())

        assertTrue(
            "shipping execution result must be non-executable: <$result>",
            result is LegacyExecutionEvaluationResult.Failed,
        )
    }

    fun testExecutionEvaluationClassifiedOgnlFailureFailsClosedWithStrictModeOff() {
        val result = MyBatisEvaluator.evaluateForExecution(
            classifiedOgnlFailureXml(),
            mapOf("kind" to "B"),
        )

        assertTrue(
            "shipping execution result must be non-executable: <$result>",
            result is LegacyExecutionEvaluationResult.Failed,
        )
    }

    fun testExecutionEvaluationMalformedXmlFailsClosed() {
        val xml = """
            <select>
            SELECT <if test="id != null">id FROM users
            </select>
        """.trimIndent()

        val result = MyBatisEvaluator.evaluateForExecution(xml, mapOf("id" to 1))

        assertTrue(
            "shipping execution result must be non-executable: <$result>",
            result is LegacyExecutionEvaluationResult.Failed,
        )
    }

    fun testExecutionEvaluationSuccessfulSqlRemainsExplicitlyEvaluated() {
        val result = MyBatisEvaluator.evaluateForExecution("SELECT 1", emptyMap())

        assertTrue(
            "successful execution result must carry SQL: <$result>",
            result is LegacyExecutionEvaluationResult.Evaluated,
        )
        assertEquals(
            "SELECT 1",
            (result as LegacyExecutionEvaluationResult.Evaluated).sql,
        )
    }

    fun testExecutionEvaluationDirectListFailsClosedBeforeCompatibilityMarker() {
        val result = MyBatisEvaluator.evaluateForExecution(
            "SELECT #{items}",
            mapOf("items" to listOf(1, 2)),
        )

        assertUnsupportedExecutionLiteral(result, LegacyUnsupportedLiteralKind.LIST)
    }

    fun testExecutionEvaluationDirectMapFailsClosedBeforeCompatibilityMarker() {
        val result = MyBatisEvaluator.evaluateForExecution(
            "SELECT #{user}",
            mapOf("user" to mapOf("id" to 1)),
        )

        assertUnsupportedExecutionLiteral(result, LegacyUnsupportedLiteralKind.MAP)
    }

    fun testExecutionEvaluationDirectArrayAndCustomObjectFailClosedBeforeToStringFallback() {
        val opaque = object {
            override fun toString(): String = "sensitive-object-text"
        }
        val values = listOf(
            arrayOf(1, 2) to LegacyUnsupportedLiteralKind.OBJECT,
            opaque to LegacyUnsupportedLiteralKind.OBJECT,
        )

        for ((value, expectedKind) in values) {
            val result = MyBatisEvaluator.evaluateForExecution(
                "SELECT #{value}",
                mapOf("value" to value),
            )

            assertUnsupportedExecutionLiteral(result, expectedKind)
            val cause = (result as LegacyExecutionEvaluationResult.Failed).cause
            assertFalse(
                "failure diagnostic must not expose unsupported value text",
                cause.message.orEmpty().contains("sensitive-object-text"),
            )
        }
    }

    fun testExecutionEvaluationBoundScalarRemainsEvaluated() {
        val result = MyBatisEvaluator.evaluateForExecution(
            "SELECT #{id}, #{name}, #{enabled}",
            mapOf("id" to 7, "name" to "A", "enabled" to true),
        )

        assertEquals(
            LegacyExecutionEvaluationResult.Evaluated("SELECT 7, 'A', 1"),
            result,
        )
    }

    fun testExecutionEvaluationQualifiedNestedOgnlUsesStockMapNavigation() {
        val xml = """
            <select>
            SELECT 1
            <if test="user.profile.active">, 2</if>
            </select>
        """.trimIndent()
        val params = mapOf(
            "user" to linkedMapOf(
                "profile" to linkedMapOf("active" to true),
            ),
        )

        assertEquals(
            LegacyExecutionEvaluationResult.Evaluated("SELECT 1 , 2"),
            MyBatisEvaluator.evaluateForExecution(xml, params),
        )
    }

    fun testExecutionEvaluationDoesNotSearchNestedMapsForMissingOgnlProperty() {
        val xml = """
            <select>
            SELECT 1
            <if test="user.active">, 2</if>
            </select>
        """.trimIndent()
        val params = mapOf(
            "user" to linkedMapOf(
                "profile" to linkedMapOf("active" to true),
            ),
        )

        assertEquals(
            LegacyExecutionEvaluationResult.Evaluated("SELECT 1"),
            MyBatisEvaluator.evaluateForExecution(xml, params),
        )
    }

    fun testExecutionEvaluationStructuredNavigationStillResolvesSupportedScalars() {
        val result = MyBatisEvaluator.evaluateForExecution(
            "SELECT #{user.id}, #{items[0]}",
            mapOf(
                "user" to mapOf("id" to 11),
                "items" to listOf(22),
            ),
        )

        assertEquals(
            LegacyExecutionEvaluationResult.Evaluated("SELECT 11, 22"),
            result,
        )
    }

    fun testExecutionEvaluationLegacyDateAndTemporalScalarsRemainEvaluated() {
        val timestamp = java.sql.Timestamp.valueOf("2026-01-02 03:04:05")
        val date = java.time.LocalDate.of(2026, 1, 2)
        val result = MyBatisEvaluator.evaluateForExecution(
            "SELECT #{timestamp}, #{date}",
            mapOf("timestamp" to timestamp, "date" to date),
        )

        assertEquals(
            LegacyExecutionEvaluationResult.Evaluated(
                "SELECT '2026-01-02 03:04:05.0', '2026-01-02'",
            ),
            result,
        )
    }

    fun testDirectCustomObjectRetainsLegacyToStringCompatibility() {
        val opaque = object {
            override fun toString(): String = "legacy-object"
        }

        assertEquals(
            "SELECT 'legacy-object'",
            MyBatisEvaluator.evaluate("SELECT #{value}", mapOf("value" to opaque)),
        )
    }

    fun testUnsupportedDirectListRemainsExecutableLookingSqlStringWithNullMarker() {
        assertEquals(
            "SELECT /*[ERROR: List — use <foreach>]*/NULL",
            MyBatisEvaluator.evaluate("SELECT #{items}", mapOf("items" to listOf(1, 2)))
        )
    }

    fun testMalformedXmlReturnsSqlLookingPluginErrorText() {
        val xml = """
            <select>
            SELECT <if test="id != null">id FROM users
            </select>
        """.trimIndent()

        val result = MyBatisEvaluator.evaluate(xml, mapOf("id" to 1))

        assertTrue("actual result: <$result>", result.startsWith("-- [MyBatis Plugin Error]"))
        assertTrue("actual result: <$result>", result.contains("-- Input:"))
    }

    private fun assertUnsupportedExecutionLiteral(
        result: LegacyExecutionEvaluationResult,
        expectedKind: LegacyUnsupportedLiteralKind,
    ) {
        assertTrue(
            "shipping execution result must be non-executable: <$result>",
            result is LegacyExecutionEvaluationResult.Failed,
        )
        val cause = (result as LegacyExecutionEvaluationResult.Failed).cause
        assertTrue(
            "failure must identify unsupported legacy literalization: <${cause::class.java.name}>",
            cause is LegacyUnsupportedExecutionLiteralException,
        )
        assertEquals(
            expectedKind,
            (cause as LegacyUnsupportedExecutionLiteralException).kind,
        )
    }

    private fun ognlCoercionFailureXml(): String = """
        <select>
        SELECT
        <if test="kind == 'B'">1</if>
        </select>
    """.trimIndent()

    private fun classifiedOgnlFailureXml(): String = """
        <select>
        SELECT
        <if test="kind.noSuchMethod()">1</if>
        </select>
    """.trimIndent()
}
