package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
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
            fixture(script, listOf(Parameter("java.lang.Long", "id", integer(7)))),
        )
        assertTrue(present is PreparationResult.Success)
        present as PreparationResult.Success
        assertEquals("select * from users WHERE id = ?", normalizeSql(present.execution.sqlWithPlaceholders))
        assertEquals(integer(7), present.execution.orderedBindings.single().value)

        val absent = prepare(
            fixture(script, listOf(Parameter("java.lang.Long", "id", InputValue.NullValue))),
        )
        assertTrue(absent is PreparationResult.Success)
        absent as PreparationResult.Success
        assertEquals("select * from users", normalizeSql(absent.execution.sqlWithPlaceholders))
        assertTrue(absent.execution.orderedBindings.isEmpty())
    }

    @Test
    fun chooseAndTrimSemanticsComeFromStockMyBatis() {
        val result = prepare(
            fixture(
                """
                    <script>
                      select * from users
                      <trim prefix="WHERE" prefixOverrides="AND |OR ">
                        <choose>
                          <when test="id != null">AND id = #{id}</when>
                          <otherwise>AND 1 = 0</otherwise>
                        </choose>
                      </trim>
                    </script>
                """.trimIndent(),
                listOf(Parameter("java.lang.Long", "id", integer(9))),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select * from users WHERE id = ?", normalizeSql(result.execution.sqlWithPlaceholders))
    }

    @Test
    fun bindAdditionalParameterIsCapturedAsGeneratedMyBatisBinding() {
        val result = prepare(
            fixture(
                """
                    <script>
                      <bind name="pattern" value="'%' + name + '%'"/>
                      select * from users where name like #{pattern}
                    </script>
                """.trimIndent(),
                listOf(Parameter("java.lang.String", "name", InputValue.Text("Ada"))),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        val binding = result.execution.orderedBindings.single()
        assertEquals("pattern", binding.property)
        assertEquals(InputValue.Text("%Ada%"), binding.value)
        assertTrue(binding.additionalParameter)
        assertTrue(binding.requirementId == null)
        val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
        assertEquals(InternalBindingKind.ADDITIONAL_PARAMETER, origin.internalBinding.kind)
    }

    @Test
    fun dynamicRawInterpolationUsesCallerValueAndRetainsRiskProvenance() {
        val fixture = fixture(
            "<script>select * from " + "$" + "{table}</script>",
            listOf(Parameter("java.lang.String", "table", InputValue.Text("users"))),
        )
        val result = prepare(fixture)

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select * from users", normalizeSql(result.execution.sqlWithPlaceholders))
        assertTrue(result.execution.orderedBindings.isEmpty())
        assertEquals(1, result.execution.rawInterpolations.size)
        assertEquals(
            fixture.contract.requirements.single().id,
            result.execution.rawInterpolations.single().requirementId,
        )
    }

    @Test
    fun malformedDynamicScriptReturnsTypedParseFailure() {
        val result = prepare(
            fixture(
                "<script><if test=\"id != null\">select #{id}</script>",
                listOf(Parameter("java.lang.Long", "id", integer(3))),
            ),
        )

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.MYBATIS_PARSE, result.failure.kind)
        assertEquals("mybatis-dynamic-script-malformed", result.failure.code)
    }

    @Test
    fun concurrentBindPreparationsCannotCrossContaminateAdditionalValues() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 24).map { index ->
                executor.submit<InputValue> {
                    val result = prepare(
                        fixture(
                            "<script><bind name=\"pattern\" value=\"'%' + name + '%'\"/>select #{pattern}</script>",
                            listOf(Parameter("java.lang.String", "name", InputValue.Text("name_$index"))),
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
        val request = MyBatisPreparationRequest.create(
            fixture.source,
            fixture.contract,
            fixture.environment,
        )
        check(request is PreparationRequestResult.Ready)
        return MyBatisPreparationEngine.prepare(request.request)
    }

    private fun fixture(
        script: String,
        parameters: List<Parameter>,
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
        val capture = JavaAnnotationStatementCapture(
            StatementSourceGraph(
                CapturedStatement(
                    statementId,
                    StatementKind.SELECT,
                    SourceRange(0, snapshot.content.length),
                ),
                listOf(snapshot),
                emptyList(),
            ),
            listOf(script),
            parameters.mapIndexed { index, parameter ->
                JavaMethodParameterMetadata(index, parameter.alias, types[index], parameter.alias)
            },
        )
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked)
        val environment = InputEnvironment.validate(
            contract,
            contract.requirements.map { requirement ->
                val index = requirement.provenance.evidence
                    .filterIsInstance<com.algorist.zMyBatis.core.input.InputEvidence.MapperMethodParameter>()
                    .single()
                    .index
                ProvidedInput(
                    requirement.id,
                    parameters[index].value,
                    ExecutionInputOrigin.USER_ENTERED,
                )
            },
        )
        check(environment is InputEnvironmentResult.Success)
        return Fixture(source, contract, environment.environment)
    }

    private fun integer(value: Long) = InputValue.IntegerValue(BigInteger.valueOf(value))

    private fun normalizeSql(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private data class Fixture(
        val source: PreparationSource.JavaAnnotation,
        val contract: ParameterContract,
        val environment: InputEnvironment,
    )

    private data class Parameter(
        val javaType: String,
        val alias: String,
        val value: InputValue,
    )
}
