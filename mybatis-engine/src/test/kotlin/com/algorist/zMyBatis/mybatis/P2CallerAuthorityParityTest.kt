package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputUsageKind
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
import com.algorist.zMyBatis.mybatis.parity.OracleParameterMapper
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.math.BigInteger
import org.apache.ibatis.reflection.ParamNameResolver
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2CallerAuthorityParityTest {
    private val rawOpen = String(charArrayOf(36.toChar(), 123.toChar()))

    @Test
    fun mapperMethodPayloadMatchesStockParamNameResolverForScalarMultiAndCollectionShapes() {
        val scalar = javaFixture(
            sql = "SELECT #{value,jdbcType=BIGINT}",
            parameters = listOf(parameter(0, "value", "java.lang.Long", null)),
        )
        val scalarPayload = payload(scalar.source, mapOf(0 to integer(41)))
        val scalarMethod = method("scalar")
        assertEquals(
            normalizeRuntime(
                ParamNameResolver(Configuration(), scalarMethod, scalarMethod.declaringClass)
                    .getNamedParams(arrayOf(41L)),
            ),
            normalizeRuntime(scalarPayload.parameterObject),
        )

        val multiple = javaFixture(
            sql = "SELECT #{id,jdbcType=BIGINT}, #{name,jdbcType=VARCHAR}",
            parameters = listOf(
                parameter(0, "id", "java.lang.Long", "id"),
                parameter(1, "name", "java.lang.String", "name"),
            ),
        )
        val multiplePayload = payload(
            multiple.source,
            mapOf(0 to integer(7), 1 to InputValue.Text("seven")),
        )
        val multipleMethod = method("multiple")
        assertEquals(
            normalizeRuntime(
                ParamNameResolver(Configuration(), multipleMethod, multipleMethod.declaringClass)
                    .getNamedParams(arrayOf<Any?>(7L, "seven")),
            ),
            normalizeRuntime(multiplePayload.parameterObject),
        )

        collectionCase(
            methodName = "list",
            declaredType = "java.util.List<java.lang.Long>",
            value = InputValue.ListValue(listOf(integer(1), integer(2))),
            runtimeValue = listOf(1L, 2L),
            requiredAliases = setOf("collection", "list"),
        )
        collectionCase(
            methodName = "collection",
            declaredType = "java.util.Collection<java.lang.Long>",
            value = InputValue.ListValue(listOf(integer(3), integer(4))),
            runtimeValue = listOf(3L, 4L),
            requiredAliases = setOf("collection"),
        )
        collectionCase(
            methodName = "array",
            declaredType = "java.lang.Long[]",
            value = InputValue.ArrayValue(listOf(integer(5), integer(6))),
            runtimeValue = arrayOf(5L, 6L),
            requiredAliases = setOf("array"),
        )
    }

    @Test
    fun xmlMapperMethodObjectAndNestedPropertiesUseCallerAuthorityWithoutSyntaxAdmission() {
        val source = xmlSource(
            body = """
                <select id="find">
                    SELECT id FROM products
                    <where>
                        <if test="filter.enabled">AND tenant_id = #{filter.tenantId,jdbcType=BIGINT}</if>
                    </where>
                </select>
            """.trimIndent(),
            mapperParameters = listOf(
                parameter(0, "filter", "com.acme.Filter", "filter"),
            ),
        )
        val contract = MyBatisInvocationContractFactory.build(source)
        assertFalse(contract.isPreparationBlocked)
        assertEquals(InputShape.OBJECT, contract.requirements.single().expectedType.shape)
        assertEquals(setOf(InputUsageKind.BOUND_PLACEHOLDER), contract.requirements.single().usageKinds)

        val environment = environment(
            contract,
            listOf(
                contract.requirements.single().id to InputValue.ObjectValue(
                    linkedMapOf(
                        "enabled" to InputValue.BooleanValue(true),
                        "tenantId" to integer(77),
                    ),
                ),
            ),
        )
        val execution = xmlSuccess(request(source, contract, environment))

        assertEquals(
            "SELECT id FROM products WHERE tenant_id = ?",
            normalizeSql(execution.sqlWithPlaceholders),
        )
        assertEquals(listOf("filter.tenantId"), execution.orderedBindings.map { it.property })
        assertEquals(listOf(integer(77)), execution.orderedBindings.map { it.value })
        assertEquals(source.sourceGraph.rootStatement.id, execution.statementId)
        assertEquals(source.sourceRevisions, execution.sourceRevisions)
    }

    @Test
    fun xmlOnlyObjectEnvironmentOwnsChooseForeachBindAndRawUsage() {
        val source = xmlSource(
            body = """
                <select id="find">
                    <bind name="pattern" value="'%' + name + '%'"/>
                    SELECT ${rawOpen}column} FROM products
                    <where>
                        <if test="enabled">AND active = TRUE</if>
                        <choose>
                            <when test="ids != null and ids.size > 0">
                                AND id IN
                                <foreach collection="ids" item="item" index="idx" open="(" separator="," close=")">
                                    #{item,jdbcType=BIGINT}
                                </foreach>
                            </when>
                            <otherwise>AND 1 = 0</otherwise>
                        </choose>
                        <if test="name != null">AND name LIKE #{pattern,jdbcType=VARCHAR}</if>
                    </where>
                </select>
            """.trimIndent(),
        )
        val contract = MyBatisInvocationContractFactory.build(source)
        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals("xml-parameter-object", requirement.id.value)
        assertEquals(InputShape.OBJECT, requirement.expectedType.shape)
        assertEquals(
            setOf(InputUsageKind.BOUND_PLACEHOLDER, InputUsageKind.RAW_INTERPOLATION),
            requirement.usageKinds,
        )

        val environment = environment(
            contract,
            listOf(
                requirement.id to InputValue.ObjectValue(
                    linkedMapOf(
                        "column" to InputValue.Text("name"),
                        "enabled" to InputValue.BooleanValue(true),
                        "ids" to InputValue.ListValue(listOf(integer(7), integer(9))),
                        "name" to InputValue.Text("pro"),
                    ),
                ),
            ),
        )
        val execution = xmlSuccess(request(source, contract, environment))

        assertEquals(
            "SELECT name FROM products WHERE active = TRUE AND id IN ( ? , ? ) AND name LIKE ?",
            normalizeSql(execution.sqlWithPlaceholders),
        )
        assertEquals(
            listOf(integer(7), integer(9), InputValue.Text("%pro%")),
            execution.orderedBindings.map { it.value },
        )
        assertEquals(1, execution.rawInterpolations.size)
        assertEquals(requirement.id, execution.rawInterpolations.single().requirementId)
        assertEquals(source.sourceGraph.rootStatement.id, execution.statementId)
        assertEquals(source.sourceRevisions, execution.sourceRevisions)
    }

    @Test
    fun xmlOnlyObjectContractCarriesCallerPathHintsWithoutPromotingMyBatisInternals() {
        val source = xmlSource(
            body = """
                <select id="find">
                    <bind name="pattern" value="'%' + search + '%'"/>
                    SELECT
                    <if test="user.id == 1">id</if>
                    <if test="cust.name == 'test'">, name</if>
                    FROM customers
                    WHERE id = #{test}
                    AND name LIKE #{pattern}
                    AND id IN
                    <foreach collection="ids" item="item" open="(" separator="," close=")">
                        #{item}
                    </foreach>
                </select>
            """.trimIndent(),
        )

        val contract = MyBatisInvocationContractFactory.build(source)
        val requirement = contract.requirements.single()
        val evidence = requirement.provenance.evidence

        assertTrue(evidence.any {
            it is InputEvidence.OgnlExpression && it.expression == "user.id == 1"
        })
        assertTrue(evidence.any {
            it is InputEvidence.OgnlExpression && it.expression == "cust.name == 'test'"
        })
        assertTrue(evidence.any {
            it is InputEvidence.OgnlExpression && it.expression == "'%' + search + '%'"
        })
        assertTrue(evidence.any {
            it is InputEvidence.Placeholder &&
                it.kind == com.algorist.zMyBatis.core.input.InputKind.BOUND &&
                it.expression == "test"
        })
        assertTrue(evidence.any {
            it is InputEvidence.ForeachCollection && it.expression == "ids"
        })
        assertFalse(evidence.any {
            it is InputEvidence.Placeholder && it.expression in setOf("pattern", "item")
        })
    }

    @Test
    fun xmlOnlyStaticBindLiteralDoesNotManufactureCallerInput() {
        val source = xmlSource(
            body = """
                <select id="find">
                    <bind name="value" value="41L"/>
                    SELECT #{value,jdbcType=BIGINT}
                </select>
            """.trimIndent(),
        )

        val contract = MyBatisInvocationContractFactory.build(source)

        assertFalse(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
    }

    @Test
    fun javaDynamicScriptKeepsOneTypedCallerForBoundAndRawUse() {
        val fixture = javaFixture(
            sql = """
                <script>
                    SELECT ${rawOpen}value} AS raw_value, #{value,jdbcType=VARCHAR} AS bound_value
                    <if test="value != null">, 1 AS enabled</if>
                </script>
            """.trimIndent(),
            parameters = listOf(parameter(0, "value", "java.lang.String", "value")),
        )
        val contract = MyBatisInvocationContractFactory.build(fixture.source)
        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputShape.SCALAR, requirement.expectedType.shape)
        assertEquals(
            setOf(InputUsageKind.BOUND_PLACEHOLDER, InputUsageKind.RAW_INTERPOLATION),
            requirement.usageKinds,
        )

        val environment = environment(contract, listOf(requirement.id to InputValue.Text("42")))
        val prepared = MyBatisPreparationEngine.prepare(request(fixture.source, contract, environment))
        val execution = (prepared as PreparationResult.Success).execution

        assertEquals(
            "SELECT 42 AS raw_value, ? AS bound_value , 1 AS enabled",
            normalizeSql(execution.sqlWithPlaceholders),
        )
        assertEquals(listOf(InputValue.Text("42")), execution.orderedBindings.map { it.value })
        assertEquals(1, execution.rawInterpolations.size)
        assertEquals(requirement.id, execution.rawInterpolations.single().requirementId)
        assertEquals(fixture.source.sourceGraph.rootStatement.id, execution.statementId)
        assertEquals(
            fixture.source.sourceGraph.sourceSnapshots.associate { it.fileId to it.revision },
            execution.sourceRevisions,
        )
    }

    @Test
    fun parameterizedIncludePropertyDoesNotBecomeRuntimeRawCallerInput() {
        val source = xmlSource(
            body = """
                <sql id="columns">${rawOpen}alias}.id, ${rawOpen}alias}.name</sql>
                <select id="find">
                    SELECT
                    <include refid="columns">
                        <property name="alias" value="p"/>
                    </include>
                    FROM products p
                </select>
            """.trimIndent(),
        )
        val contract = MyBatisInvocationContractFactory.build(source)

        assertFalse(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        val environment = environment(contract, emptyList())
        val execution = xmlSuccess(request(source, contract, environment))
        assertEquals("SELECT p.id, p.name FROM products p", normalizeSql(execution.sqlWithPlaceholders))
        assertTrue(execution.rawInterpolations.isEmpty())
    }

    private fun collectionCase(
        methodName: String,
        declaredType: String,
        value: InputValue,
        runtimeValue: Any,
        requiredAliases: Set<String>,
    ) {
        val fixture = javaFixture(
            sql = "SELECT 1",
            parameters = listOf(parameter(0, "ids", declaredType, null)),
        )
        val actual = normalizeRuntime(payload(fixture.source, mapOf(0 to value)).parameterObject) as Map<*, *>
        val method = method(methodName)
        val expected = normalizeRuntime(
            ParamNameResolver(Configuration(), method, method.declaringClass)
                .getNamedParams(arrayOf(runtimeValue)),
        ) as Map<*, *>

        for (alias in requiredAliases) {
            assertTrue("stock ParamNameResolver missing $alias", expected.containsKey(alias))
            assertTrue("P2 payload missing $alias", actual.containsKey(alias))
            assertEquals(expected[alias], actual[alias])
        }
    }

    private fun payload(
        source: PreparationSource,
        valuesByParameterIndex: Map<Int, InputValue>,
    ): MyBatisInvocationParameterObjectFactory.Ready {
        val contract = MyBatisInvocationContractFactory.build(source)
        assertFalse(
            "fixture contract blocked: " + contract.blockingProblems.map { it.code },
            contract.isPreparationBlocked,
        )
        val provided = contract.requirements.map { requirement ->
            val parameter = requirement.provenance.evidence
                .filterIsInstance<InputEvidence.MapperMethodParameter>()
                .single()
            ProvidedInput(
                requirement.id,
                requireNotNull(valuesByParameterIndex[parameter.index]),
                ExecutionInputOrigin.USER_ENTERED,
            )
        }
        val environment = (InputEnvironment.validate(contract, provided) as InputEnvironmentResult.Success).environment
        val request = request(source, contract, environment)
        return when (val result = MyBatisInvocationParameterObjectFactory.create(request)) {
            is MyBatisInvocationParameterObjectFactory.Result.Success -> result.payload
            is MyBatisInvocationParameterObjectFactory.Result.Failed ->
                throw AssertionError("expected payload success: " + result.failure)
        }
    }

    private fun request(
        source: PreparationSource,
        contract: ParameterContract,
        environment: InputEnvironment,
    ): MyBatisPreparationRequest =
        (MyBatisPreparationRequest.create(source, contract, environment) as PreparationRequestResult.Ready).request

    private fun environment(
        contract: ParameterContract,
        values: List<Pair<InputRequirementId, InputValue>>,
    ): InputEnvironment {
        val result = InputEnvironment.validate(
            contract,
            values.map { (id, value) -> ProvidedInput(id, value, ExecutionInputOrigin.USER_ENTERED) },
        )
        assertTrue("input environment must validate: " + result, result is InputEnvironmentResult.Success)
        return (result as InputEnvironmentResult.Success).environment
    }

    private fun xmlSuccess(request: MyBatisPreparationRequest) =
        when (val result = XmlMapperPreparationEngine.prepare(request)) {
            is PreparationResult.Success -> result.execution
            is PreparationResult.Failed -> throw AssertionError(
                "expected XML preparation success, got kind=" + result.failure.kind +
                    ", code=" + result.failure.code +
                    ", property=" + result.failure.bindingProperty +
                    ", diagnostic=" + result.failure.diagnosticType,
            )
        }

    private fun javaFixture(
        sql: String,
        parameters: List<JavaMethodParameterMetadata>,
    ): JavaFixture {
        val fileId = SourceFileId("fixture/OracleMapper.java")
        val statementId = JavaStatementId(
            fileId,
            "fixture.OracleMapper",
            MethodSignature("query", parameters.map { it.typeIdentity }),
        )
        val snapshot = SourceSnapshot(fileId, SourceRevision("java-r1"), "authoritative-java-source")
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, snapshot.content.length)),
            listOf(snapshot),
            emptyList(),
        )
        return JavaFixture(
            PreparationSource.JavaAnnotation(
                JavaAnnotationStatementCapture(graph, listOf(sql), parameters),
            ),
        )
    }

    private fun xmlSource(
        body: String,
        mapperParameters: List<JavaMethodParameterMetadata>? = null,
    ): XmlMapperPreparationSource {
        val xmlFile = SourceFileId("vfs:/mapper.xml")
        val xmlRevision = SourceRevision("xml-r1")
        val statementId = XmlStatementId(xmlFile, "fixture.Mapper", "find")
        val content =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n" +
                "<mapper namespace=\"fixture.Mapper\">\n" +
                body +
                "\n</mapper>"
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, content.length)),
            listOf(SourceSnapshot(xmlFile, xmlRevision, content)),
            emptyList(),
        )
        val mapperMethod = mapperParameters?.let { parameters ->
            val javaSource = SourceSnapshot(
                SourceFileId("vfs:/Mapper.java"),
                SourceRevision("java-r1"),
                "x".repeat(256),
            )
            XmlMapperMethodCapture(
                statementId,
                javaSource,
                SourceRange(20, 120),
                parameters,
            )
        }
        return XmlMapperPreparationSource(graph, mapperMethod = mapperMethod)
    }

    private fun parameter(
        index: Int,
        sourceName: String,
        type: String,
        alias: String?,
    ) = JavaMethodParameterMetadata(index, sourceName, JavaTypeIdentity(type), alias)

    private fun method(name: String): Method =
        OracleParameterMapper::class.java.methods.single { it.name == name }

    private fun integer(value: Long): InputValue.IntegerValue =
        InputValue.IntegerValue(BigInteger.valueOf(value))

    private fun normalizeSql(sql: String): String =
        sql.trim().replace(Regex("\\s+"), " ")

    private fun normalizeRuntime(value: Any?): Any? = when {
        value == null -> null
        value is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .associate { it.key to normalizeRuntime(it.value) }
        value is Iterable<*> -> value.map(::normalizeRuntime)
        value.javaClass.isArray -> (0 until ReflectArray.getLength(value))
            .map { index -> normalizeRuntime(ReflectArray.get(value, index)) }
        else -> value
    }

    private data class JavaFixture(val source: PreparationSource.JavaAnnotation)
}
