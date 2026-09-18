package com.algorist.zMyBatis.core.materialization

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
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
import com.algorist.zMyBatis.core.preparation.PreparedRawInterpolation
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterializationTest {
    @Test
    fun zeroBindingSelectMaterializesWithoutChangingSql() {
        val sql = "select '?' as literal_marker, payload ? 'key' as dialect_operator"
        val prepared = prepared(sql = sql)
        val dialect = TargetDialectIdentity("opaque-test-dialect")

        val execution = success(ZeroBindingExecutionMaterializer.materialize(prepared, dialect))

        assertEquals(sql, execution.executionSql)
        assertEquals(prepared.statementId, execution.statementId)
        assertEquals(StatementKind.SELECT, execution.statementKind)
        assertEquals(prepared.sourceRevisions, execution.sourceRevisions)
        assertEquals(prepared.preparationMetadata, execution.preparationMetadata)
        assertEquals(dialect, execution.targetDialectIdentity)
        assertFalse(execution.safetyFlags.declaredMutation)
        assertFalse(execution.safetyFlags.containsRawInterpolation)
    }

    @Test
    fun mutationDeclarationIsRetainedAsSafetyEvidence() {
        val execution = success(
            ZeroBindingExecutionMaterializer.materialize(
                prepared(kind = StatementKind.UPDATE, sql = "update users set active = 0"),
                TargetDialectIdentity("opaque-test-dialect"),
            ),
        )

        assertEquals(StatementKind.UPDATE, execution.statementKind)
        assertTrue(execution.safetyFlags.declaredMutation)
    }

    @Test
    fun fingerprintIsDeterministicAndChangesWithExecutionRelevantIdentity() {
        val baseline = prepared()
        val dialect = TargetDialectIdentity("dialect-a")
        val first = success(ZeroBindingExecutionMaterializer.materialize(baseline, dialect))
        val second = success(ZeroBindingExecutionMaterializer.materialize(baseline, dialect))

        assertEquals(first.fingerprint, second.fingerprint)

        val variants = listOf(
            success(
                ZeroBindingExecutionMaterializer.materialize(
                    prepared(sql = "select 2"),
                    dialect,
                ),
            ),
            success(
                ZeroBindingExecutionMaterializer.materialize(
                    prepared(revision = "r2"),
                    dialect,
                ),
            ),
            success(
                ZeroBindingExecutionMaterializer.materialize(
                    prepared(engineVersion = "3.5.20"),
                    dialect,
                ),
            ),
            success(
                ZeroBindingExecutionMaterializer.materialize(
                    prepared(namespace = "fixture.OtherMapper"),
                    dialect,
                ),
            ),
            success(
                ZeroBindingExecutionMaterializer.materialize(
                    baseline,
                    TargetDialectIdentity("dialect-b"),
                ),
            ),
        )

        variants.forEach { variant ->
            assertNotEquals(first.fingerprint, variant.fingerprint)
        }
        assertEquals(variants.size + 1, (variants.map { it.fingerprint } + first.fingerprint).toSet().size)
    }

    @Test
    fun anyBoundMappingFailsWithoutGuessingLiteralization() {
        val prepared = prepared(
            sql = "select ?",
            bindings = listOf(boundBinding("top-secret-value")),
        )

        val result = ZeroBindingExecutionMaterializer.materialize(
            prepared,
            TargetDialectIdentity("opaque-test-dialect"),
        )

        val failure = (result as MaterializationResult.Failed).failure
        assertEquals(MaterializationFailureKind.DIALECT_LITERALIZATION_REQUIRED, failure.kind)
        assertEquals("materialization-dialect-literalization-required", failure.code)
        assertFalse(failure.toString().contains("top-secret-value"))
        assertFalse(failure.toString().contains("select ?"))
    }

    @Test
    fun rawInterpolationProvenanceFailsBeforeExecutableArtifactExists() {
        val provenance = provenance(InputKind.RAW_INTERPOLATION, "table")
        val prepared = prepared(
            sql = "select * from users",
            rawInterpolations = listOf(
                PreparedRawInterpolation(
                    requirementId = InputRequirementId("raw-table"),
                    provenance = provenance,
                    origin = ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        )

        val result = ZeroBindingExecutionMaterializer.materialize(
            prepared,
            TargetDialectIdentity("opaque-test-dialect"),
        )

        val failure = (result as MaterializationResult.Failed).failure
        assertEquals(MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY, failure.kind)
        assertEquals("materialization-raw-interpolation-policy-required", failure.code)
        assertFalse(failure.toString().contains("select * from users"))
    }

    @Test
    fun materializedExecutionDefensivelyCopiesSourceRevisions() {
        val file = SourceFileId("vfs:/mapper.xml")
        val revisions = linkedMapOf(file to SourceRevision("r1"))
        val execution = MaterializedExecution(
            statementId = XmlStatementId(file, "fixture.Mapper", "find"),
            statementKind = StatementKind.SELECT,
            sourceRevisions = revisions,
            preparationMetadata = metadata(),
            targetDialectIdentity = TargetDialectIdentity("opaque-test-dialect"),
            executionSql = "select 1",
            safetyFlags = MaterializationSafetyFlags(
                declaredMutation = false,
                containsRawInterpolation = false,
            ),
            fingerprint = MaterializationFingerprint("0".repeat(64)),
        )

        revisions.clear()

        assertEquals(mapOf(file to SourceRevision("r1")), execution.sourceRevisions)
        val exposed = execution.sourceRevisions as MutableMap
        exposed.clear()
        assertEquals(mapOf(file to SourceRevision("r1")), execution.sourceRevisions)
    }

    @Test
    fun materializationIdentityValuesRejectBlankOrMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetDialectIdentity(" ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaterializationFingerprint("not-a-digest")
        }
    }

    private fun prepared(
        kind: StatementKind = StatementKind.SELECT,
        sql: String = "select 1",
        revision: String = "r1",
        engineVersion: String = "3.5.19",
        namespace: String = "fixture.Mapper",
        bindings: List<PreparedBinding> = emptyList(),
        rawInterpolations: List<PreparedRawInterpolation> = emptyList(),
    ): PreparedExecution {
        val file = SourceFileId("vfs:/mapper.xml")
        return PreparedExecution(
            statementId = XmlStatementId(file, namespace, "find"),
            statementKind = kind,
            sourceRevisions = mapOf(file to SourceRevision(revision)),
            sqlWithPlaceholders = sql,
            orderedBindings = bindings,
            rawInterpolations = rawInterpolations,
            preparationMetadata = metadata(engineVersion),
        )
    }

    private fun metadata(engineVersion: String = "3.5.19") = PreparationMetadata(
        engineIdentity = "org.mybatis:mybatis",
        engineVersion = engineVersion,
        languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
    )

    private fun boundBinding(secret: String): PreparedBinding {
        val requirementId = InputRequirementId("bound-id")
        val provenance = provenance(InputKind.BOUND, "id")
        return PreparedBinding(
            index = 0,
            property = "id",
            value = InputValue.Text(secret),
            origin = PreparedBindingOrigin.CallerInput(requirementId, provenance),
            metadata = PreparedBindingMetadata(
                declaredJavaTypeIdentity = JavaTypeIdentity("java.lang.String"),
                mappingJavaTypeIdentity = "java.lang.String",
                jdbcTypeIdentity = "VARCHAR",
                typeHandlerIdentity = "org.apache.ibatis.type.StringTypeHandler",
                parameterMode = "IN",
                numericScale = null,
            ),
        )
    }

    private fun provenance(kind: InputKind, expression: String): InputProvenance {
        val file = SourceFileId("vfs:/mapper.xml")
        return InputProvenance(
            listOf(
                InputEvidence.Placeholder(
                    kind = kind,
                    expression = expression,
                    source = SourceEvidence(
                        sourceFileId = file,
                        sourceRevision = SourceRevision("r1"),
                        sourceRange = SourceRange(0, expression.length),
                    ),
                ),
            ),
        )
    }

    private fun success(result: MaterializationResult): MaterializedExecution = when (result) {
        is MaterializationResult.Success -> result.execution
        is MaterializationResult.Failed -> throw AssertionError(
            "expected materialization success but got ${result.failure.kind} / ${result.failure.code}",
        )
    }
}
