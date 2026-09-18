package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
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

class MyBatisPreparationBoundaryXmlTest {
    @Test
    fun sourceAgnosticBoundaryDispatchesXmlSource() {
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
        val contract = ParameterContract(
            statementId,
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environment = (InputEnvironment.validate(contract, emptyList()) as InputEnvironmentResult.Success).environment
        val request = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(graph),
            contract,
            environment,
        ) as PreparationRequestResult.Ready

        val execution = (MyBatisPreparationBoundary.prepare(request.request) as PreparationResult.Success).execution
        assertEquals("SELECT 1", execution.sqlWithPlaceholders.trim())
        assertEquals(statementId, execution.statementId)
    }
}
