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
        val result = prepare(
            root = mapper(
                file = "vfs:/mapper.xml",
                revision = "r1",
                namespace = "example.Mapper",
                statementId = "find",
                statementKind = StatementKind.SELECT,
                body = "SELECT id, name FROM users",
            ),
        )

        val execution = success(result)
        assertEquals("example.Mapper", (execution.statementId as XmlStatementId).namespace)
        assertEquals("find", (execution.statementId as XmlStatementId).statementId)
        assertEquals("SELECT id, name FROM users", normalize(execution.sqlWithPlaceholders))
        assertTrue(execution.orderedBindings.isEmpty())
        assertTrue(execution.rawInterpolations.isEmpty())
        assertEquals("org.mybatis:mybatis", execution.preparationMetadata.engineIdentity)
        assertEquals("3.5.19", execution.preparationMetadata.engineVersion)
        assertEquals("org.apache.ibatis.scripting.xmltags.XMLLanguageDriver", execution.preparationMetadata.languageDriverIdentity)
    }

    @Test
    fun sameFileIncludeIsResolvedByMyBatis() {
        val content = mapperDocument(
            "example.Mapper",
            """
                <sql id="columns">id, name</sql>
                <select id="find">SELECT <include refid="columns"/> FROM users</select>
            """,
        )
        val root = fixture(
            file = "vfs:/mapper.xml",
            revision = "r1",
            namespace = "example.Mapper",
            statementId = "find",
            kind = StatementKind.SELECT,
            content = content,
        )

        assertEquals(
            "SELECT id, name FROM users",
            normalize(success(prepare(root)).sqlWithPlaceholders),
        )
    }

    @Test
    fun qualifiedCrossNamespaceIncludeUsesOnlyCapturedSnapshots() {
        val commonContent = mapperDocument(
            "example.Common",
            "<sql id=\"columns\">id, created_at</sql>",
        )
        val rootContent = mapperDocument(
            "example.Mapper",
            "<select id=\"find\">SELECT <include refid=\"example.Common.columns\"/> FROM users</select>",
        )
        val common = SourceSnapshot(
            SourceFileId("vfs:/a-common.xml"),
            SourceRevision("r-common"),
            commonContent,
        )
        val root = fixture(
            file = "vfs:/b-root.xml",
            revision = "r-root",
            namespace = "example.Mapper",
            statementId = "find",
            kind = StatementKind.SELECT,
            content = rootContent,
            additionalSnapshots = listOf(common),
            dependencies = listOf(
                SourceDependencyEdge(
                    SourceFileId("vfs:/b-root.xml"),
                    SourceFileId("vfs:/a-common.xml"),
                    SourceRange(0, 0),
                ),
            ),
        )

        assertEquals(
            "SELECT id, created_at FROM users",
            normalize(success(prepare(root)).sqlWithPlaceholders),
        )
    }

    @Test
    fun boundPlaceholderIsRejectedBeforeMapperParsingCanCreateABinding() {
        assertFailureCode(
            prepare(
                mapper(
                    "vfs:/mapper.xml",
                    "r1",
                    "example.Mapper",
                    "find",
                    StatementKind.SELECT,
                    "SELECT * FROM users WHERE id = #{id}",
                ),
            ),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-preparation-placeholder-unsupported",
        )
    }

    @Test
    fun rawInterpolationIsRejectedBeforeDynamicEvaluation() {
        assertFailureCode(
            prepare(
                mapper(
                    "vfs:/mapper.xml",
                    "r1",
                    "example.Mapper",
                    "find",
                    StatementKind.SELECT,
                    "SELECT * FROM ${'$'}{table}",
                ),
            ),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-preparation-placeholder-unsupported",
        )
    }

    @Test
    fun dynamicTagIsRejectedBeforeGetBoundSqlEvaluation() {
        val content = mapperDocument(
            "example.Mapper",
            "<select id=\"find\">SELECT * FROM users <if test=\"true\">WHERE active = 1</if></select>",
        )
        assertFailureCode(
            prepare(
                fixture(
                    "vfs:/mapper.xml",
                    "r1",
                    "example.Mapper",
                    "find",
                    StatementKind.SELECT,
                    content,
                ),
            ),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-preparation-dynamic-sql-unsupported",
        )
    }

    @Test
    fun applicationClassLoadingAttributesAreRejectedBeforeMyBatisParser() {
        val dangerous = listOf(
            "resultType=\"example.Payload\"",
            "parameterType=\"example.Payload\"",
            "lang=\"example.Driver\"",
            "databaseId=\"vendor\"",
        )
        dangerous.forEach { attribute ->
            val content = mapperDocument(
                "example.Mapper",
                "<select id=\"find\" $attribute>SELECT 1</select>",
            )
            assertFailureCode(
                prepare(
                    fixture(
                        "vfs:/$attribute.xml",
                        "r1",
                        "example.Mapper",
                        "find",
                        StatementKind.SELECT,
                        content,
                    ),
                ),
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                "xml-preparation-runtime-class-loading-unsupported",
            )
        }
    }

    @Test
    fun mapperRuntimeConstructionElementsAreRejectedBeforeMyBatisParser() {
        val content = mapperDocument(
            "example.Mapper",
            """
                <cache/>
                <select id="find">SELECT 1</select>
            """,
        )
        assertFailureCode(
            prepare(
                fixture(
                    "vfs:/mapper.xml",
                    "r1",
                    "example.Mapper",
                    "find",
                    StatementKind.SELECT,
                    content,
                ),
            ),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-preparation-runtime-class-loading-unsupported",
        )
    }

    @Test
    fun nonMyBatisDoctypeAndEntityDeclarationsFailClosed() {
        val xxe = """
            <!DOCTYPE mapper [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <mapper namespace="example.Mapper">
                <select id="find">SELECT 1</select>
            </mapper>
        """.trimIndent()
        assertFailureCode(
            prepare(
                fixture(
                    "vfs:/mapper.xml",
                    "r1",
                    "example.Mapper",
                    "find",
                    StatementKind.SELECT,
                    xxe,
                ),
            ),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-preparation-external-entity-unsupported",
        )
    }

    @Test
    fun standardMyBatisMapperDoctypeRemainsLocalAndSupported() {
        val content = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="example.Mapper">
                <select id="find">SELECT 1</select>
            </mapper>
        """.trimIndent()

        assertEquals(
            "SELECT 1",
            normalize(
                success(
                    prepare(
                        fixture(
                            "vfs:/mapper.xml",
                            "r1",
                            "example.Mapper",
                            "find",
                            StatementKind.SELECT,
                            content,
                        ),
                    ),
                ).sqlWithPlaceholders,
            ),
        )
    }

    @Test
    fun missingCanonicalRootStatementFailsTyped() {
        val content = mapperDocument(
            "example.Mapper",
            "<select id=\"actual\">SELECT 1</select>",
        )
        assertFailureCode(
            prepare(
                fixture(
                    "vfs:/mapper.xml",
                    "r1",
                    "example.Mapper",
                    "missing",
                    StatementKind.SELECT,
                    content,
                ),
            ),
            PreparationFailureKind.MYBATIS_PARSE,
            "xml-preparation-root-statement-missing",
        )
    }

    @Test
    fun malformedMapperReturnsTypedParseFailure() {
        val content = "<mapper namespace=\"example.Mapper\"><select id=\"find\">SELECT 1"
        assertFailureCode(
            prepare(
                fixture(
                    "vfs:/mapper.xml",
                    "r1",
                    "example.Mapper",
                    "find",
                    StatementKind.SELECT,
                    content,
                ),
            ),
            PreparationFailureKind.MYBATIS_PARSE,
            "mybatis-xml-mapper-parse-failure",
        )
    }

    @Test
    fun concurrentXmlPreparationsDoNotShareConfigurationOrFragments() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 24).map { index ->
                Callable {
                    val namespace = "example.Mapper$index"
                    val content = mapperDocument(
                        namespace,
                        """
                            <sql id="literal">$index</sql>
                            <select id="find">SELECT <include refid="literal"/></select>
                        """,
                    )
                    normalize(
                        success(
                            prepare(
                                fixture(
                                    "vfs:/mapper-$index.xml",
                                    "r-$index",
                                    namespace,
                                    "find",
                                    StatementKind.SELECT,
                                    content,
                                ),
                            ),
                        ).sqlWithPlaceholders,
                    )
                }
            }
            val results = executor.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals((0 until 24).map { "SELECT $it" }, results)
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

    private fun mapper(
        file: String,
        revision: String,
        namespace: String,
        statementId: String,
        statementKind: StatementKind,
        body: String,
    ): Fixture = fixture(
        file,
        revision,
        namespace,
        statementId,
        statementKind,
        mapperDocument(namespace, "<select id=\"$statementId\">$body</select>"),
    )

    private fun fixture(
        file: String,
        revision: String,
        namespace: String,
        statementId: String,
        kind: StatementKind,
        content: String,
        additionalSnapshots: List<SourceSnapshot> = emptyList(),
        dependencies: List<SourceDependencyEdge> = emptyList(),
    ): Fixture {
        val fileId = SourceFileId(file)
        val rootId = XmlStatementId(fileId, namespace, statementId)
        val rootSnapshot = SourceSnapshot(fileId, SourceRevision(revision), content)
        return Fixture(
            StatementSourceGraph(
                CapturedStatement(rootId, kind, SourceRange(0, content.length)),
                listOf(rootSnapshot) + additionalSnapshots,
                dependencies,
            ),
        )
    }

    private fun mapperDocument(namespace: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="$namespace">
            $body
        </mapper>
    """.trimIndent()

    private fun success(result: PreparationResult) =
        (result as PreparationResult.Success).execution

    private fun assertFailureCode(
        result: PreparationResult,
        kind: PreparationFailureKind,
        code: String,
    ) {
        val failure = (result as PreparationResult.Failed).failure
        assertEquals(kind, failure.kind)
        assertEquals(code, failure.code)
    }

    private fun normalize(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private data class Fixture(val graph: StatementSourceGraph)
}
