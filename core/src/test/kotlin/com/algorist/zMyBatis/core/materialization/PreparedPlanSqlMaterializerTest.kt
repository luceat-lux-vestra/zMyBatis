package com.algorist.zMyBatis.core.materialization

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanResult
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanner
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedPlanSqlMaterializerTest {
    @Test
    fun materializesBigintVarcharAndTypedNullInExactBindingOrder() {
        val plan = plan(
            sql = "SELECT ?::BIGINT AS id, ?::VARCHAR AS label, ?::VARCHAR AS nullable_value",
            bindings = listOf(
                bigint(0, 41),
                varchar(1, "O'Reilly\\path"),
                typedNull(2, "VARCHAR"),
            ),
        )

        val artifact = ready(NativeExecutionPlanMaterializer.materialize(plan))

        assertEquals(
            "SELECT 41::BIGINT AS id, E'O''Reilly\\\\path'::VARCHAR AS label, CAST(NULL AS varchar)::VARCHAR AS nullable_value",
            artifact.executionSql,
        )
        assertEquals(plan.executionTargetId, artifact.executionTargetId)
        assertEquals(plan.databaseId, artifact.databaseId)
        assertEquals(plan.fingerprint, artifact.planFingerprint)
    }

    @Test
    fun skipsQuotedCommentAndPostgreSqlOperatorQuestionMarks() {
        val sql = "SELECT '?' AS literal, payload ? 'key' AS has_key, ?::BIGINT AS id /* ? */ -- ?\n"
        val artifact = ready(
            NativeExecutionPlanMaterializer.materialize(
                plan(sql = sql, bindings = listOf(bigint(0, 73))),
            ),
        )

        assertEquals(
            "SELECT '?' AS literal, payload ? 'key' AS has_key, 73::BIGINT AS id /* ? */ -- ?\n",
            artifact.executionSql,
        )
    }

    @Test
    fun zeroBindingPlanRemainsExactText() {
        val sql = "SELECT payload ? 'key' AS has_key, '?' AS literal"
        val artifact = ready(NativeExecutionPlanMaterializer.materialize(plan(sql = sql)))

        assertEquals(sql, artifact.executionSql)
    }

    @Test
    fun materializesRequiredCommonScalarEnvelopeInExactOrder() {
        val plan = plan(
            sql = """
                SELECT
                  ?::smallint,
                  ?::smallint,
                  ?::integer,
                  ?::bigint,
                  ?::real,
                  ?::double precision,
                  ?::numeric,
                  ?::boolean,
                  ?::varchar,
                  encode(?::bytea, 'hex'),
                  ?::date,
                  ?::time,
                  ?::timestamp,
                  CASE WHEN ?::varchar IS NULL THEN 'NULL' ELSE 'NON_NULL' END
            """.trimIndent(),
            bindings = listOf(
                binding(0, InputValue.IntegerValue(BigInteger.valueOf(7)), "java.lang.Byte", "TINYINT", "org.apache.ibatis.type.ByteTypeHandler"),
                binding(1, InputValue.IntegerValue(BigInteger.valueOf(8)), "java.lang.Short", "SMALLINT", "org.apache.ibatis.type.ShortTypeHandler"),
                binding(2, InputValue.IntegerValue(BigInteger.valueOf(9)), "java.lang.Integer", "INTEGER", "org.apache.ibatis.type.IntegerTypeHandler"),
                bigint(3, 42),
                binding(4, InputValue.DecimalValue(BigDecimal("11.25")), "java.lang.Float", "REAL", "org.apache.ibatis.type.FloatTypeHandler"),
                binding(5, InputValue.DecimalValue(BigDecimal("12.5")), "java.lang.Double", "DOUBLE", "org.apache.ibatis.type.DoubleTypeHandler"),
                binding(6, InputValue.DecimalValue(BigDecimal("13.75")), "java.math.BigDecimal", "DECIMAL", "org.apache.ibatis.type.BigDecimalTypeHandler"),
                binding(7, InputValue.BooleanValue(true), "java.lang.Boolean", "BOOLEAN", "org.apache.ibatis.type.BooleanTypeHandler"),
                varchar(8, "captured-proof"),
                binding(
                    9,
                    InputValue.ArrayValue(
                        listOf(
                            InputValue.IntegerValue(BigInteger.ONE),
                            InputValue.IntegerValue(BigInteger.valueOf(2)),
                            InputValue.IntegerValue(BigInteger.valueOf(3)),
                        ),
                    ),
                    "byte[]",
                    "VARBINARY",
                    "org.apache.ibatis.type.ByteArrayTypeHandler",
                ),
                binding(10, InputValue.DateValue(LocalDate.of(2026, 11, 18)), "java.time.LocalDate", "DATE", "org.apache.ibatis.type.LocalDateTypeHandler"),
                binding(11, InputValue.TimeValue(LocalTime.of(12, 34, 56)), "java.time.LocalTime", "TIME", "org.apache.ibatis.type.LocalTimeTypeHandler"),
                binding(
                    12,
                    InputValue.DateTimeValue(LocalDateTime.of(2026, 11, 18, 12, 34, 56)),
                    "java.time.LocalDateTime",
                    "TIMESTAMP",
                    "org.apache.ibatis.type.LocalDateTimeTypeHandler",
                ),
                typedNull(13, "VARCHAR"),
            ),
        )

        val artifact = ready(NativeExecutionPlanMaterializer.materialize(plan))

        assertEquals(
            """
                SELECT
                  7::smallint,
                  8::smallint,
                  9::integer,
                  42::bigint,
                  11.25::real,
                  12.5::double precision,
                  13.75::numeric,
                  TRUE::boolean,
                  E'captured-proof'::varchar,
                  encode(decode('010203', 'hex')::bytea, 'hex'),
                  DATE '2026-11-18'::date,
                  TIME '12:34:56'::time,
                  TIMESTAMP '2026-11-18 12:34:56'::timestamp,
                  CASE WHEN CAST(NULL AS varchar)::varchar IS NULL THEN 'NULL' ELSE 'NON_NULL' END
            """.trimIndent(),
            artifact.executionSql,
        )
    }

    @Test
    fun typedNullPreservesJdbcTypeAuthorityWithoutSqlContext() {
        val artifact = ready(
            NativeExecutionPlanMaterializer.materialize(
                plan(
                    sql = "SELECT pg_typeof(?)::text",
                    bindings = listOf(typedNull(0, "VARCHAR")),
                ),
            ),
        )

        assertEquals(
            "SELECT pg_typeof(CAST(NULL AS varchar))::text",
            artifact.executionSql,
        )
    }

    @Test
    fun stockObjectTypeHandlerNullUsesEffectiveJdbcTypeAuthority() {
        val artifact = ready(
            NativeExecutionPlanMaterializer.materialize(
                plan(
                    sql = "SELECT pg_typeof(?)::text",
                    bindings = listOf(
                        binding(
                            index = 0,
                            value = InputValue.NullValue,
                            javaType = "java.lang.Object",
                            jdbcType = "VARCHAR",
                            handler = "org.apache.ibatis.type.ObjectTypeHandler",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            "SELECT pg_typeof(CAST(NULL AS varchar))::text",
            artifact.executionSql,
        )
    }

    @Test
    fun rejectsUnsupportedInstantUntilTimestampTimezoneSemanticsAreProven() {
        val result = NativeExecutionPlanMaterializer.materialize(
            plan(
                sql = "SELECT ?::timestamp",
                bindings = listOf(
                    binding(
                        0,
                        InputValue.InstantValue(java.time.Instant.parse("2026-11-18T03:00:17Z")),
                        "java.time.Instant",
                        "TIMESTAMP",
                        "org.apache.ibatis.type.InstantTypeHandler",
                    ),
                ),
            ),
        )

        assertFailure(
            result,
            PreparedPlanSqlMaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
            "prepared-plan-sql-binding-metadata-unsupported",
        )
    }

    @Test
    fun rejectsInvalidUtf16AndBinaryValuesOutsideSignedByteRange() {
        val invalidText = NativeExecutionPlanMaterializer.materialize(
            plan(
                sql = "SELECT ?::varchar",
                bindings = listOf(varchar(0, "\uD800")),
            ),
        )
        assertFailure(
            invalidText,
            PreparedPlanSqlMaterializationFailureKind.BINDING_VALUE_UNSUPPORTED,
            "prepared-plan-sql-binding-value-unsupported",
        )

        val invalidBinary = NativeExecutionPlanMaterializer.materialize(
            plan(
                sql = "SELECT ?::bytea",
                bindings = listOf(
                    binding(
                        0,
                        InputValue.ArrayValue(
                            listOf(InputValue.IntegerValue(BigInteger.valueOf(128))),
                        ),
                        "byte[]",
                        "VARBINARY",
                        "org.apache.ibatis.type.ByteArrayTypeHandler",
                    ),
                ),
            ),
        )
        assertFailure(
            invalidBinary,
            PreparedPlanSqlMaterializationFailureKind.BINDING_VALUE_UNSUPPORTED,
            "prepared-plan-sql-binding-value-unsupported",
        )
    }

    @Test
    fun markerCountMustMatchOrderedBindingCount() {
        val result = NativeExecutionPlanMaterializer.materialize(
            plan(
                sql = "SELECT ?::BIGINT",
                bindings = listOf(bigint(0, 1), varchar(1, "extra")),
            ),
        )

        assertFailure(
            result,
            PreparedPlanSqlMaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNSUPPORTED,
            "prepared-plan-sql-placeholder-topology-unsupported",
        )
    }

    @Test
    fun unsupportedJdbcShapeFailsClosedWithoutRenderingInput() {
        val secret = "do-not-render"
        val result = NativeExecutionPlanMaterializer.materialize(
            plan(
                sql = "SELECT ?",
                bindings = listOf(
                    binding(
                        index = 0,
                        value = InputValue.Text(secret),
                        javaType = "java.lang.String",
                        jdbcType = "CLOB",
                        handler = "org.apache.ibatis.type.ClobTypeHandler",
                    ),
                ),
            ),
        )

        val failure = assertFailure(
            result,
            PreparedPlanSqlMaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
            "prepared-plan-sql-binding-metadata-unsupported",
        )
        assertTrue(secret !in failure.toString())
    }

    @Test
    fun bigintOutsideSignedLongRangeFailsClosed() {
        val result = NativeExecutionPlanMaterializer.materialize(
            plan(
                sql = "SELECT ?::BIGINT",
                bindings = listOf(
                    binding(
                        index = 0,
                        value = InputValue.IntegerValue(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                        javaType = "java.lang.Long",
                        jdbcType = "BIGINT",
                        handler = "org.apache.ibatis.type.LongTypeHandler",
                    ),
                ),
            ),
        )

        assertFailure(
            result,
            PreparedPlanSqlMaterializationFailureKind.BINDING_VALUE_UNSUPPORTED,
            "prepared-plan-sql-binding-value-unsupported",
        )
    }

    private fun plan(
        sql: String,
        bindings: List<PreparedBinding> = emptyList(),
        databaseId: String = "PostgreSQL",
    ): PreparedExecutionPlan {
        val file = SourceFileId("vfs:/Mapper.xml")
        val prepared = PreparedExecution(
            statementId = XmlStatementId(file, "fixture.Mapper", "find"),
            statementKind = StatementKind.SELECT,
            sourceRevisions = mapOf(file to SourceRevision("r1")),
            sqlWithPlaceholders = sql,
            orderedBindings = bindings,
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                engineIdentity = "org.mybatis:mybatis",
                engineVersion = "3.6.0",
                languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
                databaseId = databaseId,
            ),
        )
        val target = ResolvedExecutionTarget(
            ExecutionTargetDescriptor(
                ExecutionTargetId(
                    StableDataSourceId("fixture-ds"),
                    ExplicitSchemaIdentity("public"),
                ),
                "fixture",
            ),
            MyBatisDatabaseId(databaseId),
        )
        return when (val result = PreparedExecutionPlanner.freeze(prepared, target)) {
            is PreparedExecutionPlanResult.Ready -> result.plan
            is PreparedExecutionPlanResult.Failed -> error("fixture plan failed: ${result.failure.code}")
        }
    }

    private fun bigint(index: Int, value: Long): PreparedBinding =
        binding(
            index = index,
            value = InputValue.IntegerValue(BigInteger.valueOf(value)),
            javaType = "java.lang.Long",
            jdbcType = "BIGINT",
            handler = "org.apache.ibatis.type.LongTypeHandler",
        )

    private fun varchar(index: Int, value: String): PreparedBinding =
        binding(
            index = index,
            value = InputValue.Text(value),
            javaType = "java.lang.String",
            jdbcType = "VARCHAR",
            handler = "org.apache.ibatis.type.StringTypeHandler",
        )

    private fun typedNull(index: Int, jdbcType: String): PreparedBinding =
        binding(
            index = index,
            value = InputValue.NullValue,
            javaType = "java.lang.Object",
            jdbcType = jdbcType,
            handler = "org.apache.ibatis.type.UnknownTypeHandler",
        )

    private fun binding(
        index: Int,
        value: InputValue,
        javaType: String,
        jdbcType: String,
        handler: String,
    ): PreparedBinding {
        val file = SourceFileId("vfs:/Mapper.xml")
        val revision = SourceRevision("r1")
        return PreparedBinding(
            index = index,
            property = "p$index",
            value = value,
            origin = PreparedBindingOrigin.CallerInput(
                InputRequirementId("p$index"),
                InputProvenance(
                    listOf(
                        InputEvidence.Placeholder(
                            kind = InputKind.BOUND,
                            expression = "p$index",
                            source = SourceEvidence(file, revision, null),
                        ),
                    ),
                ),
            ),
            metadata = PreparedBindingMetadata(
                declaredJavaTypeIdentity = JavaTypeIdentity(javaType),
                mappingJavaTypeIdentity = javaType,
                jdbcTypeIdentity = jdbcType,
                effectiveJdbcTypeIdentity = jdbcType,
                typeHandlerIdentity = handler,
                parameterMode = "IN",
                numericScale = null,
            ),
        )
    }

    private fun ready(result: PreparedPlanSqlMaterializationResult): PreparedPlanSqlArtifact =
        when (result) {
            is PreparedPlanSqlMaterializationResult.Ready -> result.artifact
            is PreparedPlanSqlMaterializationResult.Failed ->
                error("expected ready, got ${result.failure.kind} / ${result.failure.code}")
        }

    private fun assertFailure(
        result: PreparedPlanSqlMaterializationResult,
        kind: PreparedPlanSqlMaterializationFailureKind,
        code: String,
    ): PreparedPlanSqlMaterializationFailure {
        val failure = (result as PreparedPlanSqlMaterializationResult.Failed).failure
        assertEquals(kind, failure.kind)
        assertEquals(code, failure.code)
        return failure
    }
}
