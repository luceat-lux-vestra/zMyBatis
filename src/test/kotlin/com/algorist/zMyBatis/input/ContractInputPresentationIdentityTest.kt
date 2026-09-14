package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractInputPresentationIdentityTest {
    @Test
    fun `presentation preserves authoritative statement identity when no inputs are required`() {
        val file = SourceFileId("src/example/Mapper.java")
        val statement = JavaStatementId(
            sourceFileId = file,
            qualifiedMapperType = "example.Mapper",
            methodSignature = MethodSignature(
                name = "findAll",
                parameterTypeIdentities = emptyList(),
            ),
        )
        val contract = ParameterContract(
            statementId = statement,
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(file to SourceRevision("revision-1")),
        )

        val presentation = ContractInputPresentationFactory.create(contract)

        assertEquals(contract.statementId, presentation.statementId)
        assertTrue(presentation.fields.isEmpty())
        assertTrue(presentation.canSubmit)
    }
}
