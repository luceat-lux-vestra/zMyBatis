package com.algorist.zMyBatis.core.input

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
import org.junit.Test

class XmlSingleMapParameterObjectContractTest {
    @Test
    fun soleTypedStringKeyMapUsesExplicitPropertyLookupProvenance() {
        val contract = build(
            graph("select * from users where id = #{id}"),
            listOf(parameter(0, "java.util.Map<java.lang.String, java.lang.Long>", "payload", null)),
        )

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputRequirementId("xml-java-param:0"), requirement.id)
        assertEquals(InputKind.BOUND, requirement.kind)
        assertEquals(InputShape.MAP, requirement.expectedType.shape)
        assertEquals(
            JavaTypeIdentity("java.util.Map<java.lang.String, java.lang.Long>"),
            requirement.expectedType.javaTypeIdentity,
        )

        val alias = contract.aliases.single()
        assertEquals("id", alias.name)
        assertEquals(InputAliasKind.PARAMETER_OBJECT, alias.kind)
        assertEquals(requirement.id, alias.requirementId)

        val property = requirement.provenance.evidence
            .filterIsInstance<InputEvidence.ParameterObjectProperty>()
            .single()
        assertEquals(0, property.parameterIndex)
        assertEquals("id", property.mappingProperty)
        assertEquals(
            "mybatis-3.5.19-default-parameter-handler-meta-object-property",
            property.ruleId,
        )
        assertTrue(
            requirement.provenance.evidence.none {
                it is InputEvidence.ParameterObjectFallback
            },
        )
        assertTrue(
            requirement.provenance.evidence.any {
                it is InputEvidence.MapperMethodParameter &&
                    it.index == 0 &&
                    it.sourceName == "payload"
            },
        )
    }

    @Test
    fun distinctMapPropertiesShareOneRequirementAndKeepPlaceholderOrder() {
        val contract = build(
            graph("select * from users where id = #{id} or parent_id = #{parentId}"),
            listOf(parameter(0, "java.util.Map<java.lang.String,java.lang.Long>", "payload", null)),
        )

        assertFalse(contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        assertEquals(listOf("id", "parentId"), contract.aliases.map { it.name })
        assertTrue(contract.aliases.all { it.kind == InputAliasKind.PARAMETER_OBJECT })
        assertEquals(
            listOf("id", "parentId"),
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .map { it.expression },
        )
        assertEquals(
            setOf("id", "parentId"),
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.ParameterObjectProperty>()
                .mapTo(linkedSetOf()) { it.mappingProperty },
        )
    }

    @Test
    fun temporalMapValueTypeIsAdmitted() {
        val contract = build(
            graph("select * from audit where created_on = #{createdOn}"),
            listOf(
                parameter(
                    0,
                    "java.util.Map<java.lang.String,java.time.LocalDate>",
                    "payload",
                    null,
                ),
            ),
        )

        assertFalse(contract.isPreparationBlocked)
        assertEquals(InputShape.MAP, contract.requirements.single().expectedType.shape)
        assertEquals(InputAliasKind.PARAMETER_OBJECT, contract.aliases.single().kind)
        assertTrue(
            contract.requirements.single().provenance.evidence
                .any { it is InputEvidence.ParameterObjectProperty },
        )
    }

    @Test
    fun rawNonStringAndUnsupportedMapTypesRemainBlocked() {
        val unsupportedTypes = listOf(
            "java.util.Map",
            "java.util.Map<java.lang.Long,java.lang.Long>",
            "java.util.Map<java.lang.String,java.util.UUID>",
            "java.util.Map<java.lang.String,java.util.List<java.lang.Long>>",
            "java.util.Map<java.lang.String,fixture.CustomValue>",
        )

        unsupportedTypes.forEach { type ->
            val contract = build(
                graph("select * from users where id = #{id}"),
                listOf(parameter(0, type, "payload", null)),
            )

            assertTrue("expected blocked type: $type", contract.isPreparationBlocked)
            assertTrue(contract.requirements.isEmpty())
            assertTrue(contract.aliases.none { it.kind == InputAliasKind.PARAMETER_OBJECT })
            assertEquals(
                "xml-caller-input-authority-unproven",
                contract.blockingProblems.single().code,
            )
        }
    }

    @Test
    fun explicitParamAndMultipleParametersDoNotUseMapParameterObjectPropertyLookup() {
        val explicit = build(
            graph("select * from users where id = #{payload}"),
            listOf(
                parameter(
                    0,
                    "java.util.Map<java.lang.String,java.lang.Long>",
                    "payload",
                    "payload",
                ),
            ),
        )
        assertFalse(explicit.isPreparationBlocked)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, explicit.aliases.single().kind)
        assertTrue(
            explicit.requirements.single().provenance.evidence
                .none { it is InputEvidence.ParameterObjectProperty },
        )

        val multi = build(
            graph("select * from users where id = #{id}"),
            listOf(
                parameter(
                    0,
                    "java.util.Map<java.lang.String,java.lang.Long>",
                    "payload",
                    null,
                ),
                parameter(1, "long", "fallback", null),
            ),
        )
        assertTrue(multi.isPreparationBlocked)
        assertTrue(multi.aliases.none { it.kind == InputAliasKind.PARAMETER_OBJECT })
    }

    @Test
    fun rawInterpolationAndNestedPropertyDoNotGainMapPropertyAuthority() {
        val raw = build(
            graph("select * from " + rawToken("table")),
            listOf(
                parameter(
                    0,
                    "java.util.Map<java.lang.String,java.lang.String>",
                    "payload",
                    null,
                ),
            ),
        )
        assertTrue(raw.isPreparationBlocked)
        assertTrue(raw.aliases.none { it.kind == InputAliasKind.PARAMETER_OBJECT })

        val nested = build(
            graph("select * from users where id = #{payload.id}"),
            listOf(
                parameter(
                    0,
                    "java.util.Map<java.lang.String,java.lang.Long>",
                    "payload",
                    null,
                ),
            ),
        )
        assertTrue(nested.isPreparationBlocked)
        assertTrue(nested.aliases.none { it.kind == InputAliasKind.PARAMETER_OBJECT })
    }

    @Test
    fun scalarParameterObjectFallbackRemainsDistinctFromMapPropertyLookup() {
        val contract = build(
            graph("select * from users where id = #{arbitrary}"),
            listOf(parameter(0, "long", "id", null)),
        )

        assertFalse(contract.isPreparationBlocked)
        assertTrue(
            contract.requirements.single().provenance.evidence
                .any { it is InputEvidence.ParameterObjectFallback },
        )
        assertTrue(
            contract.requirements.single().provenance.evidence
                .none { it is InputEvidence.ParameterObjectProperty },
        )
    }

    private fun build(
        graph: StatementSourceGraph,
        parameters: List<JavaMethodParameterMetadata>,
    ): ParameterContract = XmlMapperMethodParameterContractFactory.build(
        graph,
        XmlMapperMethodCapture(
            statementId = graph.rootStatement.id as XmlStatementId,
            mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(256)),
            methodSourceRange = SourceRange(20, 120),
            parameters = parameters,
        ),
    )

    private fun graph(body: String): StatementSourceGraph {
        val statementId = XmlStatementId(XML_FILE, "example.Mapper", "find")
        val declaration = "<select id=\"find\">$body</select>"
        val xml = "<mapper namespace=\"example.Mapper\">$declaration</mapper>"
        val start = xml.indexOf("<select id=\"find\">")
        val end = xml.indexOf('>', start) + 1
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                statementId,
                StatementKind.SELECT,
                SourceRange(start, end),
            ),
            sourceSnapshots = listOf(SourceSnapshot(XML_FILE, XML_REVISION, xml)),
            dependencies = emptyList(),
        )
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

    private fun rawToken(name: String): String = "$" + "{$name}"

    private companion object {
        val XML_FILE = SourceFileId("vfs:/mapper.xml")
        val XML_REVISION = SourceRevision("xml-r1")
        val JAVA_FILE = SourceFileId("vfs:/Mapper.java")
        val JAVA_REVISION = SourceRevision("java-r1")
    }
}
