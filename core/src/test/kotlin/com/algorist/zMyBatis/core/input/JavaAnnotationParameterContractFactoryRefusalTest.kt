package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceDependencyEdge
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaAnnotationParameterContractFactoryRefusalTest {
    @Test
    fun dependencyBackedSqlBlocksUntilSegmentLevelProvenanceExists() {
        val dependentFile = SourceFileId("src/main/java/com/acme/SqlConstants.java")
        val capture = capture(
            sqlSegments = listOf("select * from users where id = #{id}"),
            parameters = listOf(parameter(0, "long", "id", "id")),
            extraSnapshots = listOf(
                SourceSnapshot(dependentFile, SourceRevision("vfs:9"), "x".repeat(64)),
            ),
            dependencies = listOf(
                SourceDependencyEdge(ROOT_FILE, dependentFile, SourceRange(30, 40)),
            ),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals(
            listOf("java-annotation-dependent-segment-provenance-unsupported"),
            contract.blockingProblems.map { it.code },
        )
        assertEquals(InputContractProblemKind.UNSUPPORTED, contract.blockingProblems.single().kind)
        assertEquals(
            setOf(ROOT_FILE, dependentFile),
            contract.sourceRevisions.keys,
        )
    }

    @Test
    fun reservedMyBatisContextRootsNeverBecomeCallerInputsEvenWithExplicitParamAlias() {
        listOf("_parameter", "_databaseId").forEach { reservedAlias ->
            val capture = capture(
                sqlSegments = listOf("select #{$reservedAlias}"),
                parameters = listOf(parameter(0, "java.lang.String", "value", reservedAlias)),
            )

            val contract = JavaAnnotationParameterContractFactory.build(capture)

            assertTrue(contract.isPreparationBlocked)
            assertTrue(contract.requirements.isEmpty())
            assertTrue(contract.aliases.isEmpty())
            assertEquals(
                listOf("java-annotation-reserved-internal-alias"),
                contract.blockingProblems.map { it.code },
            )
            assertEquals(InputContractProblemKind.UNSUPPORTED, contract.blockingProblems.single().kind)
        }
    }

    @Test
    fun escapedPlaceholderTextIsNotReinterpretedAsExecutableCallerInput() {
        val capture = capture(
            sqlSegments = listOf("select \\#{id}"),
            parameters = listOf(parameter(0, "long", "id", "id")),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals(
            listOf("java-annotation-escaped-placeholder-unsupported"),
            contract.blockingProblems.map { it.code },
        )
        val evidence = contract.blockingProblems.single().provenance!!.evidence.single()
        assertTrue(evidence is InputEvidence.Placeholder)
        assertEquals("id", (evidence as InputEvidence.Placeholder).expression)
    }

    @Test
    fun unrelatedCapturedSnapshotWithoutDependencyDoesNotInventAProvenanceFailure() {
        val unrelatedFile = SourceFileId("src/main/java/com/acme/Unrelated.java")
        val capture = capture(
            sqlSegments = listOf("select * from users where id = #{id}"),
            parameters = listOf(parameter(0, "long", "id", "id")),
            extraSnapshots = listOf(
                SourceSnapshot(unrelatedFile, SourceRevision("vfs:11"), "x".repeat(64)),
            ),
        )

        val contract = JavaAnnotationParameterContractFactory.build(capture)

        assertTrue(!contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        assertEquals(
            setOf(ROOT_FILE, unrelatedFile),
            contract.sourceRevisions.keys,
        )
    }

    private fun capture(
        sqlSegments: List<String>,
        parameters: List<JavaMethodParameterMetadata>,
        extraSnapshots: List<SourceSnapshot> = emptyList(),
        dependencies: List<SourceDependencyEdge> = emptyList(),
    ): JavaAnnotationStatementCapture {
        val statementId = JavaStatementId(
            sourceFileId = ROOT_FILE,
            qualifiedMapperType = "com.acme.UserMapper",
            methodSignature = MethodSignature("find", parameters.map { it.typeIdentity }),
        )
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(statementId, StatementKind.SELECT, ROOT_RANGE),
            sourceSnapshots = listOf(
                SourceSnapshot(ROOT_FILE, ROOT_REVISION, "x".repeat(256)),
            ) + extraSnapshots,
            dependencies = dependencies,
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
