package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2RegisteredPathParityTest {

    @Test
    fun xmlMapperMethodDynamicFamiliesMatchStockMyBatis() {
        val body = """
            <select id="find">
                <bind name="pattern" value="'%' + name + '%'"/>
                SELECT id FROM products
                <where>
                    <if test="enabled">AND active = TRUE</if>
                    <choose>
                        <when test="ids != null and ids.size > 0">
                            AND id IN
                            <foreach collection="ids" item="id" index="idx" open="(" separator="," close=")">
                                #{id,jdbcType=BIGINT}
                            </foreach>
                        </when>
                        <otherwise>AND 1 = 0</otherwise>
                    </choose>
                    <if test="name != null">AND name LIKE #{pattern,jdbcType=VARCHAR}</if>
                </where>
            </select>
        """.trimIndent()
        val fixture = xmlFixture(
            body,
            StatementKind.SELECT,
            listOf(
                parameter(0, "enabled", "boolean", "enabled"),
                parameter(1, "ids", "java.util.List<java.lang.Long>", "ids"),
                parameter(2, "name", "java.lang.String", "name"),
            ),
        )
        assertXmlStockParity(
            fixture,
            mapOf(
                0 to InputValue.BooleanValue(true),
                1 to InputValue.ListValue(listOf(integer(7), integer(9))),
                2 to InputValue.Text("pro"),
            ),
        )
    }

    @Test
    fun xmlMapperMethodSetAndTrimMatchStockMyBatis() {
        val fixture = xmlFixture(
            """
                <update id="find">
                    UPDATE products
                    <set>
                        <if test="name != null">name = #{name,jdbcType=VARCHAR},</if>
                    </set>
                    <trim prefix="WHERE" prefixOverrides="AND |OR ">
                        <if test="id != null">AND id = #{id,jdbcType=BIGINT}</if>
                    </trim>
                </update>
            """.trimIndent(),
            StatementKind.UPDATE,
            listOf(
                parameter(0, "id", "java.lang.Long", "id"),
                parameter(1, "name", "java.lang.String", "name"),
            ),
        )
        assertXmlStockParity(
            fixture,
            mapOf(0 to integer(5), 1 to InputValue.Text("updated")),
        )
    }

    @Test
    fun xmlMapperMethodScalarAndNestedMapMatchStockMyBatis() {
        val scalar = xmlFixture(
            """<select id="find">SELECT #{arbitrary,jdbcType=BIGINT}</select>""",
            StatementKind.SELECT,
            listOf(parameter(0, "id", "java.lang.Long", null)),
        )
        assertXmlStockParity(scalar, mapOf(0 to integer(42)))

        val nestedMap = xmlFixture(
            """
                <select id="find">
                    SELECT #{id,jdbcType=BIGINT}, #{nested.name,jdbcType=VARCHAR}
                </select>
            """.trimIndent(),
            StatementKind.SELECT,
            listOf(parameter(0, "payload", "java.util.Map<java.lang.String,java.lang.Object>", null)),
        )
        assertXmlStockParity(
            nestedMap,
            mapOf(
                0 to InputValue.MapValue(
                    linkedMapOf(
                        "id" to integer(9),
                        "nested" to InputValue.MapValue(
                            linkedMapOf("name" to InputValue.Text("nested")),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun xmlMapperMethodListCollectionAndArrayForeachMatchStockMyBatis() {
        val cases = listOf(
            CollectionCase(
                type = "java.util.List<java.lang.Long>",
                expression = "list",
                value = InputValue.ListValue(listOf(integer(1), integer(2))),
            ),
            CollectionCase(
                type = "java.util.Collection<java.lang.Long>",
                expression = "collection",
                value = InputValue.ListValue(listOf(integer(3), integer(4))),
            ),
            CollectionCase(
                type = "java.lang.Long[]",
                expression = "array",
                value = InputValue.ArrayValue(listOf(integer(5), integer(6))),
            ),
        )
        cases.forEach { case ->
            val fixture = xmlFixture(
                """
                    <select id="find">
                        SELECT id FROM products WHERE id IN
                        <foreach collection="${case.expression}" item="id" open="(" separator="," close=")">
                            #{id,jdbcType=BIGINT}
                        </foreach>
                    </select>
                """.trimIndent(),
                StatementKind.SELECT,
                listOf(parameter(0, "ids", case.type, null)),
            )
            assertXmlStockParity(fixture, mapOf(0 to case.value))
        }
    }

    @Test
    fun javaStaticCrudKeepsStatementKindAndOrderedBindings() {
        val cases = listOf(
            JavaCase(StatementKind.SELECT, "SELECT 1", emptyList(), emptyMap(), "SELECT 1", emptyList()),
            JavaCase(
                StatementKind.INSERT,
                "INSERT INTO products(id, name) VALUES(#{id,jdbcType=BIGINT}, #{name,jdbcType=VARCHAR})",
                listOf(
                    parameter(0, "id", "java.lang.Long", "id"),
                    parameter(1, "name", "java.lang.String", "name"),
                ),
                mapOf(0 to integer(1), 1 to InputValue.Text("one")),
                "INSERT INTO products(id, name) VALUES(?, ?)",
                listOf(integer(1), InputValue.Text("one")),
            ),
            JavaCase(
                StatementKind.UPDATE,
                "UPDATE products SET name = #{name,jdbcType=VARCHAR} WHERE id = #{id,jdbcType=BIGINT}",
                listOf(
                    parameter(0, "id", "java.lang.Long", "id"),
                    parameter(1, "name", "java.lang.String", "name"),
                ),
                mapOf(0 to integer(2), 1 to InputValue.Text("two")),
                "UPDATE products SET name = ? WHERE id = ?",
                listOf(InputValue.Text("two"), integer(2)),
            ),
            JavaCase(
                StatementKind.DELETE,
                "DELETE FROM products WHERE id = #{id,jdbcType=BIGINT}",
                listOf(parameter(0, "id", "java.lang.Long", "id")),
                mapOf(0 to integer(3)),
                "DELETE FROM products WHERE id = ?",
                listOf(integer(3)),
            ),
        )

        cases.forEach { item ->
            val source = javaSource(item.kind, item.sql, item.parameters)
            val execution = javaSuccess(source, item.values)
            assertEquals(item.kind, execution.statementKind)
            assertEquals(item.expectedSql, normalizeSql(execution.sqlWithPlaceholders))
            assertEquals(item.expectedValues, execution.orderedBindings.map { it.value })
        }
    }

    @Test
    fun javaDynamicScriptChooseForeachBindMatchesFrozenStockSemantics() {
        val source = javaSource(
            StatementKind.SELECT,
            """
                <script>
                    <bind name="pattern" value="'%' + name + '%'"/>
                    SELECT id FROM products
                    <where>
                        <if test="enabled">active = TRUE</if>
                        <choose>
                            <when test="ids != null and ids.size > 0">
                                AND id IN
                                <foreach collection="ids" item="id" index="idx" open="(" separator="," close=")">
                                    #{id,jdbcType=BIGINT}
                                </foreach>
                            </when>
                            <otherwise>AND 1 = 0</otherwise>
                        </choose>
                        <if test="name != null">AND name LIKE #{pattern,jdbcType=VARCHAR}</if>
                    </where>
                </script>
            """.trimIndent(),
            listOf(
                parameter(0, "enabled", "boolean", "enabled"),
                parameter(1, "ids", "java.util.List<java.lang.Long>", "ids"),
                parameter(2, "name", "java.lang.String", "name"),
            ),
        )
        val execution = javaSuccess(
            source,
            mapOf(
                0 to InputValue.BooleanValue(true),
                1 to InputValue.ListValue(listOf(integer(7), integer(9))),
                2 to InputValue.Text("pro"),
            ),
        )

        assertEquals(
            "SELECT id FROM products WHERE active = TRUE AND id IN ( ? , ? ) AND name LIKE ?",
            normalizeSql(execution.sqlWithPlaceholders),
        )
        assertEquals(
            listOf(integer(7), integer(9), InputValue.Text("%pro%")),
            execution.orderedBindings.map { it.value },
        )
    }

    private fun assertXmlStockParity(
        fixture: XmlFixture,
        values: Map<Int, InputValue>,
    ) {
        val contract = MyBatisInvocationContractFactory.build(fixture.source)
        assertFalse("contract blocked: " + contract.blockingProblems.map { it.code }, contract.isPreparationBlocked)
        val environment = mapperEnvironment(contract, values)
        val request = request(fixture.source, contract, environment)
        val payload = when (val result = MyBatisInvocationParameterObjectFactory.create(request)) {
            is MyBatisInvocationParameterObjectFactory.Result.Success -> result.payload
            is MyBatisInvocationParameterObjectFactory.Result.Failed ->
                throw AssertionError("payload failed: " + result.failure)
        }
        val execution = when (val result = XmlMapperPreparationEngine.prepare(request)) {
            is PreparationResult.Success -> result.execution
            is PreparationResult.Failed -> throw AssertionError("P2 XML preparation failed: " + result.failure)
        }
        val stock = stockSnapshot(fixture.content, fixture.statementId.namespace + "." + fixture.statementId.statementId, payload.parameterObject)

        assertEquals(normalizeSql(stock.sql), normalizeSql(execution.sqlWithPlaceholders))
        assertEquals(stock.properties, execution.orderedBindings.map { it.property })
        assertEquals(
            stock.values.map(::canonicalRuntimeValue),
            execution.orderedBindings.map { canonicalInputValue(it.value) },
        )
        assertEquals(fixture.source.sourceRevisions, execution.sourceRevisions)
        assertEquals(fixture.source.sourceGraph.rootStatement.id, execution.statementId)
    }

    private fun stockSnapshot(
        content: String,
        statementId: String,
        parameterObject: Any?,
    ): StockSnapshot {
        val configuration = Configuration()
        ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)).use { input ->
            XMLMapperBuilder(
                input,
                configuration,
                "p2-parity.xml",
                configuration.sqlFragments,
            ).parse()
        }
        val boundSql = configuration.getMappedStatement(statementId).getBoundSql(parameterObject)
        return StockSnapshot(
            sql = boundSql.sql,
            properties = boundSql.parameterMappings.map { it.property },
            values = boundSql.parameterMappings.map { mapping ->
                stockEffectiveParameterMappingMetadata(
                    configuration,
                    boundSql,
                    parameterObject,
                    mapping,
                ).runtimeValue
            },
        )
    }

    private fun javaSuccess(
        source: PreparationSource.JavaAnnotation,
        values: Map<Int, InputValue>,
    ): com.algorist.zMyBatis.core.preparation.PreparedExecution {
        val contract = MyBatisInvocationContractFactory.build(source)
        val environment = mapperEnvironment(contract, values)
        return when (
            val result = MyBatisPreparationEngine.prepare(request(source, contract, environment))
        ) {
            is PreparationResult.Success -> result.execution
            is PreparationResult.Failed -> throw AssertionError("P2 Java preparation failed: " + result.failure)
        }
    }

    private fun mapperEnvironment(
        contract: ParameterContract,
        values: Map<Int, InputValue>,
    ): InputEnvironment {
        val inputs = contract.requirements.map { requirement ->
            val index = requirement.provenance.evidence
                .filterIsInstance<InputEvidence.MapperMethodParameter>()
                .single()
                .index
            ProvidedInput(
                requirement.id,
                requireNotNull(values[index]) { "missing fixture value for mapper parameter $index" },
                ExecutionInputOrigin.USER_ENTERED,
            )
        }
        return environment(contract, inputs)
    }

    private fun environment(
        contract: ParameterContract,
        inputs: List<ProvidedInput>,
    ): InputEnvironment {
        val result = InputEnvironment.validate(contract, inputs)
        assertTrue("input environment failed: " + result, result is InputEnvironmentResult.Success)
        return (result as InputEnvironmentResult.Success).environment
    }

    private fun request(
        source: PreparationSource,
        contract: ParameterContract,
        environment: InputEnvironment,
    ): MyBatisPreparationRequest =
        (MyBatisPreparationRequest.create(source, contract, environment) as PreparationRequestResult.Ready).request

    private fun xmlFixture(
        body: String,
        kind: StatementKind,
        parameters: List<JavaMethodParameterMetadata>,
    ): XmlFixture {
        val file = SourceFileId("vfs:/P2Mapper.xml")
        val statementId = XmlStatementId(file, "p2.Mapper", "find")
        val content =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n" +
                "<mapper namespace=\"p2.Mapper\">\n" +
                body +
                "\n</mapper>"
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, kind, SourceRange(0, content.length)),
            listOf(SourceSnapshot(file, SourceRevision("xml-r1"), content)),
            emptyList(),
        )
        val javaSource = SourceSnapshot(
            SourceFileId("vfs:/P2Mapper.java"),
            SourceRevision("java-r1"),
            "x".repeat(256),
        )
        val mapperMethod = XmlMapperMethodCapture(
            statementId,
            javaSource,
            SourceRange(20, 120),
            parameters,
        )
        return XmlFixture(
            XmlMapperPreparationSource(graph, mapperMethod = mapperMethod),
            content,
            statementId,
        )
    }

    private fun javaSource(
        kind: StatementKind,
        sql: String,
        parameters: List<JavaMethodParameterMetadata>,
    ): PreparationSource.JavaAnnotation {
        val file = SourceFileId("vfs:/P2AnnotatedMapper.java")
        val statementId = JavaStatementId(
            file,
            "p2.P2AnnotatedMapper",
            MethodSignature("query", parameters.map { it.typeIdentity }),
        )
        val snapshot = SourceSnapshot(file, SourceRevision("java-r1"), "authoritative-java-source")
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, kind, SourceRange(0, snapshot.content.length)),
            listOf(snapshot),
            emptyList(),
        )
        return PreparationSource.JavaAnnotation(
            JavaAnnotationStatementCapture(graph, listOf(sql), parameters),
        )
    }

    private fun parameter(
        index: Int,
        sourceName: String,
        type: String,
        alias: String?,
    ) = JavaMethodParameterMetadata(index, sourceName, JavaTypeIdentity(type), alias)

    private fun integer(value: Long) = InputValue.IntegerValue(BigInteger.valueOf(value))

    private fun normalizeSql(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private fun canonicalInputValue(value: InputValue): Any? = when (value) {
        InputValue.NullValue -> null
        is InputValue.Text -> value.value
        is InputValue.RawText -> value.value
        is InputValue.BooleanValue -> value.value
        is InputValue.IntegerValue -> value.value.toString()
        is InputValue.DecimalValue -> value.value.stripTrailingZeros().toPlainString()
        is InputValue.DateValue -> value.value
        is InputValue.TimeValue -> value.value
        is InputValue.DateTimeValue -> value.value
        is InputValue.InstantValue -> value.value
        is InputValue.UuidValue -> value.value
        is InputValue.ObjectValue -> value.entries.mapValues { canonicalInputValue(it.value) }
        is InputValue.MapValue -> value.entries.mapValues { canonicalInputValue(it.value) }
        is InputValue.ListValue -> value.elements.map(::canonicalInputValue)
        is InputValue.ArrayValue -> value.elements.map(::canonicalInputValue)
    }

    private fun canonicalRuntimeValue(value: Any?): Any? = when (value) {
        null -> null
        is Byte, is Short, is Int, is Long, is BigInteger -> value.toString()
        is Float, is Double, is BigDecimal -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        else -> value
    }

    private data class XmlFixture(
        val source: XmlMapperPreparationSource,
        val content: String,
        val statementId: XmlStatementId,
    )

    private data class StockSnapshot(
        val sql: String,
        val properties: List<String>,
        val values: List<Any?>,
    )

    private data class CollectionCase(
        val type: String,
        val expression: String,
        val value: InputValue,
    )

    private data class JavaCase(
        val kind: StatementKind,
        val sql: String,
        val parameters: List<JavaMethodParameterMetadata>,
        val values: Map<Int, InputValue>,
        val expectedSql: String,
        val expectedValues: List<InputValue>,
    )
}
