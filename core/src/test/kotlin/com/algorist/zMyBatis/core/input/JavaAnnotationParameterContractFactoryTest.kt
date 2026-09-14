package com.algorist.zMyBatis.core.input

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaAnnotationParameterContractFactoryTest {
    @Test
    fun buildsBoundRequirementOnlyFromExplicitParamAndBoundSourceUse() {
        val capture = capture(
            sqlSegments = listOf("select * from users where id = #{id,jdbcType=BIGINT}"),
            parameters = listOf(parameter(0, "long", "id", "id")),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(capture.sourceGraph.rootStatement.id, contract.statementId)
        assertEquals(1, contract.requirements.size)
        val requirement = contract.requirements.single()
        assertEquals(InputRequirementId("java-param:0"), requirement.id)
        assertEquals(InputKind.BOUND, requirement.kind)
        assertEquals(InputRequiredness.REQUIRED, requirement.requiredness)
        assertEquals(InputShape.SCALAR, requirement.expectedType.shape)
        assertEquals(InputScalarType.INTEGER, requirement.expectedType.scalarType)
        assertEquals(InputNullability.NON_NULL, requirement.expectedType.nullability)
        assertEquals(JavaTypeIdentity("long"), requirement.expectedType.javaTypeIdentity)

        assertEquals(1, contract.aliases.size)
        val alias = contract.aliases.single()
        assertEquals("id", alias.name)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, alias.kind)
        assertEquals(requirement.id, alias.requirementId)

        val evidence = requirement.provenance.evidence
        assertTrue(evidence.any { it is InputEvidence.MapperMethodParameter && it.index == 0 })
        assertTrue(evidence.any { it is InputEvidence.ExplicitParamAlias && it.alias == "id" })
        assertTrue(
            evidence.any {
                it is InputEvidence.Placeholder &&
                    it.kind == InputKind.BOUND &&
                    it.expression == "id"
            },
        )
        assertTrue(contract.internalBindings.isEmpty())
    }

    @Test
    fun buildsRawTextRequirementOnlyFromRawSourceUse() {
        val capture = capture(
            sqlSegments = listOf("select * from \${table}"),
            parameters = listOf(parameter(0, "java.lang.String", "table", "table")),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputKind.RAW_INTERPOLATION, requirement.kind)
        assertEquals(InputShape.RAW_TEXT, requirement.expectedType.shape)
        assertEquals(InputScalarType.STRING, requirement.expectedType.scalarType)
        assertEquals(InputNullability.UNKNOWN, requirement.expectedType.nullability)
    }

    @Test
    fun sourceNameWithoutExplicitParamNeverBecomesCallerAuthority() {
        val capture = capture(
            sqlSegments = listOf("select * from users where id = #{id}"),
            parameters = listOf(parameter(0, "long", "id", null)),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals(
            listOf("java-annotation-unresolved-explicit-param-root"),
            contract.blockingProblems.map { it.code },
        )
        assertTrue(
            contract.blockingProblems.single().provenance?.evidence?.single() is InputEvidence.Placeholder,
        )
    }

    @Test
    fun dynamicScriptBlocksBeforeAnyPartialRequirementIsProduced() {
        val capture = capture(
            sqlSegments = listOf(
                "<script>select * from users <if test=\"id != null\">where id = #{id}</if></script>",
            ),
            parameters = listOf(parameter(0, "java.lang.Long", "id", "id")),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals(
            listOf("java-annotation-dynamic-script-input-discovery-unsupported"),
            contract.blockingProblems.map { it.code },
        )
        assertEquals(InputContractProblemKind.UNSUPPORTED, contract.blockingProblems.single().kind)
    }

    @Test
    fun mixedRawAndBoundUseOfOneLogicalAliasBlocksInsteadOfSplittingInputs() {
        val capture = capture(
            sqlSegments = listOf("select * from \${value} where name = #{value}"),
            parameters = listOf(parameter(0, "java.lang.String", "value", "value")),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        val problem = contract.blockingProblems.single()
        assertEquals(InputContractProblemKind.AMBIGUOUS, problem.kind)
        assertEquals("java-annotation-mixed-raw-bound-input", problem.code)
        assertTrue(
            problem.provenance!!.evidence.filterIsInstance<InputEvidence.Placeholder>().map { it.kind }.toSet() ==
                setOf(InputKind.BOUND, InputKind.RAW_INTERPOLATION),
        )
    }

    @Test
    fun duplicateExplicitParamAliasesBlockResolution() {
        val capture = capture(
            sqlSegments = listOf("select * from users where id = #{same}"),
            parameters = listOf(
                parameter(0, "long", "first", "same"),
                parameter(1, "long", "second", "same"),
            ),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals(1, contract.blockingProblems.size)
        assertEquals(InputContractProblemKind.AMBIGUOUS, contract.blockingProblems.single().kind)
        assertEquals("java-annotation-duplicate-explicit-param-alias", contract.blockingProblems.single().code)
    }

    @Test
    fun classifiesOnlyKnownOuterShapesAndKnownScalarTemporalTypes() {
        val parameters = listOf(
            parameter(0, "java.lang.String", "name", "name"),
            parameter(1, "java.lang.String[]", "tags", "tags"),
            parameter(2, "java.util.List<java.lang.String>", "ids", "ids"),
            parameter(3, "java.util.Collection<java.lang.Long>", "values", "values"),
            parameter(4, "java.util.Map<java.lang.String,java.lang.Object>", "attrs", "attrs"),
            parameter(5, "java.time.LocalDate", "day", "day"),
            parameter(6, "java.util.UUID", "token", "token"),
        )
        val capture = capture(
            sqlSegments = listOf(
                "select #{name}, #{tags}, #{ids}, #{values}, #{attrs}, #{day}, #{token}",
            ),
            parameters = parameters,
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(7, contract.requirements.size)
        val types = contract.requirements.associateBy { it.id.value }
        assertEquals(InputShape.SCALAR, types.getValue("java-param:0").expectedType.shape)
        assertEquals(InputScalarType.STRING, types.getValue("java-param:0").expectedType.scalarType)
        assertEquals(InputShape.ARRAY, types.getValue("java-param:1").expectedType.shape)
        assertEquals(InputShape.LIST, types.getValue("java-param:2").expectedType.shape)
        assertEquals(InputShape.LIST, types.getValue("java-param:3").expectedType.shape)
        assertEquals(InputShape.MAP, types.getValue("java-param:4").expectedType.shape)
        assertEquals(InputShape.TEMPORAL, types.getValue("java-param:5").expectedType.shape)
        assertEquals(InputScalarType.DATE, types.getValue("java-param:5").expectedType.scalarType)
        assertEquals(InputShape.SCALAR, types.getValue("java-param:6").expectedType.shape)
        assertEquals(InputScalarType.UUID, types.getValue("java-param:6").expectedType.scalarType)
        assertTrue(types.values.all { it.expectedType.javaTypeIdentity != null })
    }

    @Test
    fun customObjectShapeIsRetainedAsUnknownWithExplicitBlockingProblem() {
        val capture = capture(
            sqlSegments = listOf("select * from users where filter = #{filter}"),
            parameters = listOf(parameter(0, "com.acme.Filter", "filter", "filter")),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputShape.UNKNOWN, requirement.expectedType.shape)
        assertEquals(JavaTypeIdentity("com.acme.Filter"), requirement.expectedType.javaTypeIdentity)
        val problem = contract.blockingProblems.single()
        assertEquals(requirement.id, problem.requirementId)
        assertEquals(InputContractProblemKind.UNSUPPORTED, problem.kind)
        assertEquals("java-annotation-unproven-parameter-shape", problem.code)
    }

    @Test
    fun nestedPathOnScalarBlocksInsteadOfPretendingScalarHasProperties() {
        val capture = capture(
            sqlSegments = listOf("select * from users where id = #{id.value}"),
            parameters = listOf(parameter(0, "java.lang.Long", "id", "id")),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        assertEquals(InputShape.SCALAR, contract.requirements.single().expectedType.shape)
        assertEquals(
            listOf("java-annotation-scalar-nested-placeholder-path"),
            contract.blockingProblems.map { it.code },
        )
    }

    @Test
    fun rawInterpolationOfNonStringParameterIsExplicitlyUnsupported() {
        val capture = capture(
            sqlSegments = listOf("select * from users limit \${limit}"),
            parameters = listOf(parameter(0, "long", "limit", "limit")),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputKind.RAW_INTERPOLATION, requirement.kind)
        assertEquals(InputShape.RAW_TEXT, requirement.expectedType.shape)
        assertEquals(
            listOf("java-annotation-raw-non-string-parameter"),
            contract.blockingProblems.map { it.code },
        )
    }

    @Test
    fun complexAndMalformedPlaceholdersFailClosed() {
        val complex = capture(
            sqlSegments = listOf("select * from users where name = \${user['name']}"),
            parameters = listOf(parameter(0, "java.lang.String", "user", "user")),
        )
        val malformed = capture(
            sqlSegments = listOf("select * from users where id = #{id"),
            parameters = listOf(parameter(0, "long", "id", "id")),
        )

        val complexContract = JavaAnnotationParameterContractFactory.build(complex)
        val malformedContract = JavaAnnotationParameterContractFactory.build(malformed)

        assertTrue(complexContract.isPreparationBlocked)
        assertEquals(
            listOf("java-annotation-complex-placeholder-expression"),
            complexContract.blockingProblems.map { it.code },
        )
        assertTrue(malformedContract.isPreparationBlocked)
        assertEquals(
            listOf("java-annotation-malformed-placeholder"),
            malformedContract.blockingProblems.map { it.code },
        )
    }

    @Test
    fun canonicalIdentityAndEveryCapturedSourceRevisionFlowIntoContract() {
        val dependentFile = SourceFileId("src/main/java/com/acme/SqlConstants.java")
        val dependentRevision = SourceRevision("vfs:9")
        val capture = capture(
            sqlSegments = listOf("select * from users where id = #{id}"),
            parameters = listOf(parameter(0, "long", "id", "id")),
            extraSnapshots = listOf(
                SourceSnapshot(dependentFile, dependentRevision, "final class SqlConstants {}"),
            ),
            methodName = "findById",
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)
        val statementId = capture.sourceGraph.rootStatement.id as JavaStatementId

        assertEquals(statementId, contract.statementId)
        assertEquals("findById(long)", statementId.methodSignature.toString())
        assertEquals(
            mapOf(
                ROOT_FILE to ROOT_REVISION,
                dependentFile to dependentRevision,
            ),
            contract.sourceRevisions,
        )
        val sourceEvidence = contract.requirements.single().provenance.evidence
            .filterIsInstance<InputEvidence.MapperMethodParameter>()
            .single()
            .source
        assertEquals(ROOT_FILE, sourceEvidence.sourceFileId)
        assertEquals(ROOT_REVISION, sourceEvidence.sourceRevision)
        assertEquals(ROOT_RANGE, sourceEvidence.sourceRange)
    }

    @Test
    fun overloadedMethodsRemainBoundToDistinctCanonicalStatementIdentities() {
        val longCapture = capture(
            sqlSegments = listOf("select * from users where id = #{id}"),
            parameters = listOf(parameter(0, "long", "id", "id")),
            methodName = "find",
        )
        val stringCapture = capture(
            sqlSegments = listOf("select * from users where id = #{id}"),
            parameters = listOf(parameter(0, "java.lang.String", "id", "id")),
            methodName = "find",
        )

        val longContract = JavaAnnotationParameterContractFactory.build(longCapture)
        val stringContract = JavaAnnotationParameterContractFactory.build(stringCapture)

        assertFalse(longContract.isPreparationBlocked)
        assertFalse(stringContract.isPreparationBlocked)
        assertNotEquals(longContract.statementId, stringContract.statementId)
        assertEquals(
            MethodSignature("find", listOf(JavaTypeIdentity("long"))),
            (longContract.statementId as JavaStatementId).methodSignature,
        )
        assertEquals(
            MethodSignature("find", listOf(JavaTypeIdentity("java.lang.String"))),
            (stringContract.statementId as JavaStatementId).methodSignature,
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun returnedContractCollectionsCannotMutateFactoryResult() {
        val contract = JavaAnnotationParameterContractFactory.build(
            capture(
                sqlSegments = listOf("select #{first}, #{second}"),
                parameters = listOf(
                    parameter(0, "long", "first", "first"),
                    parameter(1, "java.lang.String", "second", "second"),
                ),
            ),
        )

        assertFalse(contract.isPreparationBlocked)
        assertEquals(2, contract.requirements.size)
        assertEquals(2, contract.aliases.size)

        (contract.requirements as MutableList<InputRequirement>).clear()
        (contract.aliases as MutableList<InputAlias>).clear()
        (contract.sourceRevisions as MutableMap<SourceFileId, SourceRevision>).clear()

        assertEquals(2, contract.requirements.size)
        assertEquals(2, contract.aliases.size)
        assertEquals(mapOf(ROOT_FILE to ROOT_REVISION), contract.sourceRevisions)
    }

    private fun capture(
        sqlSegments: List<String>,
        parameters: List<JavaMethodParameterMetadata>,
        methodName: String = "find",
        extraSnapshots: List<SourceSnapshot> = emptyList(),
    ): JavaAnnotationStatementCapture {
        val statementId = JavaStatementId(
            sourceFileId = ROOT_FILE,
            qualifiedMapperType = "com.acme.UserMapper",
            methodSignature = MethodSignature(methodName, parameters.map { it.typeIdentity }),
        )
        val statement = CapturedStatement(statementId, StatementKind.SELECT, ROOT_RANGE)
        val graph = StatementSourceGraph(
            rootStatement = statement,
            sourceSnapshots = listOf(
                SourceSnapshot(ROOT_FILE, ROOT_REVISION, "x".repeat(256)),
            ) + extraSnapshots,
            dependencies = emptyList(),
        )
        return JavaAnnotationStatementCapture(graph, sqlSegments, parameters)
    }

    private fun parameter(
        index: Int,
        type: String,
        sourceName: String?,
        alias: String?,
    ) = JavaMethodParameterMetadata(
        index = index,
        sourceName = sourceName,
        typeIdentity = JavaTypeIdentity(type),
        myBatisParamAlias = alias,
    )

    private companion object {
        val ROOT_FILE = SourceFileId("src/main/java/com/acme/UserMapper.java")
        val ROOT_REVISION = SourceRevision("document:42")
        val ROOT_RANGE = SourceRange(20, 120)
    }
}
