package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputNullability
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Test

class XmlMapperPreparationInputBoundaryTest {
    @Test
    fun unprovenSourceParameterNameAliasRemainsFailClosed() {
        val fixture = fixture()
        val requirementId = InputRequirementId("caller-id")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.Placeholder(
                    kind = InputKind.BOUND,
                    expression = "id",
                    source = SourceEvidence(
                        fixture.fileId,
                        fixture.revision,
                        SourceRange(0, fixture.content.length),
                    ),
                ),
            ),
        )
        val contract = ParameterContract(
            statementId = fixture.statementId,
            requirements = listOf(
                InputRequirement(
                    id = requirementId,
                    kind = InputKind.BOUND,
                    expectedType = ExpectedInputType(
                        shape = InputShape.SCALAR,
                        scalarType = InputScalarType.STRING,
                        nullability = InputNullability.NON_NULL,
                    ),
                    requiredness = InputRequiredness.REQUIRED,
                    provenance = provenance,
                ),
            ),
            aliases = listOf(
                InputAlias("id", requirementId, InputAliasKind.SOURCE_PARAMETER_NAME, provenance),
            ),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fixture.fileId to fixture.revision),
        )
        val environment = (
            InputEnvironment.validate(
                contract,
                listOf(
                    ProvidedInput(
                        requirementId,
                        InputValue.Text("42"),
                        ExecutionInputOrigin.USER_ENTERED,
                    ),
                ),
            ) as InputEnvironmentResult.Success
            ).environment
        assertEquals(InputAliasKind.SOURCE_PARAMETER_NAME, contract.aliases.single().kind)

        val request = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(fixture.graph),
            contract,
            environment,
        ) as PreparationRequestResult.Ready

        val failure = (XmlMapperPreparationEngine.prepare(request.request) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
        assertEquals("xml-preparation-alias-kind-unsupported", failure.code)
    }

    @Test
    fun statementIdentityDriftIsRejectedBeforeXmlEngine() {
        val fixture = fixture()
        val driftedId = XmlStatementId(fixture.fileId, "example.Other", "find")
        val contract = emptyContract(
            driftedId,
            mapOf(fixture.fileId to fixture.revision),
        )
        val environment = (InputEnvironment.validate(contract, emptyList()) as InputEnvironmentResult.Success).environment

        val failure = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(fixture.graph),
            contract,
            environment,
        ) as PreparationRequestResult.Failed
        assertEquals(PreparationFailureKind.STATEMENT_ID_MISMATCH, failure.failure.kind)
        assertEquals("preparation-statement-identity-drift", failure.failure.code)
    }

    @Test
    fun sourceRevisionDriftIsRejectedBeforeXmlEngine() {
        val fixture = fixture()
        val driftedRevision = SourceRevision("stale-r0")
        val contract = emptyContract(
            fixture.statementId,
            mapOf(fixture.fileId to driftedRevision),
        )
        val environment = (InputEnvironment.validate(contract, emptyList()) as InputEnvironmentResult.Success).environment

        val failure = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(fixture.graph),
            contract,
            environment,
        ) as PreparationRequestResult.Failed
        assertEquals(PreparationFailureKind.SOURCE_REVISION_MISMATCH, failure.failure.kind)
        assertEquals("preparation-source-revision-drift", failure.failure.code)
    }

    @Test
    fun mapperAuthorityRevisionMustBeOwnedByPreparationSource() {
        val fixture = fixture()
        val javaFile = SourceFileId("vfs:/Mapper.java")
        val javaRevision = SourceRevision("java-r1")
        val javaSnapshot = SourceSnapshot(
            javaFile,
            javaRevision,
            "interface Mapper { Object find(long id); }",
        )
        val revisions = mapOf(
            fixture.fileId to fixture.revision,
            javaFile to javaRevision,
        )
        val contract = emptyContract(fixture.statementId, revisions)
        val environment = (
            InputEnvironment.validate(contract, emptyList()) as InputEnvironmentResult.Success
            ).environment

        val missingAuthority = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(fixture.graph),
            contract,
            environment,
        ) as PreparationRequestResult.Failed
        assertEquals(PreparationFailureKind.SOURCE_REVISION_MISMATCH, missingAuthority.failure.kind)
        assertEquals("preparation-source-revision-drift", missingAuthority.failure.code)

        val authoritySnapshots = mutableListOf(javaSnapshot)
        val source = XmlMapperPreparationSource(
            fixture.graph,
            additionalAuthoritySnapshots = authoritySnapshots,
        )
        authoritySnapshots.clear()

        val ready = MyBatisPreparationRequest.create(
            source,
            contract,
            environment,
        ) as PreparationRequestResult.Ready

        assertEquals(revisions, ready.request.sourceRevisions)
        assertEquals(listOf(javaSnapshot), source.additionalAuthoritySnapshots)
    }

    private fun emptyContract(
        statementId: XmlStatementId,
        revisions: Map<SourceFileId, SourceRevision>,
    ) = ParameterContract(
        statementId = statementId,
        requirements = emptyList(),
        aliases = emptyList(),
        internalBindings = emptyList(),
        blockingProblems = emptyList(),
        sourceRevisions = revisions,
    )

    private fun fixture(): Fixture {
        val fileId = SourceFileId("vfs:/mapper.xml")
        val revision = SourceRevision("r1")
        val content = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="example.Mapper">
                <select id="find">SELECT 1</select>
            </mapper>
        """.trimIndent()
        val statementId = XmlStatementId(fileId, "example.Mapper", "find")
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, content.length)),
            listOf(SourceSnapshot(fileId, revision, content)),
            emptyList(),
        )
        return Fixture(fileId, revision, statementId, content, graph)
    }

    private data class Fixture(
        val fileId: SourceFileId,
        val revision: SourceRevision,
        val statementId: XmlStatementId,
        val content: String,
        val graph: StatementSourceGraph,
    )
}
