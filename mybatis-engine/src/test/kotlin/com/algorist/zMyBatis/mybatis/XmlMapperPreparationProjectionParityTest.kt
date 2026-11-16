package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperPreparationProjectionParityTest {
    @Test
    fun ordinaryResultMetadataIsExcludedWithoutChangingBoundSql() {
        val fixture = resourceFixture(
            rootResource = "parity/ordinary-full.xml",
            namespace = "parity.OrdinaryFull",
            statementId = "withResultMap",
        )
        assertProjectionParity(fixture, mapOf("id" to 42L))

        val projected = ready(fixture).documents.single { it.rootStatementDocument }.content
        assertFalse(projected.contains("<resultMap"))
        assertFalse(projected.contains("resultMap="))
        assertFalse(projected.contains("resultType="))
        assertTrue(projected.contains("parameterType=\"map\""))
    }

    @Test
    fun resultTypeIsPreparationIrrelevantButBuiltInParameterTypeRemains() {
        val fixture = resourceFixture(
            rootResource = "parity/ordinary-full.xml",
            namespace = "parity.OrdinaryFull",
            statementId = "withResultType",
        )
        assertProjectionParity(fixture, mapOf("name" to "pro"))

        val projected = ready(fixture).documents.single { it.rootStatementDocument }.content
        assertFalse(projected.contains("resultType="))
        assertTrue(projected.contains("parameterType=\"map\""))
    }

    @Test
    fun historicalLongParameterTypeAndResultMapMatchFullStockMapper() {
        val fixture = resourceFixture(
            rootResource = "parity/historical-product.xml",
            namespace = "com.san.sprinboot.mybatis.h2.spring_boot_myBatis_h2.mapper.ProductMapper",
            statementId = "findById",
        )
        assertProjectionParity(
            fixture = fixture,
            parameterObject = 7L,
            configure = {
                typeAliasRegistry.registerAlias(
                    "Product",
                    com.algorist.zMyBatis.mybatis.parity.HistoricalProduct::class.java,
                )
            },
        )

        val projected = ready(fixture).documents.single { it.rootStatementDocument }.content
        assertTrue(projected.contains("parameterType=\"long\""))
        assertFalse(projected.contains("resultMap="))
    }

    @Test
    fun sameCrossAndNestedIncludesProjectOnlyReachableFragments() {
        val same = resourceFixture(
            rootResource = "parity/include-root.xml",
            namespace = "parity.IncludeMapper",
            statementId = "sameNamespace",
            additionalResources = listOf("parity/include-shared.xml"),
        )
        assertProjectionParity(same, null)

        val cross = resourceFixture(
            rootResource = "parity/include-root.xml",
            namespace = "parity.IncludeMapper",
            statementId = "crossNamespace",
            additionalResources = listOf("parity/include-shared.xml"),
        )
        assertProjectionParity(cross, mapOf("active" to true))

        val nested = resourceFixture(
            rootResource = "parity/include-root.xml",
            namespace = "parity.IncludeMapper",
            statementId = "nested",
            additionalResources = listOf("parity/include-shared.xml"),
        )
        assertProjectionParity(nested, mapOf("tenantId" to 77L))

        val nestedProjection = ready(nested)
        val fragmentText = nestedProjection.documents
            .filterNot { it.rootStatementDocument }
            .joinToString("\n") { it.content }
        assertTrue(fragmentText.contains("id=\"nestedOuter\""))
        assertTrue(fragmentText.contains("id=\"nestedInner\""))
        assertFalse(fragmentText.contains("id=\"columns\""))
        assertFalse(fragmentText.contains("id=\"activePredicate\""))
    }

    @Test
    fun databaseIdSelectsTheSameFragmentVariantAsFullStockMyBatis() {
        val fixture = resourceFixture(
            rootResource = "parity/include-root.xml",
            namespace = "parity.IncludeMapper",
            statementId = "databaseId",
            additionalResources = listOf("parity/include-shared.xml"),
        )
        val databaseId = MyBatisDatabaseId("PostgreSQL")
        assertProjectionParity(fixture, null, databaseId)

        val projected = ready(fixture, databaseId).documents
            .filterNot { it.rootStatementDocument }
            .joinToString("\n") { it.content }
        assertTrue(projected.contains("vendor = 'postgres'"))
        assertFalse(projected.contains("vendor = 'default'"))
    }

    @Test
    fun standardDynamicNodesArePreservedStructurallyByProjection() {
        for (
            statement in listOf(
                "ifCase" to mapOf("enabled" to true, "id" to 7L),
                "chooseCase" to mapOf("mode" to "name", "name" to "pro", "id" to 1L),
                "foreachCase" to mapOf("ids" to listOf(7L, 9L)),
                "whereCase" to mapOf("name" to "pro", "active" to true),
                "trimCase" to mapOf("name" to "pro", "active" to true),
                "bindCase" to mapOf("name" to "pro"),
                "chooseForeach" to mapOf("ids" to listOf(2L, 4L)),
                "bindIf" to mapOf("name" to "customer"),
                "foreachWhere" to mapOf("ids" to listOf(10L, 20L)),
            )
        ) {
            val fixture = resourceFixture(
                rootResource = "parity/dynamic.xml",
                namespace = "parity.DynamicMapper",
                statementId = statement.first,
            )
            assertProjectionParity(fixture, statement.second)
        }
    }

    @Test
    fun unrelatedRuntimeDeclarationsDoNotEnterSelectedStatementProjection() {
        val xml = mapperDocument(
            "example.Mapper",
            """
                <cache type="example.CustomCache"/>
                <resultMap id="PayloadMap" type="example.Payload">
                    <id property="id" column="id"/>
                </resultMap>
                <select id="other" resultType="example.Payload" lang="example.Driver">SELECT 2</select>
                <select id="find">SELECT 1</select>
            """.trimIndent(),
        )
        val fixture = inlineFixture(xml)
        val projection = ready(fixture)

        assertEquals(1, projection.documents.size)
        val projected = projection.documents.single().content
        assertFalse(projected.contains("CustomCache"))
        assertFalse(projected.contains("PayloadMap"))
        assertFalse(projected.contains("example.Payload"))
        assertFalse(projected.contains("example.Driver"))
        assertTrue(projected.contains("SELECT 1"))
    }

    @Test
    fun selectedApplicationParameterTypeRemainsTypedRefusalInP1() {
        val fixture = inlineFixture(
            mapperDocument(
                "example.Mapper",
                """<select id="find" parameterType="example.Payload">SELECT #{value}</select>""",
            ),
        )
        val result = XmlMapperPreparationProjection.project(
            fixture.source,
            fixture.statementId,
            null,
        )
        assertTrue(result is XmlMapperPreparationProjection.Result.Failed)
        result as XmlMapperPreparationProjection.Result.Failed
        assertEquals(
            "xml-preparation-runtime-class-loading-unsupported",
            result.failure.code,
        )
    }

    @Test
    fun externalEntityRemainsFailClosedBeforeProjectionParse() {
        val fixture = inlineFixture(
            """
                <!DOCTYPE mapper [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <mapper namespace="example.Mapper">
                    <select id="find">SELECT 1</select>
                </mapper>
            """.trimIndent(),
        )
        val result = XmlMapperPreparationProjection.project(
            fixture.source,
            fixture.statementId,
            null,
        )
        assertTrue(result is XmlMapperPreparationProjection.Result.Failed)
        result as XmlMapperPreparationProjection.Result.Failed
        assertEquals("xml-preparation-external-entity-unsupported", result.failure.code)
    }

    private fun assertProjectionParity(
        fixture: Fixture,
        parameterObject: Any?,
        databaseId: MyBatisDatabaseId? = null,
        configure: Configuration.() -> Unit = {},
    ) {
        val full = Configuration().apply {
            this.databaseId = databaseId?.value
            configure()
        }
        fixture.snapshots
            .sortedBy { if (it.fileId == fixture.statementId.sourceFileId) 1 else 0 }
            .forEach { snapshot ->
                parseMapper(full, snapshot.content, "full:${snapshot.fileId.value}")
            }

        val projection = ready(fixture, databaseId)
        val projected = Configuration().apply {
            this.databaseId = databaseId?.value
        }
        projection.documents.forEachIndexed { index, document ->
            parseMapper(projected, document.content, "projected:$index")
        }

        val canonical = "${fixture.statementId.namespace}.${fixture.statementId.statementId}"
        val expected = snapshot(full, canonical, parameterObject)
        val actual = snapshot(projected, canonical, parameterObject)

        assertEquals(expected.sql, actual.sql)
        assertEquals(expected.bindings, actual.bindings)
    }

    private fun ready(
        fixture: Fixture,
        databaseId: MyBatisDatabaseId? = null,
    ): XmlMapperPreparationProjection.Result.Ready {
        val result = XmlMapperPreparationProjection.project(
            fixture.source,
            fixture.statementId,
            databaseId,
        )
        assertTrue("expected projection success, got $result", result is XmlMapperPreparationProjection.Result.Ready)
        return result as XmlMapperPreparationProjection.Result.Ready
    }

    private fun snapshot(
        configuration: Configuration,
        statementId: String,
        parameterObject: Any?,
    ): Snapshot {
        val boundSql = configuration.getMappedStatement(statementId).getBoundSql(parameterObject)
        return Snapshot(
            sql = boundSql.sql,
            bindings = boundSql.parameterMappings.map { mapping ->
                val metadata = stockEffectiveParameterMappingMetadata(
                    configuration,
                    boundSql,
                    parameterObject,
                    mapping,
                )
                Binding(
                    property = mapping.property,
                    runtimeValue = comparableValue(metadata.runtimeValue),
                    javaType = metadata.javaTypeIdentity,
                    jdbcType = metadata.jdbcTypeIdentity,
                    typeHandler = metadata.typeHandlerIdentity,
                    mode = metadata.parameterMode,
                    numericScale = metadata.numericScale,
                    mappingValue = mapping.hasValue(),
                    additionalParameter = boundSql.hasAdditionalParameter(mapping.property),
                )
            },
        )
    }

    private fun comparableValue(value: Any?): Any? = when (value) {
        is Array<*> -> value.toList()
        is LongArray -> value.toList()
        is IntArray -> value.toList()
        else -> value
    }

    private fun parseMapper(
        configuration: Configuration,
        content: String,
        resource: String,
    ) {
        ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)).use { input ->
            XMLMapperBuilder(input, configuration, resource, configuration.sqlFragments).parse()
        }
    }

    private fun resourceFixture(
        rootResource: String,
        namespace: String,
        statementId: String,
        additionalResources: List<String> = emptyList(),
        kind: StatementKind = StatementKind.SELECT,
    ): Fixture {
        val rootFileId = SourceFileId(rootResource)
        val root = SourceSnapshot(rootFileId, SourceRevision("r:$rootResource"), resourceText(rootResource))
        val additional = additionalResources.map { resource ->
            SourceSnapshot(SourceFileId(resource), SourceRevision("r:$resource"), resourceText(resource))
        }
        val statement = XmlStatementId(rootFileId, namespace, statementId)
        val graph = StatementSourceGraph(
            CapturedStatement(statement, kind, SourceRange(0, root.content.length)),
            listOf(root) + additional,
            emptyList(),
        )
        return Fixture(
            statementId = statement,
            snapshots = listOf(root) + additional,
            source = XmlMapperPreparationSource(graph),
        )
    }

    private fun inlineFixture(
        content: String,
        namespace: String = "example.Mapper",
        statementId: String = "find",
        kind: StatementKind = StatementKind.SELECT,
    ): Fixture {
        val fileId = SourceFileId("inline.xml")
        val snapshot = SourceSnapshot(fileId, SourceRevision("r-inline"), content)
        val statement = XmlStatementId(fileId, namespace, statementId)
        val graph = StatementSourceGraph(
            CapturedStatement(statement, kind, SourceRange(0, content.length)),
            listOf(snapshot),
            emptyList(),
        )
        return Fixture(statement, listOf(snapshot), XmlMapperPreparationSource(graph))
    }

    private fun resourceText(name: String): String =
        resource(name).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun resource(name: String): InputStream =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing parity fixture: $name"
        }

    private fun mapperDocument(namespace: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="$namespace">
        $body
        </mapper>
    """.trimIndent()

    private data class Fixture(
        val statementId: XmlStatementId,
        val snapshots: List<SourceSnapshot>,
        val source: XmlMapperPreparationSource,
    )

    private data class Snapshot(
        val sql: String,
        val bindings: List<Binding>,
    )

    private data class Binding(
        val property: String,
        val runtimeValue: Any?,
        val javaType: String?,
        val jdbcType: String?,
        val typeHandler: String?,
        val mode: String?,
        val numericScale: Int?,
        val mappingValue: Boolean,
        val additionalParameter: Boolean,
    )
}
