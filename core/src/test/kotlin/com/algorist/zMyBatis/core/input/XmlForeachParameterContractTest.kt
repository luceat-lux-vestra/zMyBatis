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

class XmlForeachParameterContractTest {
    @Test
    fun explicitListAliasProducesForeachCollectionAndLocalProvenance() {
        val graph = graph(
            """
            SELECT * FROM users WHERE id IN
            <foreach collection="ids" item="id" index="idx" open="(" separator="," close=")">
              #{id}
            </foreach>
            """.trimIndent(),
        )
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputKind.BOUND, requirement.kind)
        assertEquals(InputShape.LIST, requirement.expectedType.shape)
        assertEquals(JavaTypeIdentity("java.util.List<java.lang.Long>"), requirement.expectedType.javaTypeIdentity)
        assertEquals("ids", contract.aliases.single().name)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, contract.aliases.single().kind)
        assertEquals(
            listOf("ids"),
            requirement.provenance.evidence
                .filterIsInstance<InputEvidence.ForeachCollection>()
                .map { it.expression },
        )
        assertTrue(
            requirement.provenance.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .none { it.expression == "id" || it.expression == "idx" },
        )

        assertEquals(
            listOf("id" to InternalBindingKind.FOREACH_ITEM, "idx" to InternalBindingKind.FOREACH_INDEX),
            contract.internalBindings.map { it.name to it.kind },
        )
        val itemEvidence = contract.internalBindings[0].provenance.evidence
            .filterIsInstance<InputEvidence.ForeachLocal>()
            .single()
        assertEquals("id", itemEvidence.name)
        assertEquals(ForeachLocalRole.ITEM, itemEvidence.role)
        val indexEvidence = contract.internalBindings[1].provenance.evidence
            .filterIsInstance<InputEvidence.ForeachLocal>()
            .single()
        assertEquals("idx", indexEvidence.name)
        assertEquals(ForeachLocalRole.INDEX, indexEvidence.role)
        assertEquals(XML_REVISION, contract.sourceRevisions.getValue(XML_FILE))
        assertEquals(JAVA_REVISION, contract.sourceRevisions.getValue(JAVA_FILE))
    }

    @Test
    fun singleCollectionShortcutsRetainAuthoritativeShape() {
        data class Case(
            val collection: String,
            val type: String,
            val aliasKind: InputAliasKind,
            val shape: InputShape,
        )

        val cases = listOf(
            Case("list", "java.util.List<java.lang.Long>", InputAliasKind.LIST, InputShape.LIST),
            Case("collection", "java.util.Collection<java.lang.Long>", InputAliasKind.COLLECTION, InputShape.LIST),
            Case("array", "long[]", InputAliasKind.ARRAY, InputShape.ARRAY),
        )

        cases.forEach { tc ->
            val graph = graph(
                """
                SELECT * FROM users WHERE id IN
                <foreach collection="${tc.collection}" item="id" open="(" separator="," close=")">
                  #{id}
                </foreach>
                """.trimIndent(),
            )
            val mapper = mapper(graph, listOf(parameter(0, tc.type, "ids", null)))

            val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

            assertFalse(tc.collection, contract.isPreparationBlocked)
            assertEquals(tc.shape, contract.requirements.single().expectedType.shape)
            assertEquals(tc.collection, contract.aliases.single().name)
            assertEquals(tc.aliasKind, contract.aliases.single().kind)
            assertEquals(
                tc.collection,
                contract.requirements.single().provenance.evidence
                    .filterIsInstance<InputEvidence.ForeachCollection>()
                    .single()
                    .expression,
            )
        }
    }

    @Test
    fun provenGenericParamAliasCanOwnForeachCollection() {
        val graph = graph(
            """
            SELECT * FROM users WHERE id IN
            <foreach collection="param2" item="id" separator=",">#{id}</foreach>
            """.trimIndent(),
        )
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "java.lang.String", "status", "status"),
                parameter(1, "java.util.List<java.lang.Long>", "ids", "ids"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals("xml-java-param:1", requirement.id.value)
        assertEquals(InputShape.LIST, requirement.expectedType.shape)
        assertEquals("param2", contract.aliases.single().name)
        assertEquals(InputAliasKind.GENERIC_PARAM, contract.aliases.single().kind)
        assertTrue(
            requirement.provenance.evidence
                .filterIsInstance<InputEvidence.GeneratedAlias>()
                .any {
                    it.parameterIndex == 1 &&
                        it.alias == "param2" &&
                        it.ruleId == "mybatis-3.5.19-param-name-resolver-generic"
                },
        )
        assertTrue(
            requirement.provenance.evidence
                .filterIsInstance<InputEvidence.ForeachCollection>()
                .any { it.expression == "param2" },
        )
    }

    @Test
    fun staticCallerPlaceholderOutsideForeachRemainsIndependent() {
        val graph = graph(
            """
            SELECT * FROM users
            WHERE status = #{status}
              AND id IN
              <foreach collection="ids" item="id" separator=",">#{id}</foreach>
            """.trimIndent(),
        )
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "java.lang.String", "status", "status"),
                parameter(1, "java.util.List<java.lang.Long>", "ids", "ids"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(listOf("status", "ids"), contract.aliases.map { it.name })
        assertEquals(
            listOf("xml-java-param:0", "xml-java-param:1"),
            contract.requirements.map { it.id.value },
        )
        val status = contract.requirements[0]
        assertTrue(
            status.provenance.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .any { it.expression == "status" && it.kind == InputKind.BOUND },
        )
        val ids = contract.requirements[1]
        assertTrue(
            ids.provenance.evidence
                .filterIsInstance<InputEvidence.ForeachCollection>()
                .any { it.expression == "ids" },
        )
        assertTrue(contract.aliases.none { it.name == "id" })
    }

    @Test
    fun invalidForeachDeclarationsRemainFailClosed() {
        val cases = listOf(
            """<foreach collection="payload.ids" item="id">#{id}</foreach>""" to
                "xml-foreach-input-provenance-unsupported",
            """<foreach collection="ids">#{id}</foreach>""" to
                "xml-foreach-input-provenance-unsupported",
            """<foreach collection="ids" item="id" nullable="true">#{id}</foreach>""" to
                "xml-foreach-input-provenance-unsupported",
            """<foreach collection="ids" item="id"><if test="true">#{id}</if></foreach>""" to
                "xml-nested-element-input-discovery-unsupported",
            """
            <foreach collection="ids" item="id">
              <foreach collection="other" item="nested">#{nested}</foreach>
            </foreach>
            """.trimIndent() to "xml-nested-element-input-discovery-unsupported",
        )

        cases.forEach { (body, expectedCode) ->
            val contract = XmlStatementParameterContractFactory.build(graph(body))

            assertTrue(expectedCode, contract.isPreparationBlocked)
            assertEquals(expectedCode, contract.blockingProblems.single().code)
            assertTrue(contract.requirements.isEmpty())
            assertTrue(contract.aliases.isEmpty())
        }
    }

    @Test
    fun foreachLocalShadowingAndUnsupportedLocalExpressionsFailClosed() {
        val shadowed = XmlStatementParameterContractFactory.build(
            graph(
                """
                SELECT #{item}
                <foreach collection="ids" item="item">#{item}</foreach>
                """.trimIndent(),
            ),
        )
        assertEquals(
            "xml-foreach-local-shadowing-unsupported",
            shadowed.blockingProblems.single().code,
        )

        val rawLocal = XmlStatementParameterContractFactory.build(
            graph(
                """<foreach collection="ids" item="item">${raw("item")}</foreach>""",
            ),
        )
        assertEquals(
            "xml-foreach-raw-local-unsupported",
            rawLocal.blockingProblems.single().code,
        )

        val propertyLocal = XmlStatementParameterContractFactory.build(
            graph(
                """<foreach collection="ids" item="item">#{item.id}</foreach>""",
            ),
        )
        assertEquals(
            "xml-foreach-local-expression-unsupported",
            propertyLocal.blockingProblems.single().code,
        )
    }

    @Test
    fun foreachLocalsCannotShadowProvenMapperAliases() {
        val explicitGraph = graph(
            """<foreach collection="ids" item="status">#{status}</foreach>""",
        )
        val explicitMapper = mapper(
            explicitGraph,
            listOf(
                parameter(0, "java.util.List<java.lang.Long>", "ids", "ids"),
                parameter(1, "java.lang.String", "status", "status"),
            ),
        )

        val explicit = XmlMapperMethodParameterContractFactory.build(explicitGraph, explicitMapper)

        assertTrue(explicit.isPreparationBlocked)
        assertTrue(
            explicit.blockingProblems.any {
                it.code == "xml-foreach-local-shadowing-unsupported"
            },
        )

        val shortcutGraph = graph(
            """<foreach collection="list" item="collection">#{collection}</foreach>""",
        )
        val shortcutMapper = mapper(
            shortcutGraph,
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", null)),
        )

        val shortcut = XmlMapperMethodParameterContractFactory.build(shortcutGraph, shortcutMapper)

        assertTrue(shortcut.isPreparationBlocked)
        assertTrue(
            shortcut.blockingProblems.any {
                it.code == "xml-foreach-local-shadowing-unsupported"
            },
        )
    }

    @Test
    fun foreachCollectionMustHaveCollectionShape() {
        val graph = graph(
            """<foreach collection="ids" item="id">#{id}</foreach>""",
        )
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.lang.String", "ids", "ids")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertEquals(InputShape.SCALAR, contract.requirements.single().expectedType.shape)
        assertTrue(
            contract.blockingProblems.any {
                it.code == "xml-foreach-collection-shape-unsupported" &&
                    it.requirementId == contract.requirements.single().id
            },
        )
    }

    private fun graph(body: String): StatementSourceGraph {
        val statementId = XmlStatementId(XML_FILE, "com.acme.UserMapper", "find")
        val declaration = "<select id=\"find\">$body</select>"
        val xml = "<mapper namespace=\"com.acme.UserMapper\">$declaration</mapper>"
        val start = xml.indexOf("<select id=\"find\">")
        val end = xml.indexOf('>', start) + 1
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = statementId,
                kind = StatementKind.SELECT,
                sourceRange = SourceRange(start, end),
            ),
            sourceSnapshots = listOf(SourceSnapshot(XML_FILE, XML_REVISION, xml)),
            dependencies = emptyList(),
        )
    }

    private fun mapper(
        graph: StatementSourceGraph,
        parameters: List<JavaMethodParameterMetadata>,
    ): XmlMapperMethodCapture = XmlMapperMethodCapture(
        statementId = graph.rootStatement.id as XmlStatementId,
        mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(256)),
        methodSourceRange = JAVA_METHOD_RANGE,
        parameters = parameters,
    )

    private fun parameter(
        index: Int,
        type: String,
        sourceName: String?,
        alias: String?,
    ): JavaMethodParameterMetadata = JavaMethodParameterMetadata(
        index = index,
        sourceName = sourceName,
        typeIdentity = JavaTypeIdentity(type),
        myBatisParamAlias = alias,
    )

    private fun raw(name: String): String = 36.toChar().toString() + "{" + name + "}"

    private companion object {
        val XML_FILE = SourceFileId("src/main/resources/com/acme/UserMapper.xml")
        val XML_REVISION = SourceRevision("document:foreach-321")
        val JAVA_FILE = SourceFileId("src/main/java/com/acme/UserMapper.java")
        val JAVA_REVISION = SourceRevision("document:foreach-321-java")
        val JAVA_METHOD_RANGE = SourceRange(20, 120)
    }
}
