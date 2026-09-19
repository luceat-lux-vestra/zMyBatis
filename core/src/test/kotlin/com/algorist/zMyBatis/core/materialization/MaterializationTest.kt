package com.algorist.zMyBatis.core.materialization

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
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
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
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
    fun maintainedMaterializerKeepsZeroBindingBehaviorUnchanged() {
        val sql = "select '?' as literal_marker, payload ? 'key' as dialect_operator"
        val execution = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(sql = sql),
                TargetDialectIdentity("postgresql"),
            ),
        )

        assertEquals(sql, execution.executionSql)
    }

    @Test
    fun postgresqlLongBindingMaterializesAsTypedBigintCast() {
        val execution = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(
                    sql = "select * from users where id = ?",
                    bindings = listOf(longBinding(0, BigInteger.valueOf(7))),
                ),
                TargetDialectIdentity("postgresql"),
            ),
        )

        assertEquals("select * from users where id = CAST(7 AS BIGINT)", execution.executionSql)
        assertEquals(TargetDialectIdentity("postgresql"), execution.targetDialectIdentity)
    }

    @Test
    fun explicitBigintJdbcTypeIsAdmitted() {
        val execution = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(
                    sql = "select ?",
                    bindings = listOf(
                        longBinding(
                            index = 0,
                            value = BigInteger.valueOf(17),
                            jdbcType = "BIGINT",
                        ),
                    ),
                ),
                TargetDialectIdentity("postgresql"),
            ),
        )

        assertEquals("select CAST(17 AS BIGINT)", execution.executionSql)
    }

    @Test
    fun multipleLongBindingsPreserveOrder() {
        val execution = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(
                    sql = "select * from events where id between ? and ?",
                    bindings = listOf(
                        longBinding(0, BigInteger.valueOf(3)),
                        longBinding(1, BigInteger.valueOf(9)),
                    ),
                ),
                TargetDialectIdentity("postgresql"),
            ),
        )

        assertEquals(
            "select * from events where id between CAST(3 AS BIGINT) and CAST(9 AS BIGINT)",
            execution.executionSql,
        )
    }

    @Test
    fun foreachAdditionalLongBindingUsesTheSameMaintainedMatrix() {
        val internal = InternalBinding(
            name = "item",
            kind = InternalBindingKind.FOREACH_ITEM,
            provenance = provenance(InputKind.BOUND, "ids"),
        )
        val execution = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(
                    sql = "select * from users where id in (?, ?)",
                    bindings = listOf(
                        longBinding(
                            0,
                            BigInteger.valueOf(11),
                            origin = PreparedBindingOrigin.MyBatisAdditional(internal),
                        ),
                        longBinding(
                            1,
                            BigInteger.valueOf(12),
                            origin = PreparedBindingOrigin.MyBatisAdditional(internal),
                        ),
                    ),
                ),
                TargetDialectIdentity("postgresql"),
            ),
        )

        assertEquals(
            "select * from users where id in (CAST(11 AS BIGINT), CAST(12 AS BIGINT))",
            execution.executionSql,
        )
    }

    @Test
    fun signedLongBoundariesAreAdmitted() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE).forEach { value ->
            val execution = success(
                MaintainedExecutionMaterializer.materialize(
                    prepared(
                        sql = "select ?",
                        bindings = listOf(longBinding(0, BigInteger.valueOf(value))),
                    ),
                    TargetDialectIdentity("postgresql"),
                ),
            )

            assertEquals("select CAST(" + value + " AS BIGINT)", execution.executionSql)
        }
    }

    @Test
    fun materializedBindingValueParticipatesInFingerprint() {
        val first = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(sql = "select ?", bindings = listOf(longBinding(0, BigInteger.ONE))),
                TargetDialectIdentity("postgresql"),
            ),
        )
        val second = success(
            MaintainedExecutionMaterializer.materialize(
                prepared(sql = "select ?", bindings = listOf(longBinding(0, BigInteger.TWO))),
                TargetDialectIdentity("postgresql"),
            ),
        )

        assertNotEquals(first.executionSql, second.executionSql)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun nonPostgresqlDialectFailsClosed() {
        val result = MaintainedExecutionMaterializer.materialize(
            prepared(sql = "select ?", bindings = listOf(longBinding(0, BigInteger.ONE))),
            TargetDialectIdentity("mysql"),
        )

        assertFailure(
            result,
            MaterializationFailureKind.DIALECT_UNSUPPORTED,
            "materialization-postgresql-dialect-required",
        )
    }

    @Test
    fun outOfRangeIntegerFailsClosed() {
        listOf(
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
            BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
        ).forEach { value ->
            val result = MaintainedExecutionMaterializer.materialize(
                prepared(sql = "select ?", bindings = listOf(longBinding(0, value))),
                TargetDialectIdentity("postgresql"),
            )

            assertFailure(
                result,
                MaterializationFailureKind.BINDING_VALUE_OUT_OF_RANGE,
                "materialization-postgresql-bigint-value-out-of-range",
            )
        }
    }

    @Test
    fun unsupportedLongMetadataFailsClosedByExactReason() {
        val cases = listOf(
            longBinding(0, BigInteger.ONE, mappingJavaType = "java.lang.Integer") to
                "materialization-postgresql-bigint-mapping-java-type-unsupported",
            longBinding(
                0,
                BigInteger.ONE,
                typeHandler = "org.apache.ibatis.type.IntegerTypeHandler",
            ) to "materialization-postgresql-bigint-type-handler-unsupported",
            longBinding(
                0,
                BigInteger.ONE,
                typeHandler = "com.example.CustomLongTypeHandler",
            ) to "materialization-postgresql-bigint-type-handler-unsupported",
            longBinding(0, BigInteger.ONE, jdbcType = "INTEGER") to
                "materialization-postgresql-bigint-jdbc-type-unsupported",
            longBinding(0, BigInteger.ONE, parameterMode = "OUT") to
                "materialization-postgresql-bigint-parameter-mode-unsupported",
            longBinding(0, BigInteger.ONE, numericScale = 0) to
                "materialization-postgresql-bigint-numeric-scale-unsupported",
        )

        cases.forEach { (binding, code) ->
            val result = MaintainedExecutionMaterializer.materialize(
                prepared(sql = "select ?", bindings = listOf(binding)),
                TargetDialectIdentity("postgresql"),
            )
            assertFailure(result, MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED, code)
        }
    }

    @Test
    fun nonIntegerValuesRemainOutsideTheMatrix() {
        val values = listOf<InputValue>(
            InputValue.NullValue,
            InputValue.Text("top-secret-value"),
            InputValue.BooleanValue(true),
            InputValue.DecimalValue(BigDecimal("1.25")),
            InputValue.DateValue(LocalDate.of(2026, 9, 19)),
            InputValue.TimeValue(LocalTime.of(12, 34)),
            InputValue.DateTimeValue(LocalDateTime.of(2026, 9, 19, 12, 34)),
            InputValue.InstantValue(Instant.parse("2026-09-19T00:00:00Z")),
            InputValue.UuidValue(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
            InputValue.ListValue(listOf(InputValue.IntegerValue(BigInteger.ONE))),
            InputValue.ArrayValue(listOf(InputValue.IntegerValue(BigInteger.ONE))),
            InputValue.MapValue(mapOf("id" to InputValue.IntegerValue(BigInteger.ONE))),
            InputValue.ObjectValue(mapOf("id" to InputValue.IntegerValue(BigInteger.ONE))),
        )

        values.forEach { value ->
            val result = MaintainedExecutionMaterializer.materialize(
                prepared(sql = "select ?", bindings = listOf(binding(0, value))),
                TargetDialectIdentity("postgresql"),
            )
            val failure = assertFailure(
                result,
                MaterializationFailureKind.BINDING_VALUE_UNSUPPORTED,
                "materialization-postgresql-bigint-value-unsupported",
            )
            assertFalse(failure.toString().contains("top-secret-value"))
            assertFalse(failure.toString().contains("select ?"))
        }
    }

    @Test
    fun quotedCommentAndDollarSyntaxCannotEnterBindingMaterialization() {
        val sqlVariants = listOf(
            "select '?' as marker, ?",
            "select \"?\" as marker, ?",
            "select $$?$$, ?",
            "select ? -- comment",
            "select ? /* comment */",
        )

        sqlVariants.forEach { sql ->
            val result = MaintainedExecutionMaterializer.materialize(
                prepared(sql = sql, bindings = listOf(longBinding(0, BigInteger.ONE))),
                TargetDialectIdentity("postgresql"),
            )
            assertFailure(
                result,
                MaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNPROVEN,
                "materialization-placeholder-topology-unproven",
            )
        }
    }

    @Test
    fun postgresqlQuestionMarkOperatorsAndCardinalityMismatchFailClosed() {
        val sqlVariants = listOf(
            "select payload ? key from t where id = ?",
            "select payload ?| tags from t where id = ?",
            "select payload ?& tags from t where id = ?",
            "select ? + ?",
        )

        sqlVariants.forEach { sql ->
            val result = MaintainedExecutionMaterializer.materialize(
                prepared(sql = sql, bindings = listOf(longBinding(0, BigInteger.ONE))),
                TargetDialectIdentity("postgresql"),
            )
            assertFailure(
                result,
                MaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNPROVEN,
                "materialization-placeholder-topology-unproven",
            )
        }
    }

    @Test
    fun maintainedMaterializerRejectsRawInterpolationBeforeBindingWork() {
        val rawProvenance = provenance(InputKind.RAW_INTERPOLATION, "table")
        val result = MaintainedExecutionMaterializer.materialize(
            prepared(
                sql = "select ?",
                bindings = listOf(longBinding(0, BigInteger.ONE)),
                rawInterpolations = listOf(
                    PreparedRawInterpolation(
                        requirementId = InputRequirementId("raw-table"),
                        provenance = rawProvenance,
                        origin = ExecutionInputOrigin.USER_ENTERED,
                    ),
                ),
            ),
            TargetDialectIdentity("postgresql"),
        )

        assertFailure(
            result,
            MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY,
            "materialization-raw-interpolation-policy-required",
        )
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
        val first = success(
            ZeroBindingExecutionMaterializer.materialize(
                prepared(statementId = firstStatement, sourceRevisions = mapOf(file to SourceRevision("r1"))),
                TargetDialectIdentity("dialect-a"),
            ),
        )
        val second = success(
            ZeroBindingExecutionMaterializer.materialize(
                prepared(statementId = secondStatement, sourceRevisions = mapOf(file to SourceRevision("r1"))),
                TargetDialectIdentity("dialect-a"),
            ),
        )

        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun sourceRevisionInsertionOrderDoesNotChangeFingerprint() {
        val root = SourceFileId("vfs:/mapper.xml")
        val dependency = SourceFileId("vfs:/fragment.xml")
        val statementId = XmlStatementId(root, "fixture.Mapper", "find")
        val firstRevisions = linkedMapOf(
            root to SourceRevision("root-r1"),
            dependency to SourceRevision("fragment-r1"),
        )
        val secondRevisions = linkedMapOf(
            dependency to SourceRevision("fragment-r1"),
            root to SourceRevision("root-r1"),
        )
        val dialect = TargetDialectIdentity("dialect-a")

        val first = success(
            ZeroBindingExecutionMaterializer.materialize(
                prepared(statementId = statementId, sourceRevisions = firstRevisions),
                dialect,
            ),
        )
        val second = success(
            ZeroBindingExecutionMaterializer.materialize(
                prepared(statementId = statementId, sourceRevisions = secondRevisions),
                dialect,
            ),
        )

        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun anyBoundMappingFailsWithoutGuessingLiteralizationInZeroBindingMaterializer() {
        val prepared = prepared(
            sql = "select ?",
            bindings = listOf(boundBinding("top-secret-value")),
        )

        val result = ZeroBindingExecutionMaterializer.materialize(
            prepared,
            TargetDialectIdentity("opaque-test-dialect"),
        )

        val failure = assertFailure(
            result,
            MaterializationFailureKind.DIALECT_LITERALIZATION_REQUIRED,
            "materialization-dialect-literalization-required",
        )
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

        val failure = assertFailure(
            result,
            MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY,
            "materialization-raw-interpolation-policy-required",
        )
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
            preparationMetadata = metadata(engineVersion),
        )
    }

    private fun metadata(engineVersion: String = "3.5.19") = PreparationMetadata(
        engineIdentity = "org.mybatis:mybatis",
        engineVersion = engineVersion,
        languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
    )

    private fun longBinding(
        index: Int,
        value: BigInteger,
        mappingJavaType: String? = "java.lang.Long",
        jdbcType: String? = null,
        typeHandler: String = "org.apache.ibatis.type.LongTypeHandler",
        parameterMode: String = "IN",
        numericScale: Int? = null,
        origin: PreparedBindingOrigin = callerOrigin(index),
    ): PreparedBinding = binding(
        index = index,
        value = InputValue.IntegerValue(value),
        origin = origin,
        mappingJavaType = mappingJavaType,
        jdbcType = jdbcType,
        typeHandler = typeHandler,
        parameterMode = parameterMode,
        numericScale = numericScale,
    )

    private fun binding(
        index: Int,
        value: InputValue,
        origin: PreparedBindingOrigin = callerOrigin(index),
        mappingJavaType: String? = "java.lang.Long",
        jdbcType: String? = null,
        typeHandler: String = "org.apache.ibatis.type.LongTypeHandler",
        parameterMode: String = "IN",
        numericScale: Int? = null,
    ): PreparedBinding = PreparedBinding(
        index = index,
        property = "value" + index,
        value = value,
        origin = origin,
        metadata = PreparedBindingMetadata(
            declaredJavaTypeIdentity = JavaTypeIdentity("java.lang.Long"),
            mappingJavaTypeIdentity = mappingJavaType,
            jdbcTypeIdentity = jdbcType,
            typeHandlerIdentity = typeHandler,
            parameterMode = parameterMode,
            numericScale = numericScale,
        ),
    )

    private fun callerOrigin(index: Int): PreparedBindingOrigin {
        val requirementId = InputRequirementId("bound-" + index)
        return PreparedBindingOrigin.CallerInput(
            requirementId,
            provenance(InputKind.BOUND, "value" + index),
        )
    }

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
