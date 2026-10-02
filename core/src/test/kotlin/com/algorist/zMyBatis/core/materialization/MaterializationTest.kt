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
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
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
        val sql = "select '?' as literal_marker, payload ? 'key' as vendor_operator"
        val prepared = prepared(sql = sql)

        val execution = success(ZeroBindingExecutionMaterializer.materialize(prepared))

        assertEquals(sql, execution.executionSql)
        assertEquals(prepared.statementId, execution.statementId)
        assertEquals(prepared.sourceRevisions, execution.sourceRevisions)
        assertEquals(prepared.preparationMetadata, execution.preparationMetadata)
        assertFalse(execution.safetyFlags.declaredMutation)
        assertFalse(execution.safetyFlags.containsRawInterpolation)
    }

    @Test
    fun maintainedEntryPointIsDatabaseNeutralZeroBindingBoundary() {
        val sql = "select 1"
        val execution = success(MaintainedExecutionMaterializer.materialize(prepared(sql = sql)))

        assertEquals(sql, execution.executionSql)
    }

    @Test
    fun anyBoundMappingRequiresDatabaseToolsBoundExecutionInsteadOfLiteralization() {
        val result = MaintainedExecutionMaterializer.materialize(
            prepared(
                sql = "select ?",
                bindings = listOf(boundBinding("top-secret-value")),
            ),
        )

        val failure = assertFailure(
            result,
            MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
            "materialization-bound-execution-required",
        )
        assertFalse(failure.toString().contains("top-secret-value"))
        assertFalse(failure.toString().contains("select ?"))
    }

    @Test
    fun rawInterpolationStillRequiresExplicitPolicy() {
        val result = MaintainedExecutionMaterializer.materialize(
            prepared(
                sql = "select * from users",
                rawInterpolations = listOf(
                    PreparedRawInterpolation(
                        requirementId = InputRequirementId("raw-table"),
                        provenance = provenance(InputKind.RAW_INTERPOLATION, "table"),
                        origin = ExecutionInputOrigin.USER_ENTERED,
                    ),
                ),
            ),
        )

        val failure = assertFailure(
            result,
            MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY,
            "materialization-raw-interpolation-policy-required",
        )
        assertFalse(failure.toString().contains("select * from users"))
    }

    @Test
    fun mutationDeclarationIsRetainedAsSafetyEvidence() {
        val execution = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(kind = StatementKind.UPDATE, sql = "update users set active = 0"),
            ),
        )

        assertTrue(execution.safetyFlags.declaredMutation)
    }

    @Test
    fun fingerprintIsDeterministicAndChangesWithExecutionRelevantIdentity() {
        val baseline = prepared()
        val first = success(MaintainedExecutionMaterializer.materialize(baseline))
        val second = success(MaintainedExecutionMaterializer.materialize(baseline))

        assertEquals(first.fingerprint, second.fingerprint)

        val variants = listOf(
            success(MaintainedExecutionMaterializer.materialize(prepared(sql = "select 2"))),
            success(MaintainedExecutionMaterializer.materialize(prepared(revision = "r2"))),
            success(MaintainedExecutionMaterializer.materialize(prepared(engineVersion = "3.5.20"))),
            success(MaintainedExecutionMaterializer.materialize(prepared(namespace = "fixture.OtherMapper"))),
        )

        variants.forEach { variant -> assertNotEquals(first.fingerprint, variant.fingerprint) }
    }

    @Test
    fun javaStatementIdentityParticipatesInFingerprint() {
        val file = SourceFileId("vfs:/Mapper.java")
        val firstStatement = JavaStatementId(
            sourceFileId = file,
            qualifiedMapperType = "fixture.Mapper",
            methodSignature = MethodSignature("find", listOf(JavaTypeIdentity("java.lang.Long"))),
        )
        val secondStatement = JavaStatementId(
            sourceFileId = file,
            qualifiedMapperType = "fixture.Mapper",
            methodSignature = MethodSignature("find", listOf(JavaTypeIdentity("java.lang.String"))),
        )
        val revisions = mapOf(file to SourceRevision("r1"))

        val first = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(statementId = firstStatement, sourceRevisions = revisions),
            ),
        )
        val second = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(statementId = secondStatement, sourceRevisions = revisions),
            ),
        )

        assertNotEquals(first.fingerprint, second.fingerprint)
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
    fun materializationFingerprintRejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            MaterializationFingerprint("not-a-digest")
        }
    }

    private fun prepared(
        kind: StatementKind = StatementKind.SELECT,
        sql: String = "select 1",
        revision: String = "r1",
        engineIdentity: String = "org.mybatis:mybatis",
        engineVersion: String = "3.5.19",
        languageDriverIdentity: String = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
        namespace: String = "fixture.Mapper",
        bindings: List<PreparedBinding> = emptyList(),
        rawInterpolations: List<PreparedRawInterpolation> = emptyList(),
        statementId: com.algorist.zMyBatis.core.source.StatementId? = null,
        sourceRevisions: Map<SourceFileId, SourceRevision>? = null,
    ): PreparedExecution {
        val file = SourceFileId("vfs:/mapper.xml")
        val resolvedStatementId = statementId ?: XmlStatementId(file, namespace, "find")
        val resolvedRevisions = sourceRevisions ?: mapOf(file to SourceRevision(revision))
        return PreparedExecution(
            statementId = resolvedStatementId,
            statementKind = kind,
            sourceRevisions = resolvedRevisions,
            sqlWithPlaceholders = sql,
            orderedBindings = bindings,
            rawInterpolations = rawInterpolations,
            preparationMetadata = metadata(
                engineIdentity = engineIdentity,
                engineVersion = engineVersion,
                languageDriverIdentity = languageDriverIdentity,
            ),
        )
    }

    private fun metadata(
        engineIdentity: String = "org.mybatis:mybatis",
        engineVersion: String = "3.5.19",
        languageDriverIdentity: String = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
    ) = PreparationMetadata(
        engineIdentity = engineIdentity,
        engineVersion = engineVersion,
        languageDriverIdentity = languageDriverIdentity,
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

    private fun assertFailure(
        result: MaterializationResult,
        expectedKind: MaterializationFailureKind,
        expectedCode: String,
    ): MaterializationFailure {
        val failure = (result as MaterializationResult.Failed).failure
        assertEquals(expectedKind, failure.kind)
        assertEquals(expectedCode, failure.code)
        return failure
    }

    private fun success(result: MaterializationResult): MaterializedExecution = when (result) {
        is MaterializationResult.Success -> result.execution
        is MaterializationResult.Failed -> throw AssertionError(
            "expected materialization success but got " +
                result.failure.kind +
                " / " +
                result.failure.code,
        )
    }
}
