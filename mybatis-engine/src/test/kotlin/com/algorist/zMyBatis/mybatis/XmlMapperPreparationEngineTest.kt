package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.SourceDependencyEdge
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperPreparationEngineTest {
    @Test
    fun staticXmlStatementPreparesThroughStockMapperParser() {
        val execution = success(prepare(single("SELECT id, name FROM users")))

        val id = execution.statementId as XmlStatementId
        assertEquals("example.Mapper", id.namespace)
        assertEquals("find", id.statementId)
        assertEquals("SELECT id, name FROM users", normalize(execution.sqlWithPlaceholders))
        assertTrue(execution.orderedBindings.isEmpty())
        assertTrue(execution.rawInterpolations.isEmpty())
        assertEquals("org.mybatis:mybatis", execution.preparationMetadata.engineIdentity)
        assertEquals("3.5.19", execution.preparationMetadata.engineVersion)
        assertEquals(
            "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
            execution.preparationMetadata.languageDriverIdentity,
        )
    }

    @Test
    fun sameFileIncludeIsResolvedByMyBatis() {
        val fixture = fixture(
            content = mapperDocument(
                "example.Mapper",
                """
                    <sql id="columns">id, name</sql>
                    <select id="find">SELECT <include refid="columns"/> FROM users</select>
                """,
            ),
        )

        assertEquals("SELECT id, name FROM users", normalize(success(prepare(fixture)).sqlWithPlaceholders))
    }

    @Test
    fun qualifiedCrossNamespaceIncludeUsesCapturedSnapshots() {
        val common = SourceSnapshot(
            SourceFileId("vfs:/a-common.xml"),
            SourceRevision("r-common"),
            mapperDocument("example.Common", "<sql id=\"columns\">id, created_at</sql>"),
        )
        val fixture = fixture(
            file = "vfs:/b-root.xml",
            content = mapperDocument(
                "example.Mapper",
                "<select id=\"find\">SELECT <include refid=\"example.Common.columns\"/> FROM users</select>",
            ),
            additionalSnapshots = listOf(common),
            dependencies = listOf(
                SourceDependencyEdge(
                    SourceFileId("vfs:/b-root.xml"),
                    SourceFileId("vfs:/a-common.xml"),
                    SourceRange(0, 0),
                ),
            ),
        )

        assertEquals("SELECT id, created_at FROM users", normalize(success(prepare(fixture)).sqlWithPlaceholders))
    }

    @Test
    fun nestedCrossNamespaceIncludeGraphUsesOnlyCapturedSnapshots() {
        val commonFile = SourceFileId("vfs:/a-common.xml")
        val auditFile = SourceFileId("vfs:/c-audit.xml")
        val commonRevision = SourceRevision("r-common")
        val auditRevision = SourceRevision("r-audit")
        val common = SourceSnapshot(
            commonFile,
            commonRevision,
            mapperDocument(
                "example.Common",
                "<sql id=\"columns\">id, <include refid=\"example.Audit.created\"/></sql>",
            ),
        )
        val audit = SourceSnapshot(
            auditFile,
            auditRevision,
            mapperDocument("example.Audit", "<sql id=\"created\">created_at</sql>"),
        )
        val rootFile = SourceFileId("vfs:/b-root.xml")
        val rootRevision = SourceRevision("r1")
        val fixture = fixture(
            file = rootFile.value,
            revision = rootRevision.value,
            content = mapperDocument(
                "example.Mapper",
                "<select id=\"find\">SELECT <include refid=\"example.Common.columns\"/> FROM users</select>",
            ),
            additionalSnapshots = listOf(common, audit),
            dependencies = listOf(
                SourceDependencyEdge(rootFile, commonFile, SourceRange(0, 0)),
                SourceDependencyEdge(commonFile, auditFile, SourceRange(0, 0)),
            ),
        )

        val execution = success(prepare(fixture))
        assertEquals("SELECT id, created_at FROM users", normalize(execution.sqlWithPlaceholders))
        assertEquals(3, execution.sourceRevisions.size)
        assertEquals(rootRevision, execution.sourceRevisions[rootFile])
        assertEquals(commonRevision, execution.sourceRevisions[commonFile])
        assertEquals(auditRevision, execution.sourceRevisions[auditFile])
    }

    @Test
    fun boundAndRawPlaceholdersFailBeforeMapperEvaluation() {
        listOf(
            "SELECT * FROM users WHERE id = #{id}",
            "SELECT * FROM ${'$'}{table}",
        ).forEach { sql ->
            assertFailure(
                prepare(single(sql)),
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                "xml-preparation-placeholder-unsupported",
            )
        }
    }

    @Test
    fun standardDynamicTagsFailBeforeGetBoundSqlEvaluation() {
        val bodies = listOf(
            "<if test=\"true\">WHERE active = 1</if>",
            "<choose><when test=\"true\">WHERE active = 1</when><otherwise>WHERE active = 0</otherwise></choose>",
            "<where>active = 1</where>",
            "<trim prefix=\"WHERE\">active = 1</trim>",
        )
        bodies.forEach { dynamic ->
            val fixture = fixture(
                content = mapperDocument(
                    "example.Mapper",
                    "<select id=\"find\">SELECT * FROM users $dynamic</select>",
                ),
            )
            assertFailure(
                prepare(fixture),
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                "xml-preparation-dynamic-sql-unsupported",
            )
        }
    }

    @Test
    fun runtimeClassLoadingSurfacesFailBeforeMyBatisParser() {
        listOf(
            "resultType=\"example.Payload\"",
            "parameterType=\"example.Payload\"",
            "lang=\"example.Driver\"",
            "databaseId=\"vendor\"",
        ).forEachIndexed { index, attribute ->
            val fixture = fixture(
                file = "vfs:/danger-$index.xml",
                content = mapperDocument(
                    "example.Mapper",
                    "<select id=\"find\" $attribute>SELECT 1</select>",
                ),
            )
            assertFailure(
                prepare(fixture),
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                "xml-preparation-runtime-class-loading-unsupported",
            )
        }

        val cacheFixture = fixture(
            content = mapperDocument(
                "example.Mapper",
                "<cache/><select id=\"find\">SELECT 1</select>",
            ),
        )
        assertFailure(
            prepare(cacheFixture),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-preparation-runtime-class-loading-unsupported",
        )
    }

    @Test
    fun externalEntityDeclarationsFailClosedButStandardMapperDoctypeWorks() {
        val xxe = """
            <!DOCTYPE mapper [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <mapper namespace="example.Mapper"><select id="find">SELECT 1</select></mapper>
        """.trimIndent()
        assertFailure(
            prepare(fixture(content = xxe)),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-preparation-external-entity-unsupported",
        )

        assertEquals("SELECT 1", normalize(success(prepare(single("SELECT 1"))).sqlWithPlaceholders))
    }

    @Test
    fun missingRootMalformedMapperAndIncompleteIncludeReturnTypedFailures() {
        val missing = fixture(
            statementId = "missing",
            content = mapperDocument("example.Mapper", "<select id=\"actual\">SELECT 1</select>"),
        )
        assertFailure(
            prepare(missing),
            PreparationFailureKind.MYBATIS_PARSE,
            "xml-preparation-root-statement-missing",
        )

        val malformed = fixture(
            content = "<mapper namespace=\"example.Mapper\"><select id=\"find\">SELECT 1",
        )
        assertFailure(
            prepare(malformed),
            PreparationFailureKind.MYBATIS_PARSE,
            "mybatis-xml-mapper-parse-failure",
        )

        val incomplete = fixture(
            content = mapperDocument(
                "example.Mapper",
                "<select id=\"find\">SELECT <include refid=\"example.Missing.columns\"/> FROM users</select>",
            ),
        )
        assertFailure(
            prepare(incomplete),
            PreparationFailureKind.MYBATIS_PARSE,
            "xml-preparation-root-statement-missing",
        )
    }

    @Test
    fun concurrentPreparationsDoNotShareConfigurationOrFragments() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 16).map { index ->
                Callable {
                    val namespace = "example.Mapper$index"
                    val fixture = fixture(
                        file = "vfs:/mapper-$index.xml",
                        revision = "r-$index",
                        namespace = namespace,
                        content = mapperDocument(
                            namespace,
                            "<sql id=\"literal\">$index</sql><select id=\"find\">SELECT <include refid=\"literal\"/></select>",
                        ),
                    )
                    normalize(success(prepare(fixture)).sqlWithPlaceholders)
                }
            }
            val results = executor.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals((0 until 16).map { "SELECT $it" }, results)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun prepare(fixture: Fixture): PreparationResult {
        val revisions = fixture.graph.sourceSnapshots.associate { it.fileId to it.revision }
        val contract = ParameterContract(
            statementId = fixture.graph.rootStatement.id,
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = revisions,
        )
        val environment = (InputEnvironment.validate(contract, emptyList()) as InputEnvironmentResult.Success).environment
        val request = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(fixture.graph),
            contract,
            environment,
        ) as PreparationRequestResult.Ready
        return XmlMapperPreparationEngine.prepare(request.request)
    }

    private fun single(sql: String): Fixture = fixture(
        content = mapperDocument("example.Mapper", "<select id=\"find\">$sql</select>"),
    )

    private fun fixture(
        file: String = "vfs:/mapper.xml",
        revision: String = "r1",
        namespace: String = "example.Mapper",
        statementId: String = "find",
        content: String,
        additionalSnapshots: List<SourceSnapshot> = emptyList(),
        dependencies: List<SourceDependencyEdge> = emptyList(),
    ): Fixture {
        val fileId = SourceFileId(file)
        val rootId = XmlStatementId(fileId, namespace, statementId)
        val rootSnapshot = SourceSnapshot(fileId, SourceRevision(revision), content)
        return Fixture(
            StatementSourceGraph(
                CapturedStatement(rootId, StatementKind.SELECT, SourceRange(0, content.length)),
                listOf(rootSnapshot) + additionalSnapshots,
                dependencies,
            ),
        )
    }

    private fun mapperDocument(namespace: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="$namespace">$body</mapper>
    """.trimIndent()

    private fun success(result: PreparationResult) = (result as PreparationResult.Success).execution

    private fun assertFailure(result: PreparationResult, kind: PreparationFailureKind, code: String) {
        val failure = (result as PreparationResult.Failed).failure
        assertEquals(kind, failure.kind)
        assertEquals(code, failure.code)
    }

    private fun normalize(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private data class Fixture(val graph: StatementSourceGraph)
}
