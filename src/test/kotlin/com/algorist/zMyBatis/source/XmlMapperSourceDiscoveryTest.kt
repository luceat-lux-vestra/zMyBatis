package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperSourceDiscoveryTest {
    @Test
    fun standardMyBatisDoctypeParsesWithoutExternalResolution() {
        val xml = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="fixture.Mapper">
              <select id="find">SELECT 1</select>
            </mapper>
        """.trimIndent()

        val discovery = discovered(xml)

        assertEquals("fixture.Mapper", discovery.namespace)
        assertEquals(listOf("find"), discovery.statements.map { it.id })
        assertEquals(StatementKind.SELECT, discovery.statements.single().kind)
        assertEquals(SourceFileId("vfs:file:///fixture/Mapper.xml"), discovery.sourceFileId)
        assertEquals(SourceRevision("document:42"), discovery.sourceRevision)
    }

    @Test
    fun discoversStatementsFragmentsAndRepeatedIncludeOccurrencesInSourceOrder() {
        val xml = """
            <mapper namespace="fixture.Mapper">
              <select id="find">
                SELECT <include refid="baseColumns"/>
                <!-- <include refid="commentGhost"/> -->
                <![CDATA[ <include refid="cdataGhost"/> ]]>
                FROM users
                <include refid="baseColumns"/>
                <include refid="shared.auditColumns"/>
              </select>
              <sql id="baseColumns">
                id, name
                <include refid="shared.tenantColumn"/>
              </sql>
              <insert id="create">INSERT INTO users DEFAULT VALUES</insert>
            </mapper>
        """.trimIndent()

        val discovery = discovered(xml)

        assertEquals(listOf("find", "create"), discovery.statements.map { it.id })
        assertEquals(listOf(StatementKind.SELECT, StatementKind.INSERT), discovery.statements.map { it.kind })
        assertEquals(listOf("baseColumns"), discovery.fragments.map { it.id })
        assertEquals(
            listOf("baseColumns", "baseColumns", "shared.auditColumns", "shared.tenantColumn"),
            discovery.includes.map { it.refid },
        )
        assertEquals(
            listOf(
                XmlMapperDeclarationRef.Statement("find", StatementKind.SELECT),
                XmlMapperDeclarationRef.Statement("find", StatementKind.SELECT),
                XmlMapperDeclarationRef.Statement("find", StatementKind.SELECT),
                XmlMapperDeclarationRef.Fragment("baseColumns"),
            ),
            discovery.includes.map { it.owner },
        )

        val ranges = (
            discovery.statements.map { it.sourceRange } +
                discovery.fragments.map { it.sourceRange } +
                discovery.includes.map { it.sourceRange }
            )
        assertTrue(ranges.all { it.startOffset >= 0 && it.endOffsetExclusive <= xml.length })
        assertEquals(
            listOf(
                "<include refid=\"baseColumns\"/>",
                "<include refid=\"baseColumns\"/>",
                "<include refid=\"shared.auditColumns\"/>",
                "<include refid=\"shared.tenantColumn\"/>",
            ),
            discovery.includes.map { xml.substring(it.sourceRange.startOffset, it.sourceRange.endOffsetExclusive) },
        )
    }

    @Test
    fun multilineAndQuotedAttributesMapToExactStartTagRanges() {
        val xml = """
            <mapper namespace="fixture.Mapper">
              <select
                  id="find"
                  data-note="a > b">
                <include
                    refid="shared.columns"
                    data-note="x > y"/>
              </select>
            </mapper>
        """.trimIndent()

        val discovery = discovered(xml)
        val statementText = xml.substring(
            discovery.statements.single().sourceRange.startOffset,
            discovery.statements.single().sourceRange.endOffsetExclusive,
        )
        val includeText = xml.substring(
            discovery.includes.single().sourceRange.startOffset,
            discovery.includes.single().sourceRange.endOffsetExclusive,
        )

        assertTrue(statementText.startsWith("<select\n"))
        assertTrue(statementText.endsWith("data-note=\"a > b\">"))
        assertTrue(includeText.startsWith("<include\n"))
        assertTrue(includeText.endsWith("data-note=\"x > y\"/>"))
    }

    @Test
    fun processingInstructionCrLfAndSingleQuotedAttributesRemainMappable() {
        val xml =
            "<?xml version='1.0'?>\r\n" +
                "<mapper namespace='fixture.Mapper'>\r\n" +
                "  <select id='find' data-note='a > b'>\r\n" +
                "    SELECT <include refid='shared.columns' data-note='x > y'/>\r\n" +
                "  </select>\r\n" +
                "</mapper>"

        val discovery = discovered(xml)

        assertEquals(listOf("find"), discovery.statements.map { it.id })
        assertEquals(listOf("shared.columns"), discovery.includes.map { it.refid })
        assertEquals(
            "<select id='find' data-note='a > b'>",
            discovery.statements.single().sourceRange.let { xml.substring(it.startOffset, it.endOffsetExclusive) },
        )
        assertEquals(
            "<include refid='shared.columns' data-note='x > y'/>",
            discovery.includes.single().sourceRange.let { xml.substring(it.startOffset, it.endOffsetExclusive) },
        )
    }

    @Test
    fun legalNonDeclarationMapperElementsAndIncludePropertiesDoNotBecomeDeclarations() {
        val xml = """
            <mapper namespace="fixture.Mapper">
              <cache/>
              <resultMap id="result" type="fixture.User">
                <id property="id" column="id"/>
              </resultMap>
              <sql id="columns">id, name</sql>
              <select id="find">
                SELECT <include refid="columns"><property name="prefix" value="u"/></include>
                FROM users
              </select>
            </mapper>
        """.trimIndent()

        val discovery = discovered(xml)

        assertEquals(listOf("find"), discovery.statements.map { it.id })
        assertEquals(listOf("columns"), discovery.fragments.map { it.id })
        assertEquals(listOf("columns"), discovery.includes.map { it.refid })
        assertEquals(
            XmlMapperDeclarationRef.Statement("find", StatementKind.SELECT),
            discovery.includes.single().owner,
        )
    }

    @Test
    fun namespacedMapperRootAndStructuralElementsFailClosed() {
        assertFailure(
            "<m:mapper xmlns:m=\"urn:fixture\" namespace=\"fixture.Mapper\"><m:select id=\"find\">SELECT 1</m:select></m:mapper>",
            XmlMapperDiscoveryFailure.INVALID_ROOT,
        )
        assertFailure(
            "<mapper xmlns=\"urn:fixture\" namespace=\"fixture.Mapper\"><select id=\"find\">SELECT 1</select></mapper>",
            XmlMapperDiscoveryFailure.INVALID_ROOT,
        )
        assertFailure(
            "<mapper xmlns:m=\"urn:fixture\" namespace=\"fixture.Mapper\"><m:select id=\"find\">SELECT 1</m:select></mapper>",
            XmlMapperDiscoveryFailure.INVALID_DECLARATION,
        )
        assertFailure(
            "<mapper namespace=\"fixture.Mapper\"><select xmlns=\"urn:fixture\" id=\"find\">SELECT 1</select></mapper>",
            XmlMapperDiscoveryFailure.INVALID_DECLARATION,
        )
        assertFailure(
            "<mapper namespace=\"fixture.Mapper\"><select id=\"find\"><include xmlns=\"urn:fixture\" refid=\"columns\"/></select></mapper>",
            XmlMapperDiscoveryFailure.INVALID_INCLUDE,
        )
    }

    @Test
    fun duplicateStatementAndFragmentIdsFailClosed() {
        assertFailure(
            """
                <mapper namespace="fixture.Mapper">
                  <select id="same">SELECT 1</select>
                  <update id="same">UPDATE t SET x = 1</update>
                </mapper>
            """.trimIndent(),
            XmlMapperDiscoveryFailure.DUPLICATE_STATEMENT_ID,
        )
        assertFailure(
            """
                <mapper namespace="fixture.Mapper">
                  <sql id="same">a</sql>
                  <sql id="same">b</sql>
                </mapper>
            """.trimIndent(),
            XmlMapperDiscoveryFailure.DUPLICATE_FRAGMENT_ID,
        )
    }

    @Test
    fun missingOrBlankNamespaceDeclarationIdAndRefidFailClosed() {
        assertFailure(
            "<mapper><select id=\"find\">SELECT 1</select></mapper>",
            XmlMapperDiscoveryFailure.MISSING_NAMESPACE,
        )
        assertFailure(
            "<mapper namespace=\"   \"><select id=\"find\">SELECT 1</select></mapper>",
            XmlMapperDiscoveryFailure.MISSING_NAMESPACE,
        )
        assertFailure(
            "<mapper namespace=\"fixture.Mapper\"><select>SELECT 1</select></mapper>",
            XmlMapperDiscoveryFailure.INVALID_DECLARATION,
        )
        assertFailure(
            "<mapper namespace=\"fixture.Mapper\"><sql id=\"   \">x</sql></mapper>",
            XmlMapperDiscoveryFailure.INVALID_DECLARATION,
        )
        assertFailure(
            "<mapper namespace=\"fixture.Mapper\"><select id=\"find\"><include/></select></mapper>",
            XmlMapperDiscoveryFailure.INVALID_INCLUDE,
        )
        assertFailure(
            "<mapper namespace=\"fixture.Mapper\"><select id=\"find\"><include refid=\"   \"/></select></mapper>",
            XmlMapperDiscoveryFailure.INVALID_INCLUDE,
        )
    }

    @Test
    fun structurallyInvalidDeclarationAndIncludePlacementFailClosed() {
        assertFailure(
            """
                <mapper namespace="fixture.Mapper">
                  <sql id="outer"><select id="nested">SELECT 1</select></sql>
                </mapper>
            """.trimIndent(),
            XmlMapperDiscoveryFailure.INVALID_DECLARATION,
        )
        assertFailure(
            """
                <mapper namespace="fixture.Mapper">
                  <resultMap id="r" type="x"><include refid="notAllowedHere"/></resultMap>
                </mapper>
            """.trimIndent(),
            XmlMapperDiscoveryFailure.INVALID_INCLUDE,
        )
        assertFailure(
            "<wrapper><mapper namespace=\"fixture.Mapper\"/></wrapper>",
            XmlMapperDiscoveryFailure.INVALID_ROOT,
        )
    }

    @Test
    fun malformedXmlAndEntityDeclarationsFailClosed() {
        assertFailure(
            "<mapper namespace=\"fixture.Mapper\"><select id=\"find\"></mapper>",
            XmlMapperDiscoveryFailure.MALFORMED_XML,
        )
        assertFailure(
            """
                <!DOCTYPE mapper [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <mapper namespace="fixture.Mapper">
                  <select id="find">&secret;</select>
                </mapper>
            """.trimIndent(),
            XmlMapperDiscoveryFailure.UNSAFE_DTD,
        )
    }

    @Test
    fun databaseIdAndLanguageDriverEvidenceIsPreservedExplicitly() {
        val xml = """
            <mapper namespace="fixture.Mapper">
              <select id="find" databaseId="postgres" lang="fixture.CustomDriver">SELECT 1</select>
              <sql id="columns" databaseId="oracle">id</sql>
              <select id="nestedEvidence">
                <selectKey keyProperty="id" resultType="long" databaseId="h2">SELECT 1</selectKey>
              </select>
            </mapper>
        """.trimIndent()

        val discovery = discovered(xml)

        assertEquals(
            listOf(
                Triple(XmlUnsupportedSemanticsKind.DATABASE_ID, "select", "postgres"),
                Triple(XmlUnsupportedSemanticsKind.LANGUAGE_DRIVER, "select", "fixture.CustomDriver"),
                Triple(XmlUnsupportedSemanticsKind.DATABASE_ID, "sql", "oracle"),
                Triple(XmlUnsupportedSemanticsKind.DATABASE_ID, "selectKey", "h2"),
            ),
            discovery.unsupportedSemantics.map { Triple(it.kind, it.elementName, it.value) },
        )
        assertTrue(
            discovery.unsupportedSemantics.all {
                xml.substring(it.sourceRange.startOffset, it.sourceRange.endOffsetExclusive).startsWith("<${it.elementName}")
            },
        )
    }

    @Test
    fun discoveryModelRetainsNoIntellijPlatformObjects() {
        val modelTypes = listOf(
            XmlMapperDocumentDiscovery::class.java,
            XmlMapperStatementDeclaration::class.java,
            XmlMapperFragmentDeclaration::class.java,
            XmlMapperIncludeReference::class.java,
            XmlUnsupportedSemanticsEvidence::class.java,
        )

        val retainedPlatformField = modelTypes
            .flatMap { it.declaredFields.toList() }
            .firstOrNull { it.type.name.startsWith("com.intellij.") }

        assertEquals(null, retainedPlatformField)
    }

    private fun discovered(xml: String): XmlMapperDocumentDiscovery {
        val result = XmlMapperSourceDiscovery.discover(snapshot(xml))
        assertTrue("expected discovered result but got $result", result is XmlMapperDiscoveryResult.Discovered)
        return (result as XmlMapperDiscoveryResult.Discovered).mapper
    }

    private fun assertFailure(xml: String, reason: XmlMapperDiscoveryFailure) {
        assertEquals(XmlMapperDiscoveryResult.Failed(reason), XmlMapperSourceDiscovery.discover(snapshot(xml)))
    }

    private fun snapshot(xml: String): SourceSnapshot =
        SourceSnapshot(
            fileId = SourceFileId("vfs:file:///fixture/Mapper.xml"),
            revision = SourceRevision("document:42"),
            content = xml,
        )
}
