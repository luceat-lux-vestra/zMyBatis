package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputContractProblem
import com.algorist.zMyBatis.core.input.InputContractProblemKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceDependencyEdge
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

class XmlBooleanIfPreparationAdmissionTest {
    @Test
    fun setRequiresCompleteMapperAndExactConditionalProvenance() {
        val body = "UPDATE t <set>base = #{id},<if test=\"enabled\">a = #{id},</if><if test=\"param2\">b = #{id},</if></set> WHERE id = #{id}"
        val parameters = listOf(parameter(0, "boolean", "enabled"), parameter(1, "java.lang.Boolean", "other"), parameter(2, "long", "id"))
        val graph = graph(body, kind = StatementKind.UPDATE)
        val fixture = Fixture(graph, mapper(graph, parameters))
        val original = contract(fixture)
        val admitted = inspect(fixture) as XmlBooleanIfPreparationAdmission.Result.Admitted
        assertEquals(setOf("enabled", "param2"), admitted.conditions.map { it.conditionAlias }.toSet())
        val moved = graph(body.replace("base = #{id},", "base = 1,").replace("a = #{id},", "a = #{id}, extra = #{id},"), kind = StatementKind.UPDATE)
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(moved, fixture.mapper, original))
        val alteredMapper = mapper(graph, parameters.map { if (it.index == 1) parameter(1, "long", "other") else it })
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(graph, alteredMapper, original), PreparationFailureKind.UNSUPPORTED_SEMANTIC, "xml-boolean-if-preparation-mapper-unsupported")
    }

    @Test
    fun independentAndRepeatedSiblingConditionsRetainEveryDistinctAliasAuthority() {
        val fixture = fixture(
            "SELECT #{id}<where><if test=\"enabled\">AND id = #{id}</if><if test=\"param2\">AND id = #{id}</if><if test=\"enabled\">AND 1 = 1</if></where>",
            listOf(parameter(0, "boolean", "enabled"), parameter(1, "boolean", "other"), parameter(2, "long", "id")),
        )
        val result = inspect(fixture) as XmlBooleanIfPreparationAdmission.Result.Admitted
        assertEquals(mapOf("enabled" to "xml-java-param:0", "param2" to "xml-java-param:1"), result.conditions.associate { it.conditionAlias to it.conditionRequirementId.value })
    }

    @Test
    fun forgedMissingOrMovedPlaceholderScopeCannotAuthenticateItself() {
        val fixture = fixture(
            "SELECT #{id}<if test=\"enabled\">, #{id}</if><if test=\"other\">, #{id}</if>",
            listOf(parameter(0, "boolean", "enabled"), parameter(1, "boolean", "other"), parameter(2, "long", "id")),
        )
        val original = contract(fixture)
        val id = original.requirements.single { it.id.value == "xml-java-param:2" }
        for (replacement in listOf(null, "other", "forged")) {
            val changed = id.copy(provenance = InputProvenance(id.provenance.evidence.map {
                if (it is InputEvidence.Placeholder && it.enclosingOgnlExpression == "enabled") it.copy(enclosingOgnlExpression = replacement) else it
            }))
            assertFailure(inspect(fixture, copyContract(original, requirements = original.requirements.map { if (it.id == id.id) changed else it })))
        }
        val moved = graph("SELECT 1<if test=\"enabled\">, #{id}, #{id}</if><if test=\"other\">, #{id}</if>")
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(moved, mapper(moved, fixture.mapper.parameters), original))
    }

    @Test
    fun missingOneSiblingConditionOrItsCallerAliasBlocksTheWholeAdmission() {
        val fixture = fixture(
            "SELECT 1<if test=\"enabled\">WHERE 1 = 1</if><if test=\"other\">AND 2 = 2</if>",
            listOf(parameter(0, "boolean", "enabled"), parameter(1, "boolean", "other")),
        )
        val original = contract(fixture)
        val other = original.requirements[1]
        val stripped = other.copy(provenance = InputProvenance(other.provenance.evidence.filterNot { it is InputEvidence.OgnlExpression }))
        assertFailure(inspect(fixture, copyContract(original, requirements = listOf(original.requirements[0], stripped))))
        assertFailure(inspect(fixture, copyContract(original, aliases = original.aliases.filterNot { it.name == "other" })))
    }

    @Test
    fun whereWrappedConditionIsAdmittedOnlyWithCompleteMapperEvidence() {
        val fixture = fixture(
            "SELECT #{id} <where><if test=\"param1\">AND id = #{id}</if></where>",
            listOf(parameter(0, "boolean", "enabled"), parameter(1, "long", "id")),
        )
        val original = contract(fixture)
        val admitted = inspect(fixture, original) as XmlBooleanIfPreparationAdmission.Result.Admitted
        assertEquals("param1", admitted.conditions.single().conditionAlias)
        assertEquals("xml-java-param:0", admitted.conditions.single().conditionRequirementId.value)

        val condition = original.requirement(admitted.conditions.single().conditionRequirementId)!!
        val stripped = condition.copy(provenance = InputProvenance(condition.provenance.evidence.filterNot { it is InputEvidence.OgnlExpression }))
        assertFailure(inspect(fixture, copyContract(original, requirements = original.requirements.map { if (it.id == condition.id) stripped else it })))
        val suppressed = mapper(fixture.graph, listOf(parameter(0, "boolean", "enabled"), parameter(1, "long", "id"), parameter(2, "boolean", "param1")))
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(fixture.graph, suppressed, original))
    }

    @Test
    fun supportedWherePlaceholderDriftCannotReuseOldContract() {
        val fixture = fixture("SELECT 1 <where><if test=\"enabled\">AND enabled = #{enabled}</if></where>")
        val changed = graph("SELECT 1 <where><if test=\"enabled\">AND enabled = #{enabled} OR enabled = #{enabled}</if></where>")
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(changed, mapper(changed), contract(fixture)))
    }

    @Test
    fun unsupportedWhereStructureCannotBeAuthorizedByAValidConditionContract() {
        val fixture = fixture("SELECT 1 <where><if test=\"enabled\">AND 1 = 1</if></where>")
        val condition = "<if test=\"enabled\">AND 1 = 1</if>"
        for (body in listOf(
            "<where/>", "<where>AND 1 = 1</where>",
            "<where prefixOverrides=\"AND\">$condition</where>",
            "<where xmlns=\"urn:unsupported\">$condition</where>",
            "<where><where>$condition</where></where>",
            "<where>$condition</where>$condition",
            "<where><include refid=\"fragment\"/>$condition</where>",
        )) {
            val source = graph("SELECT 1 $body")
            assertFailure(XmlBooleanIfPreparationAdmission.inspect(source, mapper(source), contract(fixture)), PreparationFailureKind.UNSUPPORTED_SEMANTIC, "xml-boolean-if-preparation-source-unsupported")
        }
    }

    @Test
    fun primitiveAndBoxedExplicitBooleanAuthorityAreAdmitted() {
        for (type in listOf("boolean", "java.lang.Boolean")) {
            val fixture = fixture(parameters = listOf(parameter(0, type, "enabled")))
            val contract = contract(fixture)
            val result = inspect(fixture, contract) as XmlBooleanIfPreparationAdmission.Result.Admitted

            assertEquals(contract.requirements.single().id, result.conditions.single().conditionRequirementId)
            assertEquals("enabled", result.conditions.single().conditionAlias)
        }
    }

    @Test
    fun completeMapperCaptureProvesGenericAliasAndUnusedParameterSuppression() {
        val fixture = fixture(
            body = "SELECT 1 <if test=\"param1\">WHERE 1 = 1</if>",
            parameters = listOf(parameter(0, "boolean", "enabled"), parameter(1, "boolean", "unused")),
        )
        val contract = contract(fixture)
        val result = inspect(fixture, contract) as XmlBooleanIfPreparationAdmission.Result.Admitted
        assertEquals("param1", result.conditions.single().conditionAlias)
        assertEquals(InputAliasKind.GENERIC_PARAM, contract.aliases.single().kind)

        // This otherwise unused declaration now owns param1 and suppresses the generated alias.
        val changedMapper = mapper(fixture.graph, listOf(parameter(0, "boolean", "enabled"), parameter(1, "boolean", "param1")))
        assertFailure(
            XmlBooleanIfPreparationAdmission.inspect(fixture.graph, changedMapper, contract),
            PreparationFailureKind.PREPARATION_INVARIANT,
            MISMATCH,
        )
    }

    @Test
    fun explicitAliasThatLooksGeneratedRetainsItsRealParameterIndex() {
        val fixture = fixture(
            body = "SELECT 1 <if test=\"param2\">WHERE 1 = 1</if>",
            parameters = listOf(parameter(0, "boolean", "param2"), parameter(1, "long", "id")),
        )
        val contract = contract(fixture)
        val result = inspect(fixture, contract) as XmlBooleanIfPreparationAdmission.Result.Admitted
        assertEquals("xml-java-param:0", result.conditions.single().conditionRequirementId.value)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, contract.aliases.single().kind)
    }

    @Test
    fun conditionAndBoundUsesRetainCompleteCallerContract() {
        val fixture = fixture(
            "SELECT #{enabled} <if test=\"enabled\">, #{enabled}, #{id}</if>",
            listOf(parameter(0, "boolean", "enabled"), parameter(1, "long", "id")),
        )
        val contract = contract(fixture)
        assertFalse(contract.isPreparationBlocked)
        val result = inspect(fixture, contract) as XmlBooleanIfPreparationAdmission.Result.Admitted
        assertEquals(contract.requirements.first().id, result.conditions.single().conditionRequirementId)
        assertEquals(2, contract.requirements.size)
        assertEquals(2, contract.requirements.first().provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().size)
    }

    @Test
    fun staticAndForeachContractsDoNotAcquireBooleanIfAuthority() {
        for (fixture in listOf(
            fixture("SELECT #{enabled}"),
            fixture("SELECT <foreach collection=\"ids\" item=\"id\">#{id}</foreach>", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids"))),
        )) {
            assertTrue(inspect(fixture) === XmlBooleanIfPreparationAdmission.Result.NotPresent)
        }
    }

    @Test
    fun missingDuplicateAndChangedConditionEvidenceAreRefused() {
        val fixture = fixture()
        val original = contract(fixture)
        val requirement = original.requirements.single()
        val condition = requirement.provenance.evidence.filterIsInstance<InputEvidence.OgnlExpression>().single()
        for (evidence in listOf(
            requirement.provenance.evidence.filterNot { it is InputEvidence.OgnlExpression },
            requirement.provenance.evidence + condition,
            requirement.provenance.evidence.map { if (it is InputEvidence.OgnlExpression) it.copy(expression = "forged") else it },
        )) {
            assertFailure(inspect(fixture, copyContract(original, requirements = listOf(requirement.copy(provenance = InputProvenance(evidence))))))
        }
    }

    @Test
    fun sourceConditionCannotDisappearWhenAllConsumerConditionEvidenceIsStripped() {
        val fixture = fixture()
        val original = contract(fixture)
        val empty = copyContract(original, requirements = emptyList(), aliases = emptyList())
        assertFailure(inspect(fixture, empty))
    }

    @Test
    fun staticSourceCannotAcquireForgedConditionEvenAtSameRevision() {
        val fixture = fixture()
        val source = graph("SELECT 1")
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(source, mapper(source), contract(fixture)))
    }

    @Test
    fun changedSupportedConditionCannotReuseOldContract() {
        val fixture = fixture(parameters = listOf(parameter(0, "boolean", "enabled"), parameter(1, "boolean", "other")))
        val source = graph("SELECT 1 <if test=\"other\">WHERE 1 = 1</if>")
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(source, mapper(source, fixture.mapper.parameters), contract(fixture)))
    }

    @Test
    fun unsupportedSourceDriftBlocksBeforeAdmission() {
        val fixture = fixture()
        val bodies = listOf(
            "<if test=\"enabled != null\">1</if>",
            "<if test=\"enabled\"><if test=\"enabled\">1</if></if>",
            "<if test=\"enabled\"><include refid=\"fragment\"/></if>",
            "<if test=\"enabled\">\${table}</if>",
            "<if test=\"enabled\" extra=\"ignored\">1</if>",
            "<if xmlns=\"urn:unsupported\" test=\"enabled\">1</if>",
            "<if test=\"and\">1</if>",
            "<if test=\"enabled\">",
        )
        for (body in bodies) {
            val source = graph("SELECT 1 $body")
            assertFailure(
                XmlBooleanIfPreparationAdmission.inspect(source, mapper(source), contract(fixture)),
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                "xml-boolean-if-preparation-source-unsupported",
            )
        }
    }

    @Test
    fun missingAliasWrongKindOrForgedNamingRuleCannotProveThemselves() {
        val fixture = fixture()
        val original = contract(fixture)
        assertFailure(inspect(fixture, copyContract(original, aliases = emptyList())))
        val alias = original.aliases.single()
        assertFailure(inspect(fixture, copyContract(original, aliases = listOf(alias.copy(kind = InputAliasKind.GENERIC_PARAM)))))

        val genericFixture = fixture("SELECT 1 <if test=\"param1\">WHERE 1 = 1</if>")
        val generic = contract(genericFixture)
        val genericAlias = generic.aliases.single()
        val forged = genericAlias.copy(provenance = InputProvenance(genericAlias.provenance.evidence.map {
            if (it is InputEvidence.GeneratedAlias) it.copy(ruleId = "forged-rule") else it
        }))
        assertFailure(inspect(genericFixture, copyContract(generic, aliases = listOf(forged))))
    }

    @Test
    fun mapperParameterTypeIndexAndSourceRangeEvidenceMustMatchCapture() {
        val fixture = fixture()
        val original = contract(fixture)
        val requirement = original.requirements.single()
        val parameter = requirement.provenance.evidence.filterIsInstance<InputEvidence.MapperMethodParameter>().single()
        for (forged in listOf(
            parameter.copy(index = 1),
            parameter.copy(typeIdentity = JavaTypeIdentity("java.lang.Boolean")),
            parameter.copy(sourceName = "forged"),
            parameter.copy(source = parameter.source.copy(sourceRange = SourceRange(0, 1))),
        )) {
            val changed = requirement.copy(provenance = InputProvenance(requirement.provenance.evidence.map {
                if (it is InputEvidence.MapperMethodParameter) forged else it
            }))
            assertFailure(inspect(fixture, copyContract(original, requirements = listOf(changed))))
        }
    }

    @Test
    fun expectedTypeAndRequirednessDriftAreRefused() {
        val fixture = fixture()
        val original = contract(fixture)
        val requirement = original.requirements.single()
        for (changed in listOf(
            requirement.copy(expectedType = requirement.expectedType.copy(scalarType = InputScalarType.STRING)),
            requirement.copy(expectedType = requirement.expectedType.copy(javaTypeIdentity = JavaTypeIdentity("java.lang.Boolean"))),
            requirement.copy(requiredness = InputRequiredness.OPTIONAL),
        )) {
            assertFailure(inspect(fixture, copyContract(original, requirements = listOf(changed))))
        }
    }

    @Test
    fun unrelatedCallerAndInternalAuthorityCannotRideAlongWithCondition() {
        val fixture = fixture("SELECT #{id} <if test=\"enabled\">WHERE 1 = 1</if>", listOf(parameter(0, "boolean", "enabled"), parameter(1, "long", "id")))
        val original = contract(fixture)
        val changedBound = original.requirements[1].copy(provenance = InputProvenance(original.requirements[1].provenance.evidence.filterNot { it is InputEvidence.Placeholder }))
        assertFailure(inspect(fixture, copyContract(original, requirements = listOf(original.requirements[0], changedBound))))

        val extraInternal = InternalBinding("forged", InternalBindingKind.BIND, original.requirements[0].provenance)
        assertFailure(inspect(fixture, copyContract(original, internalBindings = listOf(extraInternal))))
        val alias = original.aliases.first()
        assertFailure(inspect(fixture, copyContract(original, aliases = original.aliases + alias.copy(name = "forged"))))
    }

    @Test
    fun blockedContractCannotBeAdmitted() {
        val fixture = fixture()
        val blocked = copyContract(contract(fixture), blockingProblems = listOf(InputContractProblem(InputContractProblemKind.UNSUPPORTED, "blocked", null, null)))
        assertFailure(inspect(fixture, blocked), PreparationFailureKind.INPUT_CONTRACT_BLOCKED, "xml-boolean-if-preparation-contract-unsupported")
    }

    @Test
    fun changedMapperTypeMissingAliasAndAmbiguousCaptureRemainBlocked() {
        val fixture = fixture()
        for (parameters in listOf(
            listOf(parameter(0, "java.lang.String", "enabled")),
            listOf(parameter(0, "boolean", null)),
            listOf(parameter(0, "boolean", "enabled"), parameter(1, "boolean", "enabled")),
        )) {
            assertFailure(
                XmlBooleanIfPreparationAdmission.inspect(fixture.graph, mapper(fixture.graph, parameters), contract(fixture)),
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                "xml-boolean-if-preparation-mapper-unsupported",
            )
        }
    }

    @Test
    fun statementIdentityAndBothSourceRevisionsMustMatch() {
        val fixture = fixture()
        val original = contract(fixture)
        val wrongGraph = graph(DEFAULT_BODY, statementName = "other")
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(wrongGraph, fixture.mapper, original), PreparationFailureKind.STATEMENT_ID_MISMATCH, "xml-boolean-if-preparation-statement-mismatch")
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(fixture.graph, mapper(wrongGraph), original), PreparationFailureKind.STATEMENT_ID_MISMATCH, "xml-boolean-if-preparation-statement-mismatch")
        val changedXml = graph(DEFAULT_BODY, revision = SourceRevision("xml-r2"))
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(changedXml, mapper(changedXml), original), PreparationFailureKind.SOURCE_REVISION_MISMATCH, "xml-boolean-if-preparation-revision-mismatch")
        val changedJava = mapper(fixture.graph, revision = SourceRevision("java-r2"))
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(fixture.graph, changedJava, original), PreparationFailureKind.SOURCE_REVISION_MISMATCH, "xml-boolean-if-preparation-revision-mismatch")
        assertFailure(inspect(fixture, copyContract(original, sourceRevisions = original.sourceRevisions + (SourceFileId("vfs:/orphan") to SourceRevision("orphan")))), PreparationFailureKind.SOURCE_REVISION_MISMATCH, "xml-boolean-if-preparation-revision-mismatch")
    }

    @Test
    fun dependentOrUnrelatedXmlSnapshotsCannotExpandTheAdmittedSourceIsland() {
        val fixture = fixture()
        val extraFile = SourceFileId("vfs:/extra.xml")
        val snapshots = fixture.graph.sourceSnapshots + SourceSnapshot(extraFile, SourceRevision("extra-r1"), "<mapper namespace=\"extra.Mapper\"/>")
        for (dependencies in listOf(
            emptyList(),
            listOf(SourceDependencyEdge(XML_FILE, extraFile, SourceRange(0, 1))),
        )) {
            val expanded = StatementSourceGraph(fixture.graph.rootStatement, snapshots, dependencies)
            assertFailure(
                XmlBooleanIfPreparationAdmission.inspect(expanded, fixture.mapper, contract(fixture)),
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                "xml-boolean-if-preparation-source-unsupported",
            )
        }
    }

    @Test
    fun nonXmlStatementSourceAndSharedXmlMapperAuthorityAreRefused() {
        val fixture = fixture()
        val original = contract(fixture)
        val javaGraph = StatementSourceGraph(
            fixture.graph.rootStatement.copy(id = JavaStatementId(XML_FILE, "example.Mapper", MethodSignature("find", emptyList()))),
            fixture.graph.sourceSnapshots,
            emptyList(),
        )
        assertFailure(
            XmlBooleanIfPreparationAdmission.inspect(javaGraph, fixture.mapper, original),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-boolean-if-preparation-source-unsupported",
        )
        val sharedAuthority = XmlMapperMethodCapture(
            fixture.mapper.statementId,
            fixture.graph.sourceSnapshots.single(),
            SourceRange(0, 1),
            fixture.mapper.parameters,
        )
        assertFailure(
            XmlBooleanIfPreparationAdmission.inspect(fixture.graph, sharedAuthority, original),
            PreparationFailureKind.PREPARATION_INVARIANT,
            "xml-boolean-if-preparation-mapper-unsupported",
        )
    }

    @Test
    fun mapperMethodRangeDriftCannotReuseOldProvenance() {
        val fixture = fixture()
        val changedMapper = XmlMapperMethodCapture(
            fixture.mapper.statementId,
            fixture.mapper.mapperSource,
            SourceRange(21, 121),
            fixture.mapper.parameters,
        )
        assertFailure(XmlBooleanIfPreparationAdmission.inspect(fixture.graph, changedMapper, contract(fixture)))
    }

    private fun inspect(fixture: Fixture, contract: ParameterContract = contract(fixture)) =
        XmlBooleanIfPreparationAdmission.inspect(fixture.graph, fixture.mapper, contract)

    private fun assertFailure(
        result: XmlBooleanIfPreparationAdmission.Result,
        kind: PreparationFailureKind = PreparationFailureKind.PREPARATION_INVARIANT,
        code: String = MISMATCH,
    ) {
        assertTrue(result is XmlBooleanIfPreparationAdmission.Result.Failed)
        val failure = (result as XmlBooleanIfPreparationAdmission.Result.Failed).failure
        assertEquals(kind, failure.kind)
        assertEquals(code, failure.code)
        assertEquals(null, failure.bindingProperty)
        assertEquals(null, failure.diagnosticType)
    }

    private fun copyContract(
        original: ParameterContract,
        requirements: List<InputRequirement> = original.requirements,
        aliases: List<InputAlias> = original.aliases,
        internalBindings: List<InternalBinding> = original.internalBindings,
        blockingProblems: List<InputContractProblem> = original.blockingProblems,
        sourceRevisions: Map<SourceFileId, SourceRevision> = original.sourceRevisions,
    ) = ParameterContract(original.statementId, requirements, aliases, internalBindings, blockingProblems, sourceRevisions)

    private fun fixture(
        body: String = DEFAULT_BODY,
        parameters: List<JavaMethodParameterMetadata> = listOf(parameter(0, "boolean", "enabled")),
    ): Fixture {
        val graph = graph(body)
        return Fixture(graph, mapper(graph, parameters))
    }

    private fun contract(fixture: Fixture) = XmlMapperMethodParameterContractFactory.build(fixture.graph, fixture.mapper)

    private fun mapper(
        graph: StatementSourceGraph,
        parameters: List<JavaMethodParameterMetadata> = listOf(parameter(0, "boolean", "enabled")),
        revision: SourceRevision = JAVA_REVISION,
    ) = XmlMapperMethodCapture(
        graph.rootStatement.id as XmlStatementId,
        SourceSnapshot(JAVA_FILE, revision, "x".repeat(256)),
        SourceRange(20, 120),
        parameters,
    )

    private fun graph(
        body: String,
        revision: SourceRevision = XML_REVISION,
        statementName: String = "find",
        kind: StatementKind = StatementKind.SELECT,
    ): StatementSourceGraph {
        val element = kind.name.lowercase()
        val xml = "<mapper namespace=\"example.Mapper\"><$element id=\"$statementName\">$body</$element></mapper>"
        val start = xml.indexOf("<$element")
        return StatementSourceGraph(
            CapturedStatement(XmlStatementId(XML_FILE, "example.Mapper", statementName), kind, SourceRange(start, xml.indexOf('>', start) + 1)),
            listOf(SourceSnapshot(XML_FILE, revision, xml)),
            emptyList(),
        )
    }

    private fun parameter(index: Int, type: String, alias: String?) = JavaMethodParameterMetadata(index, "source$index", JavaTypeIdentity(type), alias)

    private data class Fixture(val graph: StatementSourceGraph, val mapper: XmlMapperMethodCapture)

    private companion object {
        const val DEFAULT_BODY = "SELECT 1 <if test=\"enabled\">WHERE 1 = 1</if>"
        const val MISMATCH = "xml-boolean-if-preparation-source-contract-mismatch"
        val XML_FILE = SourceFileId("vfs:/mapper.xml")
        val JAVA_FILE = SourceFileId("vfs:/Mapper.java")
        val XML_REVISION = SourceRevision("xml-r1")
        val JAVA_REVISION = SourceRevision("java-r1")
    }
}
