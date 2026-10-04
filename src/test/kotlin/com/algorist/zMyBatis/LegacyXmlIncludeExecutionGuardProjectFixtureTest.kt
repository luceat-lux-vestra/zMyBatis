package com.algorist.zMyBatis

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class LegacyXmlIncludeExecutionGuardProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    fun testNestedIncludeDependencyIsDetectedFromRealXmlPsi() {
        myFixture.configureByText(
            XmlFileType.INSTANCE,
            """
            <mapper namespace="fixture.Mapper">
              <select id="find">
                SELECT *
                <where>
                  <include refid="filters"/>
                </where>
              </select>
              <sql id="filters">AND active = 1</sql>
            </mapper>
            """.trimIndent(),
        )

        assertTrue(
            LegacyXmlIncludeExecutionGuard.containsIncludeDependency(
                statementTag("find"),
            ),
        )
    }

    fun testIncludeLookingCommentAndCdataDoNotTriggerRefusal() {
        myFixture.configureByText(
            XmlFileType.INSTANCE,
            """
            <mapper namespace="fixture.Mapper">
              <select id="find">
                SELECT 1
                <!-- <include refid="commentGhost"/> -->
                <![CDATA[ <include refid="cdataGhost"/> ]]>
              </select>
            </mapper>
            """.trimIndent(),
        )

        assertFalse(
            LegacyXmlIncludeExecutionGuard.containsIncludeDependency(
                statementTag("find"),
            ),
        )
    }

    fun testSiblingSqlFragmentDoesNotTriggerStatementRefusalWithoutInclude() {
        myFixture.configureByText(
            XmlFileType.INSTANCE,
            """
            <mapper namespace="fixture.Mapper">
              <sql id="columns">id, name</sql>
              <select id="find">SELECT id, name FROM users</select>
            </mapper>
            """.trimIndent(),
        )

        assertFalse(
            LegacyXmlIncludeExecutionGuard.containsIncludeDependency(
                statementTag("find"),
            ),
        )
    }

    private fun statementTag(id: String): XmlTag =
        PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .single { it.localName == "select" && it.getAttributeValue("id") == id }
}
