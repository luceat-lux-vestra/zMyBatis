package com.algorist.zMyBatis.core.preparation

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.JavaAnnotationParameterContractFactory
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationTest {
    @Test
    fun requestRejectsStatementIdentityDrift() {
        val original = capture(methodName = "find")
        val contract = JavaAnnotationParameterContractFactory.build(original)
        val environment = environment(contract)
        val drifted = capture(methodName = "other")

        val result = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(drifted),
            contract,
            environment,
        )

        assertTrue(result is PreparationRequestResult.Failed)
        result as PreparationRequestResult.Failed
        assertEquals(PreparationFailureKind.STATEMENT_ID_MISMATCH, result.failure.kind)
    }

    @Test
    fun requestRejectsSourceRevisionDrift() {
        val original = capture(revision = "revision-1")
        val contract = JavaAnnotationParameterContractFactory.build(original)
        val environment = environment(contract)
        val drifted = capture(revision = "revision-2")

        val result = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(drifted),
            contract,
            environment,
        )

        assertTrue(result is PreparationRequestResult.Failed)
        result as PreparationRequestResult.Failed
        assertEquals(PreparationFailureKind.SOURCE_REVISION_MISMATCH, result.failure.kind)
    }

    @Test
    fun requestRejectsEnvironmentBuiltForDifferentContractShape() {
        val capture = capture()
        val original = JavaAnnotationParameterContractFactory.build(capture)
        val environment = environment(original)
        val changedAliases = original.aliases.map { alias: InputAlias -> alias.copy(name = "renamed") }
        val changedContract = ParameterContract(
            statementId = original.statementId,
            requirements = original.requirements,
            aliases = changedAliases,
            internalBindings = original.internalBindings,
            blockingProblems = original.blockingProblems,
            sourceRevisions = original.sourceRevisions,
        )

        val result = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            changedContract,
            environment,
        )

        assertTrue(result is PreparationRequestResult.Failed)
        result as PreparationRequestResult.Failed
        assertEquals(PreparationFailureKind.INPUT_ENVIRONMENT_MISMATCH, result.failure.kind)
    }

    @Test
    fun blockedContractCannotBecomePreparationRequest() {
        val staticCapture = capture()
        val staticContract = JavaAnnotationParameterContractFactory.build(staticCapture)
        val environment = environment(staticContract)
        val dynamicCapture = capture(sql = "<script>select #{id}</script>")
        val blockedContract = JavaAnnotationParameterContractFactory.build(dynamicCapture)

        val result = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(dynamicCapture),
            blockedContract,
            environment,
        )

        assertTrue(result is PreparationRequestResult.Failed)
        result as PreparationRequestResult.Failed
        assertEquals(PreparationFailureKind.INPUT_CONTRACT_BLOCKED, result.failure.kind)
    }

    @Test
    fun preparedExecutionCopiesMutableCollectionsAndRejectsRawBoundConflation() {
        val capture = capture()
        val contract = JavaAnnotationParameterContractFactory.build(capture)
        val requirement = contract.requirements.single()
        val revisions = linkedMapOf(SourceFileId("mapper.java") to SourceRevision("revision-1"))
        val metadata = PreparedBindingMetadata(
            declaredJavaTypeIdentity = JavaTypeIdentity("java.lang.Long"),
            mappingJavaTypeIdentity = "java.lang.Long",
            jdbcTypeIdentity = null,
            typeHandlerIdentity = "org.apache.ibatis.type.LongTypeHandler",
            parameterMode = "IN",
            numericScale = null,
            additionalParameter = false,
        )
        val binding = PreparedBinding(
            index = 0,
            property = "id",
            requirementId = requirement.id,
            value = InputValue.IntegerValue(BigInteger.ONE),
            provenance = requirement.provenance,
            metadata = metadata,
        )

        val execution = PreparedExecution(
            statementId = capture.sourceGraph.rootStatement.id,
            statementKind = StatementKind.SELECT,
            sourceRevisions = revisions,
            sqlWithPlaceholders = "select ?",
            orderedBindings = listOf(binding),
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                "org.mybatis:mybatis",
                "3.5.19",
                "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
            ),
        )
        revisions.clear()
        assertEquals(SourceRevision("revision-1"), execution.sourceRevisions[SourceFileId("mapper.java")])

        assertThrows(IllegalArgumentException::class.java) {
            PreparedExecution(
                statementId = capture.sourceGraph.rootStatement.id,
                statementKind = StatementKind.SELECT,
                sourceRevisions = execution.sourceRevisions,
                sqlWithPlaceholders = "select ?",
                orderedBindings = listOf(binding),
                rawInterpolations = listOf(
                    PreparedRawInterpolation(
                        requirement.id,
                        requirement.provenance,
                        ExecutionInputOrigin.USER_ENTERED,
                    ),
                ),
                preparationMetadata = execution.preparationMetadata,
            )
        }
    }

    private fun environment(contract: ParameterContract): InputEnvironment {
        val requirement = contract.requirements.single()
        val result = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(
                    requirement.id,
                    InputValue.IntegerValue(BigInteger.valueOf(42)),
                    ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        )
        assertTrue(result is InputEnvironmentResult.Success)
        return (result as InputEnvironmentResult.Success).environment
    }

    private fun capture(
        methodName: String = "find",
        revision: String = "revision-1",
        sql: String = "select #{id}",
    ): JavaAnnotationStatementCapture {
        val fileId = SourceFileId("mapper.java")
        val statementId = JavaStatementId(
            fileId,
            "fixture.Mapper",
            MethodSignature(methodName, listOf(JavaTypeIdentity("java.lang.Long"))),
        )
        val snapshot = SourceSnapshot(fileId, SourceRevision(revision), "mapper-source")
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, snapshot.content.length)),
            sourceSnapshots = listOf(snapshot),
            dependencies = emptyList(),
        )
        return JavaAnnotationStatementCapture(
            graph,
            sqlSegments = listOf(sql),
            parameters = listOf(
                JavaMethodParameterMetadata(
                    index = 0,
                    sourceName = "id",
                    typeIdentity = JavaTypeIdentity("java.lang.Long"),
                    myBatisParamAlias = "id",
                ),
            ),
        )
    }
}
