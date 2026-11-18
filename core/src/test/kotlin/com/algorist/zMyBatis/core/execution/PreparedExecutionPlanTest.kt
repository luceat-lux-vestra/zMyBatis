package com.algorist.zMyBatis.core.execution

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ForeachLocalRole
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
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementKind
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedExecutionPlanTest {
    @Test
    fun freezeCapturesExactPreparedAndTargetAuthority() {
        val prepared = prepared()
        val target = target()

        val plan = ready(PreparedExecutionPlanner.freeze(prepared, target))

        assertEquals(prepared.statementId, plan.statementId)
        assertEquals(prepared.statementKind, plan.statementKind)
        assertEquals(prepared.sourceRevisions, plan.sourceRevisions)
        assertEquals(prepared.preparationMetadata, plan.preparationMetadata)
        assertEquals(target.targetId, plan.executionTargetId)
        assertEquals(target.effectiveDatabaseId?.value, plan.databaseId)
        assertEquals(prepared.sqlWithPlaceholders, plan.sqlWithPlaceholders)
        assertEquals(prepared.orderedBindings, plan.orderedBindings)
        assertEquals(prepared.rawInterpolations, plan.rawInterpolations)
        assertTrue(plan.safetyFlags.containsRawInterpolation)
        assertTrue(!plan.safetyFlags.declaredMutation)
        assertTrue(Regex("[0-9a-f]{64}").matches(plan.fingerprint.value))
    }

    @Test
    fun fingerprintIsDeterministicAndSensitiveToExecutableAuthority() {
        val baseline = plan()
        assertEquals(baseline.fingerprint, plan().fingerprint)

        val variants = listOf(
            plan(sql = "select ?, ? /* changed */"),
            plan(sourceRevision = "revision-2"),
            plan(dataSourceId = "datasource-b"),
            plan(schema = "audit"),
            plan(databaseId = "VendorB"),
            plan(statementKind = StatementKind.UPDATE),
            plan(engineVersion = "3.6.1"),
            plan(firstBindingProperty = "renamed"),
            plan(firstBindingValue = 42L),
            plan(firstBindingHandler = "org.apache.ibatis.type.IntegerTypeHandler"),
            plan(additionalBindingName = "__frch_other_0"),
            plan(reverseBindingAuthority = true),
            plan(rawOrigin = ExecutionInputOrigin.USER_ACCEPTED_RETAINED),
            plan(rawExpression = "otherColumn"),
        )

        variants.forEach { variant ->
            assertNotEquals(baseline.fingerprint, variant.fingerprint)
        }
    }

    @Test
    fun objectAndMapInsertionOrderDoNotChangeFingerprint() {
        val first = plan(
            firstBindingValueOverride = InputValue.MapValue(
                linkedMapOf(
                    "a" to InputValue.Text("one"),
                    "b" to InputValue.IntegerValue(BigInteger.TWO),
                ),
            ),
            firstBindingJavaType = "java.util.Map",
            firstBindingHandler = "org.apache.ibatis.type.UnknownTypeHandler",
        )
        val second = plan(
            firstBindingValueOverride = InputValue.MapValue(
                linkedMapOf(
                    "b" to InputValue.IntegerValue(BigInteger.TWO),
                    "a" to InputValue.Text("one"),
                ),
            ),
            firstBindingJavaType = "java.util.Map",
            firstBindingHandler = "org.apache.ibatis.type.UnknownTypeHandler",
        )

        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun planSnapshotsCallerCollections() {
        val sources = linkedMapOf(
            SourceFileId("fixture/Mapper.java") to SourceRevision("revision-1"),
        )
        val bindings = mutableListOf(binding(0, "id", 41L))
        val raw = mutableListOf(rawInterpolation())
        val prepared = PreparedExecution(
            statementId = statementId(),
            statementKind = StatementKind.SELECT,
            sourceRevisions = sources,
            sqlWithPlaceholders = "select ?, ?",
            orderedBindings = bindings,
            rawInterpolations = raw,
            preparationMetadata = metadata(),
        )
        val plan = ready(PreparedExecutionPlanner.freeze(prepared, target()))

        sources[SourceFileId("fixture/Other.java")] = SourceRevision("revision-x")
        bindings.clear()
        raw.clear()

        assertEquals(1, plan.sourceRevisions.size)
        assertEquals(1, plan.orderedBindings.size)
        assertEquals(1, plan.rawInterpolations.size)
    }

    @Test
    fun databaseIdMismatchFailsTyped() {
        val result = PreparedExecutionPlanner.freeze(
            prepared = prepared(databaseId = "PostgreSQL"),
            target = target(databaseId = "VendorB"),
        )

        assertTrue(result is PreparedExecutionPlanResult.Failed)
        val failure = (result as PreparedExecutionPlanResult.Failed).failure
        assertEquals(PreparedExecutionPlanFailureKind.DATABASE_ID_MISMATCH, failure.kind)
        assertEquals("prepared-execution-plan-database-id-mismatch", failure.code)
    }

    private fun plan(
        sql: String = "select ?, ?",
        sourceRevision: String = "revision-1",
        dataSourceId: String = "datasource-a",
        schema: String = "public",
        databaseId: String = "PostgreSQL",
        statementKind: StatementKind = StatementKind.SELECT,
        engineVersion: String = "3.6.0",
        firstBindingProperty: String = "id",
        firstBindingValue: Long = 41L,
        firstBindingValueOverride: InputValue? = null,
        firstBindingJavaType: String = "java.lang.Long",
        firstBindingHandler: String = "org.apache.ibatis.type.LongTypeHandler",
        additionalBindingName: String = "__frch_item_0",
        reverseBindingAuthority: Boolean = false,
        rawOrigin: ExecutionInputOrigin = ExecutionInputOrigin.USER_ENTERED,
        rawExpression: String = "column",
    ): PreparedExecutionPlan =
        ready(
            PreparedExecutionPlanner.freeze(
                prepared = prepared(
                    sql = sql,
                    sourceRevision = sourceRevision,
                    databaseId = databaseId,
                    statementKind = statementKind,
                    engineVersion = engineVersion,
                    firstBindingProperty = firstBindingProperty,
                    firstBindingValue = firstBindingValue,
                    firstBindingValueOverride = firstBindingValueOverride,
                    firstBindingJavaType = firstBindingJavaType,
                    firstBindingHandler = firstBindingHandler,
                    additionalBindingName = additionalBindingName,
                    reverseBindingAuthority = reverseBindingAuthority,
                    rawOrigin = rawOrigin,
                    rawExpression = rawExpression,
                ),
                target = target(dataSourceId, schema, databaseId),
            ),
        )

    private fun prepared(
        sql: String = "select ?, ?",
        sourceRevision: String = "revision-1",
        databaseId: String = "PostgreSQL",
        statementKind: StatementKind = StatementKind.SELECT,
        engineVersion: String = "3.6.0",
        firstBindingProperty: String = "id",
        firstBindingValue: Long = 41L,
        firstBindingValueOverride: InputValue? = null,
        firstBindingJavaType: String = "java.lang.Long",
        firstBindingHandler: String = "org.apache.ibatis.type.LongTypeHandler",
        additionalBindingName: String = "__frch_item_0",
        reverseBindingAuthority: Boolean = false,
        rawOrigin: ExecutionInputOrigin = ExecutionInputOrigin.USER_ENTERED,
        rawExpression: String = "column",
    ): PreparedExecution {
        val revision = SourceRevision(sourceRevision)
        val source = SourceFileId("fixture/Mapper.java")
        val sourceEvidence = SourceEvidence(source, revision, SourceRange(10, 20))
        val callerProvenance = InputProvenance(
            listOf(
                InputEvidence.Placeholder(
                    InputKind.BOUND,
                    firstBindingProperty,
                    sourceEvidence,
                ),
            ),
        )
        val additionalProvenance = InputProvenance(
            listOf(
                InputEvidence.ForeachLocal(
                    name = "item",
                    role = ForeachLocalRole.ITEM,
                    source = sourceEvidence,
                ),
            ),
        )

        val callerBinding = PreparedBinding(
            index = 0,
            property = firstBindingProperty,
            value = firstBindingValueOverride
                ?: InputValue.IntegerValue(BigInteger.valueOf(firstBindingValue)),
            origin = PreparedBindingOrigin.CallerInput(
                requirementId = InputRequirementId("method-param:0"),
                provenance = callerProvenance,
            ),
            metadata = PreparedBindingMetadata(
                declaredJavaTypeIdentity = JavaTypeIdentity(firstBindingJavaType),
                mappingJavaTypeIdentity = firstBindingJavaType,
                jdbcTypeIdentity = "BIGINT",
                effectiveJdbcTypeIdentity = "BIGINT",
                typeHandlerIdentity = firstBindingHandler,
                parameterMode = "IN",
                numericScale = null,
            ),
        )
        val additionalBinding = PreparedBinding(
            index = 1,
            property = "item",
            value = InputValue.Text("extra"),
            origin = PreparedBindingOrigin.MyBatisAdditional(
                InternalBinding(
                    name = additionalBindingName,
                    kind = InternalBindingKind.FOREACH_ITEM,
                    provenance = additionalProvenance,
                ),
            ),
            metadata = PreparedBindingMetadata(
                declaredJavaTypeIdentity = null,
                mappingJavaTypeIdentity = "java.lang.String",
                jdbcTypeIdentity = "VARCHAR",
                effectiveJdbcTypeIdentity = "VARCHAR",
                typeHandlerIdentity = "org.apache.ibatis.type.StringTypeHandler",
                parameterMode = "IN",
                numericScale = null,
            ),
        )
        val orderedBindings = if (reverseBindingAuthority) {
            listOf(
                additionalBinding.copy(index = 0),
                callerBinding.copy(index = 1),
            )
        } else {
            listOf(callerBinding, additionalBinding)
        }

        return PreparedExecution(
            statementId = statementId(),
            statementKind = statementKind,
            sourceRevisions = mapOf(source to revision),
            sqlWithPlaceholders = sql,
            orderedBindings = orderedBindings,
            rawInterpolations = listOf(rawInterpolation(rawOrigin, sourceEvidence, rawExpression)),
            preparationMetadata = metadata(engineVersion, databaseId),
        )
    }

    private fun binding(index: Int, property: String, value: Long): PreparedBinding {
        val source = SourceEvidence(
            SourceFileId("fixture/Mapper.java"),
            SourceRevision("revision-1"),
            SourceRange(10, 20),
        )
        return PreparedBinding(
            index = index,
            property = property,
            value = InputValue.IntegerValue(BigInteger.valueOf(value)),
            origin = PreparedBindingOrigin.CallerInput(
                InputRequirementId("method-param:0"),
                InputProvenance(listOf(InputEvidence.Placeholder(InputKind.BOUND, property, source))),
            ),
            metadata = PreparedBindingMetadata(
                declaredJavaTypeIdentity = JavaTypeIdentity("java.lang.Long"),
                mappingJavaTypeIdentity = "java.lang.Long",
                jdbcTypeIdentity = "BIGINT",
                effectiveJdbcTypeIdentity = "BIGINT",
                typeHandlerIdentity = "org.apache.ibatis.type.LongTypeHandler",
                parameterMode = "IN",
                numericScale = null,
            ),
        )
    }

    private fun rawInterpolation(
        origin: ExecutionInputOrigin = ExecutionInputOrigin.USER_ENTERED,
        sourceEvidence: SourceEvidence = SourceEvidence(
            SourceFileId("fixture/Mapper.java"),
            SourceRevision("revision-1"),
            SourceRange(21, 30),
        ),
        expression: String = "column",
    ): PreparedRawInterpolation =
        PreparedRawInterpolation(
            requirementId = InputRequirementId("raw:column"),
            provenance = InputProvenance(
                listOf(
                    InputEvidence.Placeholder(
                        kind = InputKind.RAW_INTERPOLATION,
                        expression = expression,
                        source = sourceEvidence,
                    ),
                ),
            ),
            origin = origin,
        )

    private fun statementId(): JavaStatementId =
        JavaStatementId(
            sourceFileId = SourceFileId("fixture/Mapper.java"),
            qualifiedMapperType = "fixture.Mapper",
            methodSignature = MethodSignature(
                "query",
                listOf(JavaTypeIdentity("java.lang.Long")),
            ),
        )

    private fun metadata(
        engineVersion: String = "3.6.0",
        databaseId: String = "PostgreSQL",
    ): PreparationMetadata =
        PreparationMetadata(
            engineIdentity = "org.mybatis",
            engineVersion = engineVersion,
            languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
            databaseId = databaseId,
        )

    private fun target(
        dataSourceId: String = "datasource-a",
        schema: String = "public",
        databaseId: String = "PostgreSQL",
    ): ResolvedExecutionTarget =
        ResolvedExecutionTarget(
            descriptor = ExecutionTargetDescriptor(
                targetId = ExecutionTargetId(
                    StableDataSourceId(dataSourceId),
                    ExplicitSchemaIdentity(schema),
                ),
                dataSourceDisplayName = "presentation-only",
            ),
            effectiveDatabaseId = MyBatisDatabaseId(databaseId),
        )

    private fun ready(result: PreparedExecutionPlanResult): PreparedExecutionPlan =
        when (result) {
            is PreparedExecutionPlanResult.Ready -> result.plan
            is PreparedExecutionPlanResult.Failed ->
                throw AssertionError("expected plan, got ${result.failure.kind} / ${result.failure.code}")
        }
}
