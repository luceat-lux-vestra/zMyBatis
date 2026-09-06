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
