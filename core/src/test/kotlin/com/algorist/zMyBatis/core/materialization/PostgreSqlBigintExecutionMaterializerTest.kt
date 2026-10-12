package com.algorist.zMyBatis.core.materialization

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
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostgreSqlBigintExecutionMaterializerTest {
    @Test
    fun oneLongBindingProducesTargetBoundExactSql() {
        val target = target("ds-a", "public")
        val result = PostgreSqlBigintExecutionMaterializer(target).materialize(
            prepared("SELECT ? AS RESULT_VALUE", BigInteger.valueOf(7)),
        )
        val execution = success(result)

        assertEquals("SELECT 7 AS RESULT_VALUE", execution.executionSql)
        assertEquals(target.targetId, execution.executionTargetId)
        assertEquals(ConventionalMyBatisDatabaseIds.POSTGRESQL, execution.executionDialectId)
    }

    @Test
    fun missingOrNonPostgresqlDialectFailsClosed() {
        val prepared = prepared("SELECT ?", BigInteger.ONE)
        val missing = target(databaseId = null)
        val oracle = target(databaseId = "Oracle")

        assertFailure(
            PostgreSqlBigintExecutionMaterializer(missing).materialize(prepared),
            MaterializationFailureKind.DIALECT_UNSUPPORTED,
        )
        assertFailure(
            PostgreSqlBigintExecutionMaterializer(oracle).materialize(prepared),
            MaterializationFailureKind.DIALECT_UNSUPPORTED,
        )
    }

    @Test
    fun targetIdentityParticipatesInArtifactFingerprint() {
        val prepared = prepared("SELECT ? AS RESULT_VALUE", BigInteger.valueOf(7))
        val first = success(PostgreSqlBigintExecutionMaterializer(target("ds-a", "public")).materialize(prepared))
        val second = success(PostgreSqlBigintExecutionMaterializer(target("ds-b", "public")).materialize(prepared))

        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun signedBigintBoundsAreAccepted() {
        val target = target("ds", "public")
        val min = success(PostgreSqlBigintExecutionMaterializer(target).materialize(
            prepared("SELECT ?", BigInteger.valueOf(Long.MIN_VALUE)),
        ))
        val max = success(PostgreSqlBigintExecutionMaterializer(target).materialize(
            prepared("SELECT ?", BigInteger.valueOf(Long.MAX_VALUE)),
        ))

        assertEquals("SELECT " + Long.MIN_VALUE, min.executionSql)
        assertEquals("SELECT " + Long.MAX_VALUE, max.executionSql)
    }

    @Test
    fun outOfRangeIntegerFailsClosed() {
        val result = PostgreSqlBigintExecutionMaterializer(target()).materialize(
            prepared("SELECT ?", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
        )

        assertFailure(result, MaterializationFailureKind.BINDING_VALUE_UNSUPPORTED)
    }

    @Test
    fun anythingExceptStockLongBigintMappingFailsClosed() {
        val baseline = binding(BigInteger.valueOf(7))
        val variants = listOf(
            baseline.copy(value = InputValue.Text("7")),
            baseline.copy(metadata = baseline.metadata.copy(jdbcTypeIdentity = "INTEGER")),
            baseline.copy(metadata = baseline.metadata.copy(mappingJavaTypeIdentity = "java.lang.Integer")),
            baseline.copy(metadata = baseline.metadata.copy(typeHandlerIdentity = "fixture.CustomTypeHandler")),
            baseline.copy(metadata = baseline.metadata.copy(parameterMode = "OUT")),
            baseline.copy(
                metadata = baseline.metadata.copy(
                    mappingJavaTypeIdentity = "java.lang.Object",
                    typeHandlerIdentity = "org.apache.ibatis.type.LongTypeHandler",
                ),
            ),
            baseline.copy(
                metadata = baseline.metadata.copy(
                    mappingJavaTypeIdentity = "java.lang.Long",
                    typeHandlerIdentity = "org.apache.ibatis.type.UnknownTypeHandler",
                ),
            ),
        )

        variants.forEach { unsupported ->
            val result = PostgreSqlBigintExecutionMaterializer(target()).materialize(
                prepared("SELECT ?", bindings = listOf(unsupported)),
            )
            assertFailure(result, MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED)
        }
    }

    @Test
    fun stockObjectUnknownTypeHandlerPathIsAcceptedForDeclaredLongBigint() {
        val baseline = binding(BigInteger.valueOf(7))
        val stockUnknown = baseline.copy(
            metadata = baseline.metadata.copy(
                mappingJavaTypeIdentity = "java.lang.Object",
                typeHandlerIdentity = "org.apache.ibatis.type.UnknownTypeHandler",
            ),
        )

        val result = PostgreSqlBigintExecutionMaterializer(target()).materialize(
            prepared("SELECT ?", bindings = listOf(stockUnknown)),
        )

        assertEquals("SELECT 7", success(result).executionSql)
    }

    @Test
    fun quotedCommentAndPostgresOperatorQuestionMarksFailClosed() {
        val cases = listOf(
            "SELECT '?'::text, ?",
            "SELECT 1 /* ? */ + ?",
            "SELECT 1 -- ?\n, ?",
            "SELECT $$?$$::text, ?",
            "SELECT '{}'::jsonb ? 'key', ?",
            "SELECT '{}'::jsonb ?| array['key'], ?",
        )

        cases.forEach { sql ->
            val result = PostgreSqlBigintExecutionMaterializer(target()).materialize(prepared(sql, BigInteger.ONE))
            assertFailure(result, MaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNSUPPORTED)
        }
    }

    @Test
    fun syntheticBarePostgresQuestionOperatorCannotMasqueradeAsBinding() {
        val result = PostgreSqlBigintExecutionMaterializer(target()).materialize(
            prepared("SELECT '{}'::jsonb ? 'key'", BigInteger.ONE),
        )

        assertFailure(result, MaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNSUPPORTED)
    }

    @Test
    fun zeroOrMultipleBindingsAreOutsideFirstProofSlice() {
        val zero = PostgreSqlBigintExecutionMaterializer(target()).materialize(
            prepared("SELECT 1", bindings = emptyList()),
        )
        val two = PostgreSqlBigintExecutionMaterializer(target()).materialize(
            prepared(
                "SELECT ?, ?",
                bindings = listOf(binding(BigInteger.ONE, 0), binding(BigInteger.TWO, 1)),
            ),
        )

        assertFailure(zero, MaterializationFailureKind.BINDING_COUNT_UNSUPPORTED)
        assertFailure(two, MaterializationFailureKind.BINDING_COUNT_UNSUPPORTED)
    }

    private fun prepared(
        sql: String,
        value: BigInteger = BigInteger.ONE,
        bindings: List<PreparedBinding> = listOf(binding(value)),
    ): PreparedExecution {
        val file = SourceFileId("vfs:/Query.xml")
        return PreparedExecution(
            statementId = XmlStatementId(file, "fixture.QueryMapper", "ping"),
            statementKind = StatementKind.SELECT,
            sourceRevisions = mapOf(file to SourceRevision("r1")),
            sqlWithPlaceholders = sql,
            orderedBindings = bindings,
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                engineIdentity = "org.mybatis:mybatis",
                engineVersion = "3.5.19",
                languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
            ),
        )
    }

    private fun binding(value: BigInteger, index: Int = 0): PreparedBinding {
        val file = SourceFileId("vfs:/Query.xml")
        val requirementId = InputRequirementId("id")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.Placeholder(
                    kind = InputKind.BOUND,
                    expression = "id",
                    source = SourceEvidence(file, SourceRevision("r1"), SourceRange(0, 5)),
                ),
            ),
        )
        return PreparedBinding(
            index = index,
            property = "id",
            value = InputValue.IntegerValue(value),
            origin = PreparedBindingOrigin.CallerInput(requirementId, provenance),
            metadata = PreparedBindingMetadata(
                declaredJavaTypeIdentity = JavaTypeIdentity("long"),
                mappingJavaTypeIdentity = "java.lang.Long",
                jdbcTypeIdentity = "BIGINT",
                typeHandlerIdentity = "org.apache.ibatis.type.LongTypeHandler",
                parameterMode = "IN",
                numericScale = null,
            ),
        )
    }

    private fun target(
        dataSource: String = "postgres-e2e",
        schema: String = "public",
        databaseId: String? = ConventionalMyBatisDatabaseIds.POSTGRESQL,
    ) = ResolvedExecutionTarget(
        descriptor = ExecutionTargetDescriptor(
            targetId = ExecutionTargetId(
                StableDataSourceId(dataSource),
                ExplicitSchemaIdentity(schema),
            ),
            dataSourceDisplayName = null,
        ),
        effectiveDatabaseId = databaseId?.let(::MyBatisDatabaseId),
    )

    private fun success(result: MaterializationResult): MaterializedExecution =
        (result as MaterializationResult.Success).execution

    private fun assertFailure(
        result: MaterializationResult,
        kind: MaterializationFailureKind,
    ) {
        assertTrue(result is MaterializationResult.Failed)
        assertEquals(kind, (result as MaterializationResult.Failed).failure.kind)
    }
}
