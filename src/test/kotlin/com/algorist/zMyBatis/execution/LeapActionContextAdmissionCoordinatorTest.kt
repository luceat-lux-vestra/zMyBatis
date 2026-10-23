package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementVariant
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementVariantsCapture
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.algorist.zMyBatis.source.XmlMapperDiscoveryResult
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureFailure
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureResult
import com.algorist.zMyBatis.source.XmlMapperSourceDiscovery
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapActionContextAdmissionCoordinatorTest {
    @Test
    fun targetDescriptorRequiresStableDatasourceAndExplicitSchema() {
        assertNull(
            leapExecutionTargetDescriptor(
                stableDataSourceId = null,
                dataSourceDisplayName = "db",
                schemaName = "public",
            ),
        )
        assertNull(
            leapExecutionTargetDescriptor(
                stableDataSourceId = "stable",
                dataSourceDisplayName = "db",
                schemaName = null,
            ),
        )
        assertNull(
            leapExecutionTargetDescriptor(
                stableDataSourceId = "stable",
                dataSourceDisplayName = "db",
                schemaName = " ",
            ),
        )

        val descriptor = leapExecutionTargetDescriptor(
            stableDataSourceId = " stable-id ",
            dataSourceDisplayName = "db",
            schemaName = "public",
        )!!
        assertEquals("stable-id", descriptor.targetId.dataSourceId.value)
        assertEquals("public", descriptor.targetId.schema.value)
    }

    @Test
    fun javaSourceSelectionUsesTargetDerivedDatabaseId() {
        val fileId = SourceFileId("vfs:/fixture/Mapper.java")
        val snapshot = SourceSnapshot(
            fileId = fileId,
            revision = SourceRevision("document:445"),
            content = "interface Mapper {}",
        )
        val statementId = JavaStatementId(
            sourceFileId = fileId,
            qualifiedMapperType = "fixture.Mapper",
            methodSignature = MethodSignature("find", emptyList()),
        )
        val variants = JavaAnnotationStatementVariantsCapture(
            sourceGraph = StatementSourceGraph(
                rootStatement = CapturedStatement(
                    id = statementId,
                    kind = StatementKind.SELECT,
                    sourceRange = SourceRange(0, snapshot.content.length),
                ),
                sourceSnapshots = listOf(snapshot),
                dependencies = emptyList(),
            ),
            variants = listOf(
                JavaAnnotationStatementVariant(null, StatementKind.SELECT, listOf("select 1")),
                JavaAnnotationStatementVariant("postgresql", StatementKind.SELECT, listOf("select 2")),
            ),
            parameters = emptyList(),
        )

        val selected = selectLeapPreparationSource(
            evidence = LeapActionSourceEvidence.Java(variants),
            effectiveDatabaseId = MyBatisDatabaseId("postgresql"),
            captureXmlMapperMethod = { error("XML capture must not run for Java") },
        )

        val source = (selected as LeapPreparationSourceSelectionResult.Ready).source
            as PreparationSource.JavaAnnotation
        assertEquals(listOf("select 2"), source.capture.sqlSegments)
        assertEquals(MyBatisDatabaseId("postgresql"), source.capture.effectiveDatabaseId)
    }

    @Test
    fun xmlSourceSelectionUsesTargetDatabaseIdAndAllowsMissingMapperInterface() {
        val content = """
            <mapper namespace="fixture.Mapper">
              <select id="find">select 1</select>
              <select id="find" databaseId="postgresql">select 2</select>
            </mapper>
        """.trimIndent()
        val snapshot = SourceSnapshot(
            fileId = SourceFileId("vfs:/fixture/Mapper.xml"),
            revision = SourceRevision("document:445"),
            content = content,
        )
        val discovery = (XmlMapperSourceDiscovery.discover(snapshot) as XmlMapperDiscoveryResult.Discovered).mapper
        val statementId = XmlStatementId(snapshot.fileId, "fixture.Mapper", "find")

        val selected = selectLeapPreparationSource(
            evidence = LeapActionSourceEvidence.Xml(
                statementId = statementId,
                snapshots = listOf(snapshot),
                discoveries = listOf(discovery),
            ),
            effectiveDatabaseId = MyBatisDatabaseId("postgresql"),
            captureXmlMapperMethod = {
                XmlMapperMethodCaptureResult.Failed(
                    XmlMapperMethodCaptureFailure.UNRESOLVED_MAPPER_TYPE,
                )
            },
        )

        val source = (selected as LeapPreparationSourceSelectionResult.Ready).source
            as XmlMapperPreparationSource
        assertEquals(MyBatisDatabaseId("postgresql"), source.effectiveDatabaseId)
        assertNull(source.mapperMethod)
        assertEquals(statementId, source.sourceGraph.rootStatement.id)
    }

    @Test
    fun xmlSourceSelectionResolvesQualifiedCrossDocumentInclude() {
        val rootContent = """
            <mapper namespace="fixture.Mapper">
              <select id="find">
                SELECT * FROM users
                <include refid="shared.SharedFragments.where"/>
              </select>
            </mapper>
        """.trimIndent()
        val sharedContent = """
            <mapper namespace="shared.SharedFragments">
              <sql id="where">WHERE active = 1</sql>
            </mapper>
        """.trimIndent()
        val rootSnapshot = SourceSnapshot(
            fileId = SourceFileId("vfs:/fixture/Mapper.xml"),
            revision = SourceRevision("document:445-root"),
            content = rootContent,
        )
        val sharedSnapshot = SourceSnapshot(
            fileId = SourceFileId("vfs:/fixture/SharedFragments.xml"),
            revision = SourceRevision("vfs:445-shared"),
            content = sharedContent,
        )
        val rootDiscovery =
            (XmlMapperSourceDiscovery.discover(rootSnapshot) as XmlMapperDiscoveryResult.Discovered).mapper
        val sharedDiscovery =
            (XmlMapperSourceDiscovery.discover(sharedSnapshot) as XmlMapperDiscoveryResult.Discovered).mapper
        val statementId = XmlStatementId(rootSnapshot.fileId, "fixture.Mapper", "find")

        val selected = selectLeapPreparationSource(
            evidence = LeapActionSourceEvidence.Xml(
                statementId = statementId,
                snapshots = listOf(rootSnapshot, sharedSnapshot),
                discoveries = listOf(rootDiscovery, sharedDiscovery),
            ),
            effectiveDatabaseId = null,
            captureXmlMapperMethod = {
                XmlMapperMethodCaptureResult.Failed(
                    XmlMapperMethodCaptureFailure.UNRESOLVED_MAPPER_TYPE,
                )
            },
        )

        val source = (selected as LeapPreparationSourceSelectionResult.Ready).source
            as XmlMapperPreparationSource
        assertEquals(2, source.sourceGraph.sourceSnapshots.size)
        assertEquals(1, source.sourceGraph.dependencies.size)
        assertEquals(
            sharedSnapshot.fileId,
            source.sourceGraph.dependencies.single().requiredFileId,
        )
    }

    @Test
    fun admissionRemainsNonShippingAndHasNoLegacyFallbackOrDefaultSchema() {
        val coordinator = sourceText(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapActionContextAdmissionCoordinator.kt",
        )
        val action = sourceText(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt",
        )

        assertTrue(coordinator.contains("JavaAnnotationSourceCaptureAdapter.capture"))
        assertTrue(coordinator.contains("XmlActionSourceCaptureAdapter.capture"))
        assertTrue(coordinator.contains("XmlProjectMapperSourceCaptureAdapter.capture"))
        assertTrue(coordinator.contains("executeOnPooledThread"))
        assertTrue(coordinator.contains("LeapExecutionInvocationCoordinator"))
        assertTrue(coordinator.contains("ExecutionTargetDescriptorStore"))
        assertFalse(coordinator.contains("Use Default Schema"))
        assertFalse(coordinator.contains("MyBatisEvaluator"))
        assertFalse(coordinator.contains("StoredExecutionTargetBridge"))
        assertFalse(action.contains("LeapActionContextAdmissionCoordinator"))
    }

    private fun sourceText(relativePath: String): String =
        Files.readString(repositoryRoot().resolve(relativePath))

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("Repository root not found")
        }
    }
}
