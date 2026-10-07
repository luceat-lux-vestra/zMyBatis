package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.apache.ibatis.ognl.OgnlParserConstants
import org.junit.Test

class XmlBooleanIfProducerBoundaryTest {
    @Test
    fun maintainedStockOgnlKeywordsCannotAcquireCallerAuthority() {
        val keywords = OgnlParserConstants.tokenImage
            .map { it.removeSurrounding("\"") }
            .filter { Regex("[a-zA-Z]+").matches(it) }
        assertTrue(keywords.isNotEmpty())
        for (keyword in keywords) {
            val file = SourceFileId("file:///mapper.xml")
            val statement = XmlStatementId(file, "example.Mapper", "find")
            val xml = "<mapper namespace=\"example.Mapper\"><select id=\"find\">SELECT 1 <if test=\"$keyword\">WHERE 1 = 1</if></select></mapper>"
            val graph = StatementSourceGraph(
                CapturedStatement(statement, StatementKind.SELECT, SourceRange(0, xml.length)),
                listOf(SourceSnapshot(file, SourceRevision("xml-r1"), xml)),
                emptyList(),
            )
            val mapper = XmlMapperMethodCapture(
                statement,
                SourceSnapshot(SourceFileId("file:///Mapper.java"), SourceRevision("java-r1"), "x".repeat(100)),
                SourceRange(0, 100),
                listOf(JavaMethodParameterMetadata(0, "enabled", JavaTypeIdentity("boolean"), keyword)),
            )
            val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)
            assertTrue(keyword, contract.isPreparationBlocked)
            assertTrue(keyword, contract.requirements.isEmpty())
        }
    }

    @Test
    fun provenConditionDoesNotAuthorizeXmlDynamicRuntimePreparation() {
        val file = SourceFileId("file:///mapper.xml")
        val statement = XmlStatementId(file, "example.Mapper", "find")
        val xml = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="example.Mapper">
                <select id="find">SELECT 1 <if test="enabled">WHERE 1 = 1</if></select>
            </mapper>
        """.trimIndent()
        val graph = StatementSourceGraph(
            CapturedStatement(statement, StatementKind.SELECT, SourceRange(0, xml.length)),
            listOf(SourceSnapshot(file, SourceRevision("xml-r1"), xml)),
            emptyList(),
        )
        val mapper = XmlMapperMethodCapture(
            statement,
            SourceSnapshot(SourceFileId("file:///Mapper.java"), SourceRevision("java-r1"), "x".repeat(100)),
            SourceRange(0, 100),
            listOf(JavaMethodParameterMetadata(0, "enabled", JavaTypeIdentity("boolean"), "enabled")),
        )
        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)
        assertFalse(contract.isPreparationBlocked)
        val missing = InputEnvironment.validate(contract, emptyList()) as InputEnvironmentResult.Failure
        assertEquals(InputEnvironmentFailureKind.MISSING_REQUIRED_INPUT, missing.failures.single().kind)

        for (enabled in listOf(true, false)) {
            val environment = (InputEnvironment.validate(
                contract,
                listOf(ProvidedInput(contract.requirements.single().id, InputValue.BooleanValue(enabled), ExecutionInputOrigin.USER_ENTERED)),
            ) as InputEnvironmentResult.Success).environment
            val request = (MyBatisPreparationRequest.create(
                XmlMapperPreparationSource(graph, additionalAuthoritySnapshots = listOf(mapper.mapperSource)),
                contract,
                environment,
            ) as PreparationRequestResult.Ready).request

            val failure = (XmlMapperPreparationEngine.prepare(request) as PreparationResult.Failed).failure
            assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
            assertEquals("xml-preparation-dynamic-sql-unsupported", failure.code)
        }
    }
}
