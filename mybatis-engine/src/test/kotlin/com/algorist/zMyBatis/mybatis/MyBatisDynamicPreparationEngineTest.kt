package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputNullability
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
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
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisDynamicPreparationEngineTest {
    @Test
    fun ifBranchSelectionIsDelegatedToMyBatis() {
        val script = """
            <script>
              select * from users
              <where>
                <if test="id != null">id = #{id}</if>
              </where>
            </script>
        """.trimIndent()

        val present = prepare(
            fixture(
                script = script,
                parameters = listOf(Parameter("java.lang.Long", "id", longType(nullable = true), longValue(7))),
            ),
        )
        assertTrue(present is PreparationResult.Success)
        present as PreparationResult.Success
        assertEquals("select * from users WHERE id = ?", normalizeSql(present.execution.sqlWithPlaceholders))
        assertEquals(listOf("id"), present.execution.orderedBindings.map { it.property })
        assertEquals(longValue(7), present.execution.orderedBindings.single().value)

        val absent = prepare(
            fixture(
                script = script,
                parameters = listOf(Parameter("java.lang.Long", "id", longType(nullable = true), InputValue.NullValue)),
            ),
        )
        assertTrue(absent is PreparationResult.Success)
        absent as PreparationResult.Success
        assertEquals("select * from users", normalizeSql(absent.execution.sqlWithPlaceholders))
        assertTrue(absent.execution.orderedBindings.isEmpty())
    }

    @Test
    fun chooseAndTrimSemanticsComeFromStockMyBatis() {
        val script = """
            <script>
              select * from users
              <trim prefix="WHERE" prefixOverrides="AND |OR ">
                <choose>
                  <when test="id != null">AND id = #{id}</when>
                  <otherwise>AND 1 = 0</otherwise>
                </choose>
              </trim>
            </script>
        """.trimIndent()

        val result = prepare(
            fixture(
                script = script,
                parameters = listOf(Parameter("java.lang.Long", "id", longType(nullable = true), longValue(9))),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select * from users WHERE id = ?", normalizeSql(result.execution.sqlWithPlaceholders))
        assertEquals(listOf("id"), result.execution.orderedBindings.map { it.property })
    }

    @Test
    fun bindAdditionalParameterCarriesAuthoritativeInternalProvenance() {
        val bindExpression = "'%' + name + '%'"
        val script = """
            <script>
              <bind name="pattern" value="$bindExpression"/>
              select * from users where name like #{pattern}
            </script>
        """.trimIndent()
        val fixture = fixture(
            script = script,
            parameters = listOf(Parameter("java.lang.String", "name", stringType(), InputValue.Text("Ada"))),
            internalBindings = listOf(InternalSpec("pattern", InternalBindingKind.BIND, bindExpression)),
        )

        val result = prepare(fixture)

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        val binding = result.execution.orderedBindings.single()
        assertEquals("pattern", binding.property)
        assertEquals(InputValue.Text("%Ada%"), binding.value)
        assertTrue(binding.additionalParameter)
        assertTrue(binding.requirementId == null)
        assertTrue(binding.origin is PreparedBindingOrigin.MyBatisAdditional)
        val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
        assertEquals("pattern", origin.internalBinding.name)
        assertEquals(InternalBindingKind.BIND, origin.internalBinding.kind)
        assertEquals(fixture.contract.internalBindings.single().provenance, origin.internalBinding.provenance)
    }

    @Test
    fun additionalParameterWithoutContractProvenanceFailsClosed() {
        val script = """
            <script>
              <bind name="pattern" value="'%' + name + '%'"/>
              select #{pattern}
            </script>
        """.trimIndent()
        val result = prepare(
            fixture(
                script = script,
                parameters = listOf(Parameter("java.lang.String", "name", stringType(), InputValue.Text("Ada"))),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.BINDING_RESOLUTION,
            "mybatis-additional-parameter-provenance-missing",
            "pattern",
        )
    }

    @Test
    fun ambiguousInternalAuthorityCannotProducePreparedBinding() {
        val script = """
            <script>
              <bind name="pattern" value="'%' + name + '%'"/>
              select #{pattern}
            </script>
        """.trimIndent()
        val result = prepare(
            fixture(
                script = script,
                parameters = listOf(Parameter("java.lang.String", "name", stringType(), InputValue.Text("Ada"))),
                internalBindings = listOf(
                    InternalSpec("pattern", InternalBindingKind.BIND, "'%' + name + '%'"),
                    InternalSpec("pattern", InternalBindingKind.ADDITIONAL_PARAMETER, "pattern"),
                ),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.BINDING_RESOLUTION,
            "mybatis-additional-parameter-provenance-ambiguous",
            "pattern",
        )
    }

    @Test
    fun foreachGeneratedMappingNamesAreNotReverseEngineered() {
        val script = """
            <script>
              select * from users where id in
              <foreach collection="ids" item="item" open="(" separator="," close=")">
                #{item}
              </foreach>
            </script>
        """.trimIndent()
        val result = prepare(
            fixture(
                script = script,
                parameters = listOf(
                    Parameter(
                        "java.util.List<java.lang.Long>",
                        "ids",
                        listType(),
                        InputValue.ListValue(listOf(longValue(1), longValue(2))),
                    ),
                ),
                internalBindings = listOf(InternalSpec("item", InternalBindingKind.FOREACH_ITEM, "item")),
            ),
        )

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.BINDING_RESOLUTION, result.failure.kind)
        assertEquals("mybatis-additional-parameter-provenance-missing", result.failure.code)
        assertTrue(result.failure.bindingProperty != null)
        assertTrue(result.failure.bindingProperty != "item")
    }

    @Test
    fun dynamicRawInterpolationIsRejectedBeforeRenderedSqlCanBecomeAuthority() {
        val script = "<script>select * from ${'$'}{table}</script>"
        val result = prepare(
            fixture(
                script = script,
                parameters = listOf(
                    Parameter(
                        "java.lang.String",
                        "table",
                        rawType(),
                        InputValue.RawText("users"),
                        kind = InputKind.RAW_INTERPOLATION,
                    ),
                ),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "java-annotation-dynamic-raw-interpolation-unsupported",
            null,
        )
    }

    @Test
    fun malformedDynamicScriptReturnsTypedParseFailure() {
        val result = prepare(
            fixture(
                script = "<script><if test=\"id != null\">select #{id}</script>",
                parameters = listOf(Parameter("java.lang.Long", "id", longType(nullable = true), longValue(3))),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.MYBATIS_PARSE,
            "mybatis-sql-source-parse-failure",
            null,
        )
    }

    @Test
    fun concurrentBindPreparationsCannotCrossContaminateAdditionalValues() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 24).map { index ->
                executor.submit<InputValue> {
                    val expression = "'%' + name + '%'"
                    val result = prepare(
                        fixture(
                            script = "<script><bind name=\"pattern\" value=\"$expression\"/>select #{pattern}</script>",
                            parameters = listOf(
                                Parameter("java.lang.String", "name", stringType(), InputValue.Text("name_$index")),
                            ),
                            internalBindings = listOf(InternalSpec("pattern", InternalBindingKind.BIND, expression)),
                        ),
                    )
                    check(result is PreparationResult.Success) { "dynamic preparation failed for $index: $result" }
                    val binding = result.execution.orderedBindings.single()
                    check(binding.additionalParameter)
                    binding.value
                }
            }

            assertEquals(
                (0 until 24).mapTo(linkedSetOf()) { InputValue.Text("%name_$it%") },
                futures.map { it.get(10, TimeUnit.SECONDS) }.toSet(),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun prepare(fixture: Fixture): PreparationResult {
        val requestResult = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(fixture.capture),
            fixture.contract,
            fixture.environment,
        )
        check(requestResult is PreparationRequestResult.Ready) { "fixture request was not ready: $requestResult" }
        return MyBatisPreparationEngine.prepare(requestResult.request)
    }

    private fun fixture(
        script: String,
        parameters: List<Parameter>,
        internalBindings: List<InternalSpec> = emptyList(),
    ): Fixture {
        val fileId = SourceFileId("fixture/DynamicMapper.java")
        val revision = SourceRevision("revision-dynamic-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-dynamic-java-source")
        val types = parameters.map { JavaTypeIdentity(it.javaType) }
        val statementId = JavaStatementId(
            fileId,
            "fixture.DynamicMapper",
            MethodSignature("query", types),
        )
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, snapshot.content.length)),
            listOf(snapshot),
            emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(
            graph,
            listOf(script),
            parameters.mapIndexed { index, parameter ->
                JavaMethodParameterMetadata(index, parameter.alias, types[index], parameter.alias)
            },
        )
        val source = SourceEvidence(fileId, revision, SourceRange(0, snapshot.content.length))
        val requirements = parameters.mapIndexed { index, parameter ->
            val id = InputRequirementId("dynamic-param:$index")
            InputRequirement(
                id = id,
                kind = parameter.kind,
                expectedType = parameter.expectedType,
                requiredness = InputRequiredness.REQUIRED,
                provenance = InputProvenance(
                    listOf(
                        InputEvidence.MapperMethodParameter(index, parameter.alias, types[index], source),
                        InputEvidence.ExplicitParamAlias(index, parameter.alias, source),
                        if (parameter.kind == InputKind.RAW_INTERPOLATION) {
                            InputEvidence.Placeholder(InputKind.RAW_INTERPOLATION, parameter.alias, source)
                        } else {
                            InputEvidence.OgnlExpression(parameter.alias, source)
                        },
                    ),
                ),
            )
        }
        val aliases = parameters.mapIndexed { index, parameter ->
            InputAlias(
                name = parameter.alias,
                requirementId = requirements[index].id,
                kind = InputAliasKind.EXPLICIT_PARAM,
                provenance = requirements[index].provenance,
            )
        }
        val internal = internalBindings.map { spec ->
            InternalBinding(
                name = spec.name,
                kind = spec.kind,
                provenance = InputProvenance(
                    listOf(
                        when (spec.kind) {
                            InternalBindingKind.BIND -> InputEvidence.BindLocal(spec.name, spec.expression, source)
                            InternalBindingKind.FOREACH_ITEM ->
                                InputEvidence.ForeachLocal(spec.name, com.algorist.zMyBatis.core.input.ForeachLocalRole.ITEM, source)
                            InternalBindingKind.FOREACH_INDEX ->
                                InputEvidence.ForeachLocal(spec.name, com.algorist.zMyBatis.core.input.ForeachLocalRole.INDEX, source)
                            InternalBindingKind.ADDITIONAL_PARAMETER,
                            InternalBindingKind.MYBATIS_CONTEXT,
                            -> InputEvidence.OgnlExpression(spec.expression, source)
                        },
                    ),
                ),
            )
        }
        val contract = ParameterContract(
            statementId = statementId,
            requirements = requirements,
            aliases = aliases,
            internalBindings = internal,
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environmentResult = InputEnvironment.validate(
            contract,
            requirements.mapIndexed { index, requirement ->
                ProvidedInput(requirement.id, parameters[index].value, ExecutionInputOrigin.USER_ENTERED)
            },
        )
        check(environmentResult is InputEnvironmentResult.Success) { "fixture environment invalid: $environmentResult" }
        return Fixture(capture, contract, environmentResult.environment)
    }

    private fun assertFailure(
        result: PreparationResult,
        kind: PreparationFailureKind,
        code: String,
        property: String?,
    ) {
        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(kind, result.failure.kind)
        assertEquals(code, result.failure.code)
        assertEquals(property, result.failure.bindingProperty)
    }

    private fun normalizeSql(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private fun longValue(value: Long) = InputValue.IntegerValue(BigInteger.valueOf(value))

    private fun longType(nullable: Boolean) = ExpectedInputType(
        shape = InputShape.SCALAR,
        scalarType = InputScalarType.INTEGER,
        javaTypeIdentity = JavaTypeIdentity("java.lang.Long"),
        nullability = if (nullable) InputNullability.NULLABLE else InputNullability.NON_NULL,
    )

    private fun stringType() = ExpectedInputType(
        shape = InputShape.SCALAR,
        scalarType = InputScalarType.STRING,
        javaTypeIdentity = JavaTypeIdentity("java.lang.String"),
        nullability = InputNullability.NON_NULL,
    )

    private fun rawType() = ExpectedInputType(
        shape = InputShape.RAW_TEXT,
        scalarType = InputScalarType.STRING,
        javaTypeIdentity = JavaTypeIdentity("java.lang.String"),
        nullability = InputNullability.NON_NULL,
    )

    private fun listType() = ExpectedInputType(
        shape = InputShape.LIST,
        javaTypeIdentity = JavaTypeIdentity("java.util.List<java.lang.Long>"),
        nullability = InputNullability.NON_NULL,
    )

    private data class Fixture(
        val capture: JavaAnnotationStatementCapture,
        val contract: ParameterContract,
        val environment: InputEnvironment,
    )

    private data class Parameter(
        val javaType: String,
        val alias: String,
        val expectedType: ExpectedInputType,
        val value: InputValue,
        val kind: InputKind = InputKind.BOUND,
    )

    private data class InternalSpec(
        val name: String,
        val kind: InternalBindingKind,
        val expression: String,
    )
}
