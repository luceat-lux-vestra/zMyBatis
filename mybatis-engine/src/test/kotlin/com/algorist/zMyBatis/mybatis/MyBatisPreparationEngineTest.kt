package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.JavaAnnotationParameterContractFactory
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
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
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisPreparationEngineTest {
    @Test
    fun staticBoundSqlPreservesMyBatisMappingOrderAndValues() {
        val request = request(
            "select * from users where id = #{id} and name = #{name}",
            listOf(
                Parameter("java.lang.Long", "id", InputValue.IntegerValue(BigInteger.valueOf(7))),
                Parameter("java.lang.String", "name", InputValue.Text("Ada")),
            ),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Success)
        val execution = (result as PreparationResult.Success).execution
        assertEquals("select * from users where id = ? and name = ?", execution.sqlWithPlaceholders)
        assertEquals(listOf("id", "name"), execution.orderedBindings.map { it.property })
        assertEquals(
            listOf(InputValue.IntegerValue(BigInteger.valueOf(7)), InputValue.Text("Ada")),
            execution.orderedBindings.map { it.value },
        )
        assertEquals(listOf(0, 1), execution.orderedBindings.map { it.index })
        assertTrue(execution.orderedBindings.all { !it.additionalParameter })
        assertEquals("org.mybatis:mybatis", execution.preparationMetadata.engineIdentity)
        assertEquals("3.5.19", execution.preparationMetadata.engineVersion)
    }

    @Test
    fun rawInterpolationIsDelegatedToMyBatisButRemainsSeparateFromBindings() {
        val request = request(
            "select * from ${'$'}{table} where id = #{id}",
            listOf(
                Parameter("java.lang.String", "table", InputValue.RawText("users")),
                Parameter("java.lang.Long", "id", InputValue.IntegerValue(BigInteger.valueOf(9))),
            ),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Success)
        val execution = (result as PreparationResult.Success).execution
        assertEquals("select * from users where id = ?", execution.sqlWithPlaceholders)
        assertEquals(listOf("id"), execution.orderedBindings.map { it.property })
        assertEquals(listOf("java-param:0"), execution.rawInterpolations.map { it.requirementId.value })
        assertEquals("java-param:1", execution.orderedBindings.single().requirementId?.value)
        assertFalse(
            execution.rawInterpolations.any { raw ->
                execution.orderedBindings.any { bound -> bound.requirementId == raw.requirementId }
            },
        )
    }

    @Test
    fun scalarAndTemporalBindingValuesRetainCoreTypeFidelity() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val date = LocalDate.of(2026, 9, 15)
        val time = LocalTime.of(7, 42, 3)
        val dateTime = LocalDateTime.of(2026, 9, 15, 7, 42, 3)
        val instant = Instant.parse("2026-09-14T22:42:03Z")
        val values = listOf(
            Parameter("java.lang.String", "text", InputValue.Text("value")),
            Parameter("java.math.BigInteger", "integer", InputValue.IntegerValue(BigInteger("9223372036854775808123"))),
            Parameter("java.math.BigDecimal", "decimal", InputValue.DecimalValue(BigDecimal("1234567890.12345678901234567890"))),
            Parameter("java.lang.Boolean", "flag", InputValue.BooleanValue(true)),
            Parameter("java.util.UUID", "uuid", InputValue.UuidValue(uuid)),
            Parameter("java.time.LocalDate", "date", InputValue.DateValue(date)),
            Parameter("java.time.LocalTime", "time", InputValue.TimeValue(time)),
            Parameter("java.time.LocalDateTime", "dateTime", InputValue.DateTimeValue(dateTime)),
            Parameter("java.time.Instant", "instant", InputValue.InstantValue(instant)),
        )
        val sql = "select " + values.joinToString(", ") { "#{${it.alias}}" }

        val result = MyBatisPreparationEngine.prepare(request(sql, values))

        assertTrue(result is PreparationResult.Success)
        val execution = (result as PreparationResult.Success).execution
        assertEquals(values.map { it.value }, execution.orderedBindings.map { it.value })
        assertEquals(values.map { it.alias }, execution.orderedBindings.map { it.property })
    }

    @Test
    fun structuredMapListAndArrayValuesAreNotStringified() {
        val map = InputValue.MapValue(
            linkedMapOf(
                "id" to InputValue.IntegerValue(BigInteger.ONE),
                "name" to InputValue.Text("map"),
            ),
        )
        val list = InputValue.ListValue(listOf(InputValue.Text("a"), InputValue.Text("b")))
        val array = InputValue.ArrayValue(listOf(InputValue.Text("x"), InputValue.Text("y")))
        val parameters = listOf(
            Parameter("java.util.Map<java.lang.String, java.lang.Object>", "mapValue", map),
            Parameter("java.util.List<java.lang.String>", "listValue", list),
            Parameter("java.lang.String[]", "arrayValue", array),
        )
        val sql = "select #{mapValue}, #{listValue}, #{arrayValue}"

        val result = MyBatisPreparationEngine.prepare(request(sql, parameters))

        assertTrue(result is PreparationResult.Success)
        val execution = (result as PreparationResult.Success).execution
        assertEquals(listOf(map, list, array), execution.orderedBindings.map { it.value })
    }

    @Test
    fun oneExplicitAliasDoesNotRequireGeneratedMyBatisAliases() {
        val request = request(
            "select #{id}",
            listOf(Parameter("java.lang.Long", "id", InputValue.IntegerValue(BigInteger.valueOf(11)))),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Success)
        val binding = (result as PreparationResult.Success).execution.orderedBindings.single()
        assertEquals("id", binding.property)
        assertEquals("java-param:0", binding.requirementId?.value)
    }

    private fun request(sql: String, parameters: List<Parameter>): MyBatisPreparationRequest {
        val capture = capture(sql, parameters)
        val contract = JavaAnnotationParameterContractFactory.build(capture)
        assertFalse("test fixture unexpectedly produced a blocked contract", contract.isPreparationBlocked)
        val provided = contract.requirements.map { requirement ->
            val index = requirement.id.value.substringAfter("java-param:").toInt()
            ProvidedInput(requirement.id, parameters[index].value, ExecutionInputOrigin.USER_ENTERED)
        }
        val environmentResult = InputEnvironment.validate(contract, provided)
        assertTrue(environmentResult is InputEnvironmentResult.Success)
        val environment = (environmentResult as InputEnvironmentResult.Success).environment
        val requestResult = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environment,
        )
        assertTrue(requestResult is PreparationRequestResult.Ready)
        return (requestResult as PreparationRequestResult.Ready).request
    }

    private fun capture(sql: String, parameters: List<Parameter>): JavaAnnotationStatementCapture {
        val fileId = SourceFileId("fixture/Mapper.java")
        val typeIdentities = parameters.map { JavaTypeIdentity(it.type) }
        val statementId = JavaStatementId(
            fileId,
            "fixture.Mapper",
            MethodSignature("query", typeIdentities),
        )
        val snapshot = SourceSnapshot(fileId, SourceRevision("revision-1"), "authoritative-java-source")
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, snapshot.content.length)),
            listOf(snapshot),
            emptyList(),
        )
        return JavaAnnotationStatementCapture(
            graph,
            listOf(sql),
            parameters.mapIndexed { index, parameter ->
                JavaMethodParameterMetadata(
                    index,
                    parameter.alias,
                    typeIdentities[index],
                    parameter.alias,
                )
            },
        )
    }

    private data class Parameter(
        val type: String,
        val alias: String,
        val value: InputValue,
    )
}
