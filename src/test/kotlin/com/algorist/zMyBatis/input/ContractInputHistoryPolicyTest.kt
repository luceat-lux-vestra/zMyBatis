package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractInputHistoryPolicyTest {
    @Test
    fun `retention key includes complete overload-safe Java statement identity`() {
        val oneLong = javaStatement("find", listOf("java.lang.Long"))
        val oneString = javaStatement("find", listOf("java.lang.String"))
        val ordered = javaStatement("find", listOf("java.lang.Long", "java.lang.String"))
        val reversed = javaStatement("find", listOf("java.lang.String", "java.lang.Long"))

        assertNotEquals(ContractInputRetentionKey.of(oneLong), ContractInputRetentionKey.of(oneString))
        assertNotEquals(ContractInputRetentionKey.of(ordered), ContractInputRetentionKey.of(reversed))
        assertNotEquals(
            ContractInputRetentionKey.of(oneLong),
            ContractInputRetentionKey.of(oneLong.copy(sourceFileId = SourceFileId("vfs:file:///other/Mapper.java"))),
        )
    }

    @Test
    fun `XML retention key includes source namespace and statement id`() {
        val first = XmlStatementId(FILE, "example.Mapper", "find")
        val namespaceChanged = XmlStatementId(FILE, "example.OtherMapper", "find")
        val statementChanged = XmlStatementId(FILE, "example.Mapper", "findAll")
        val sourceChanged = XmlStatementId(SourceFileId("vfs:file:///other/Mapper.xml"), "example.Mapper", "find")

        assertNotEquals(ContractInputRetentionKey.of(first), ContractInputRetentionKey.of(namespaceChanged))
        assertNotEquals(ContractInputRetentionKey.of(first), ContractInputRetentionKey.of(statementChanged))
        assertNotEquals(ContractInputRetentionKey.of(first), ContractInputRetentionKey.of(sourceChanged))
    }

    @Test
    fun `length-prefixed retention components cannot collide through delimiters`() {
        val left = XmlStatementId(SourceFileId("a|1:b"), "c", "d")
        val right = XmlStatementId(SourceFileId("a"), "1:b|c", "d")

        assertNotEquals(ContractInputRetentionKey.of(left), ContractInputRetentionKey.of(right))
    }

    @Test
    fun `persistence is deny by default even when bound text is supplied`() {
        val bound = requirement("bound", InputKind.BOUND)
        val retained = ContractInputHistoryPolicy.filterForSave(
            contract = contract(bound),
            rawValues = mapOf(bound.id to "42"),
            explicitlyRetainedIds = emptySet(),
        )

        assertTrue(retained.isEmpty())
    }

    @Test
    fun `only explicitly selected current bound requirements are saved`() {
        val first = requirement("first", InputKind.BOUND)
        val second = requirement("second", InputKind.BOUND)
        val raw = requirement("raw", InputKind.RAW_INTERPOLATION)
        val stale = InputRequirementId("stale")
        val retained = ContractInputHistoryPolicy.filterForSave(
            contract = contract(first, second, raw),
            rawValues = mapOf(
                first.id to "1",
                second.id to "2",
                raw.id to "created_at",
                stale to "stale",
            ),
            explicitlyRetainedIds = setOf(first.id, raw.id, stale),
        )

        assertEquals(mapOf("first" to "1"), retained)
        assertFalse("second" in retained)
        assertFalse("raw" in retained)
        assertFalse("stale" in retained)
    }

    @Test
    fun `restore ignores stale and raw values from persisted state`() {
        val bound = requirement("bound", InputKind.BOUND)
        val raw = requirement("raw", InputKind.RAW_INTERPOLATION)
        val restored = ContractInputHistoryPolicy.restore(
            contract(bound, raw),
            mapOf(
                "bound" to "42",
                "raw" to "created_at",
                "stale" to "old",
                "" to "malformed",
            ),
        )

        assertEquals(mapOf(bound.id to "42"), restored)
        assertTrue(raw.id !in restored)
        assertTrue(InputRequirementId("stale") !in restored)
    }

    private fun requirement(id: String, kind: InputKind): InputRequirement = InputRequirement(
        id = InputRequirementId(id),
        kind = kind,
        expectedType = if (kind == InputKind.RAW_INTERPOLATION) {
            ExpectedInputType(InputShape.RAW_TEXT, InputScalarType.STRING)
        } else {
            ExpectedInputType(InputShape.SCALAR, InputScalarType.INTEGER)
        },
        requiredness = InputRequiredness.REQUIRED,
        provenance = InputProvenance(
            listOf(InputEvidence.Placeholder(kind, id, SourceEvidence(FILE, REVISION, null))),
        ),
    )

    private fun contract(vararg requirements: InputRequirement): ParameterContract = ParameterContract(
        statementId = javaStatement("find", listOf("java.lang.Long")),
        requirements = requirements.toList(),
        aliases = emptyList(),
        internalBindings = emptyList(),
        blockingProblems = emptyList(),
        sourceRevisions = mapOf(FILE to REVISION),
    )

    private fun javaStatement(name: String, parameterTypes: List<String>): JavaStatementId = JavaStatementId(
        sourceFileId = FILE,
        qualifiedMapperType = "example.Mapper",
        methodSignature = MethodSignature(name, parameterTypes.map(::JavaTypeIdentity)),
    )

    private companion object {
        val FILE = SourceFileId("vfs:file:///src/example/Mapper.java")
        val REVISION = SourceRevision("revision-1")
    }
}
