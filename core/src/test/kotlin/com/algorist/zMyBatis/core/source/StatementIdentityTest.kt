package com.algorist.zMyBatis.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StatementIdentityTest {
    @Test
    fun javaOverloadsWithDifferentParameterTypesDoNotCollide() {
        val source = SourceFileId("src/main/java/com/acme/UserMapper.java")
        val byName = JavaStatementId(
            source,
            "com.acme.UserMapper",
            MethodSignature("find", listOf(JavaTypeIdentity("java.lang.String"))),
        )
        val byId = JavaStatementId(
            source,
            "com.acme.UserMapper",
            MethodSignature("find", listOf(JavaTypeIdentity("java.lang.Long"))),
        )

        assertNotEquals(byName, byId)
    }

    @Test
    fun javaParameterOrderIsPartOfCanonicalIdentity() {
        val source = SourceFileId("src/main/java/com/acme/UserMapper.java")
        val first = JavaStatementId(
            source,
            "com.acme.UserMapper",
            MethodSignature(
                "find",
                listOf(JavaTypeIdentity("java.lang.String"), JavaTypeIdentity("java.lang.Long")),
            ),
        )
        val second = JavaStatementId(
            source,
            "com.acme.UserMapper",
            MethodSignature(
                "find",
                listOf(JavaTypeIdentity("java.lang.Long"), JavaTypeIdentity("java.lang.String")),
            ),
        )

        assertNotEquals(first, second)
    }

    @Test
    fun qualifiedMapperTypeIsPartOfJavaIdentity() {
        val source = SourceFileId("src/main/java/com/acme/UserMapper.java")
        val signature = MethodSignature("find", emptyList())

        assertNotEquals(
            JavaStatementId(source, "com.acme.first.UserMapper", signature),
            JavaStatementId(source, "com.acme.second.UserMapper", signature),
        )
    }

    @Test
    fun sourceFileIsPartOfJavaAndXmlIdentity() {
        val sourceA = SourceFileId("module-a/UserMapper.java")
        val sourceB = SourceFileId("module-b/UserMapper.java")
        val signature = MethodSignature("find", emptyList())

        assertNotEquals(
            JavaStatementId(sourceA, "com.acme.UserMapper", signature),
            JavaStatementId(sourceB, "com.acme.UserMapper", signature),
        )

        assertNotEquals(
            XmlStatementId(SourceFileId("module-a/UserMapper.xml"), "com.acme.UserMapper", "find"),
            XmlStatementId(SourceFileId("module-b/UserMapper.xml"), "com.acme.UserMapper", "find"),
        )
    }

    @Test
    fun xmlNamespaceIsPartOfCanonicalIdentity() {
        val source = SourceFileId("src/main/resources/UserMapper.xml")

        assertNotEquals(
            XmlStatementId(source, "com.acme.first.UserMapper", "find"),
            XmlStatementId(source, "com.acme.second.UserMapper", "find"),
        )
    }

    @Test
    fun equivalentCanonicalComponentsRemainEqualAndHashEqual() {
        val source = SourceFileId("src/main/java/com/acme/UserMapper.java")
        val left = JavaStatementId(
            source,
            "com.acme.UserMapper",
            MethodSignature(
                "find",
                listOf(JavaTypeIdentity("java.lang.String"), JavaTypeIdentity("java.lang.Long")),
            ),
        )
        val right = JavaStatementId(
            SourceFileId("src/main/java/com/acme/UserMapper.java"),
            "com.acme.UserMapper",
            MethodSignature(
                "find",
                listOf(JavaTypeIdentity("java.lang.String"), JavaTypeIdentity("java.lang.Long")),
            ),
        )

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun methodSignatureDefensivelyCopiesCallerOwnedParameterTypes() {
        val stringType = JavaTypeIdentity("java.lang.String")
        val longType = JavaTypeIdentity("java.lang.Long")
        val callerOwnedTypes = mutableListOf(stringType, longType)
        val signature = MethodSignature("find", callerOwnedTypes)
        val originalHash = signature.hashCode()

        callerOwnedTypes.reverse()
        callerOwnedTypes.clear()

        assertEquals(listOf(stringType, longType), signature.parameterTypeIdentities)
        assertEquals(originalHash, signature.hashCode())
    }

    @Test
    fun sourceRevisionsAreOpaqueAndDistinct() {
        assertNotEquals(SourceRevision("revision-1"), SourceRevision("revision-2"))
        assertEquals(SourceRevision("revision-1"), SourceRevision("revision-1"))
    }

    @Test
    fun blankCanonicalComponentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SourceFileId(" ") }
        assertThrows(IllegalArgumentException::class.java) { SourceRevision("\t") }
        assertThrows(IllegalArgumentException::class.java) { JavaTypeIdentity("") }
        assertThrows(IllegalArgumentException::class.java) {
            XmlStatementId(SourceFileId("UserMapper.xml"), "", "find")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XmlStatementId(SourceFileId("UserMapper.xml"), "com.acme.UserMapper", " ")
        }
        assertThrows(IllegalArgumentException::class.java) { MethodSignature("", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            JavaStatementId(
                SourceFileId("UserMapper.java"),
                " ",
                MethodSignature("find", emptyList()),
            )
        }
    }
}
