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
    fun soleUnannotatedTypedStringMapUsesPropertyLookupProvenance() {
        val graph = graph("select * from users where id = #{id}")
        val contract = build(
            graph,
            listOf(parameter(0, "java.util.Map<java.lang.String,java.lang.Long>", "payload", null)),
        )

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputRequirementId("xml-java-param:0"), requirement.id)
        assertEquals(InputKind.BOUND, requirement.kind)
        assertEquals(InputShape.MAP, requirement.expectedType.shape)
        assertEquals(
            JavaTypeIdentity("java.util.Map<java.lang.String,java.lang.Long>"),
            requirement.expectedType.javaTypeIdentity,
        )

        val alias = contract.aliases.single()
        assertEquals("id", alias.name)
        assertEquals(InputAliasKind.PARAMETER_OBJECT, alias.kind)
        assertEquals(requirement.id, alias.requirementId)

        val lookup = alias.provenance.evidence
            .filterIsInstance<InputEvidence.ParameterObjectPropertyLookup>()
            .single()
        assertEquals(0, lookup.parameterIndex)
        assertEquals("id", lookup.mappingProperty)
        assertEquals(
            "mybatis-3.5.19-default-parameter-handler-meta-object-property-lookup",
            lookup.ruleId,
        )
        assertTrue(
            alias.provenance.evidence.none { it is InputEvidence.ParameterObjectFallback },
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
    fun distinctPropertiesShareOneMapRequirementAndRetainOccurrences() {
        val graph = graph("select * from users where id = #{id} or parent_id = #{parentId}")
        val contract = build(
            graph,
            listOf(parameter(0, "java.util.Map<java.lang.String,java.lang.Long>", "payload", null)),
        )

        assertFalse(contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        assertEquals(listOf("id", "parentId"), contract.aliases.map { it.name })
        assertTrue(contract.aliases.all { it.kind == InputAliasKind.PARAMETER_OBJECT })
        assertEquals(
            setOf("id", "parentId"),
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.ParameterObjectPropertyLookup>()
                .mapTo(linkedSetOf()) { it.mappingProperty },
        )
        assertEquals(
            listOf("id", "parentId"),
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .map { it.expression },
        )
    }

    @Test
    fun supportedTemporalMapValueTypeIsAdmitted() {
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
                .any { it is InputEvidence.ParameterObjectPropertyLookup },
        )
    }

    @Test
    fun unprovenMapTypesDoNotGainPropertyLookupAuthority() {
        val types = listOf(
            "java.util.Map",
            "java.util.Map<java.lang.Long,java.lang.Long>",
            "java.util.Map<java.lang.String,java.util.UUID>",
            "java.util.Map<java.lang.String,java.util.List<java.lang.Long>>",
            "java.util.Map<java.lang.String,com.acme.CustomValue>",
        )

        types.forEach { type ->
            val contract = build(
                graph("select * from users where id = #{id}"),
                listOf(parameter(0, type, "payload", null)),
            )

            assertTrue(type, contract.isPreparationBlocked)
            assertTrue(
                type,
                contract.aliases.none { alias ->
                    alias.provenance.evidence.any {
                        it is InputEvidence.ParameterObjectPropertyLookup
                    }
                },
            )
            assertEquals(
                type,
                "xml-caller-input-authority-unproven",
                contract.blockingProblems.single().code,
            )
        }
    }

    @Test
    fun explicitParamAndMultipleParametersStayOnExistingAuthorityPaths() {
        val explicit = build(
            graph("select * from users where payload = #{payload}"),
            listOf(
                parameter(
                    0,
                    "java.util.Map<java.lang.String,java.lang.Long>",
                    "sourcePayload",
                    "payload",
                ),
            ),
        )
        assertFalse(explicit.isPreparationBlocked)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, explicit.aliases.single().kind)
        assertTrue(
            explicit.requirements.single().provenance.evidence.none {
                it is InputEvidence.ParameterObjectPropertyLookup
            },
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
                parameter(1, "long", "limit", null),
            ),
        )
        assertTrue(multi.isPreparationBlocked)
        assertTrue(
            multi.aliases.none { alias ->
                alias.provenance.evidence.any {
                    it is InputEvidence.ParameterObjectPropertyLookup
                }
            },
        )
    }

    @Test
    fun rawAndNestedPropertiesDoNotGainMapPropertyLookupAuthority() {
        val raw = build(
            graph("select " + rawToken("id") + " from users"),
            listOf(
                parameter(
                    0,
                    "java.util.Map<java.lang.String,java.lang.Long>",
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
