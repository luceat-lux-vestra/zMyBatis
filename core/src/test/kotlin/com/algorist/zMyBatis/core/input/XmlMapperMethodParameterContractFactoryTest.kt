package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.CapturedStatement
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

class XmlMapperMethodParameterContractFactoryTest {
    @Test
    fun boundExplicitParamBecomesTypedCallerRequirement() {
        val graph = graph("select * from users where id = #{id,jdbcType=BIGINT}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long", "id", "id")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputRequirementId("xml-java-param:0"), requirement.id)
        assertEquals(InputKind.BOUND, requirement.kind)
        assertEquals(InputShape.SCALAR, requirement.expectedType.shape)
        assertEquals(InputScalarType.INTEGER, requirement.expectedType.scalarType)
        assertEquals(InputNullability.NON_NULL, requirement.expectedType.nullability)
        assertEquals(JavaTypeIdentity("long"), requirement.expectedType.javaTypeIdentity)

        val alias = contract.aliases.single()
        assertEquals("id", alias.name)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, alias.kind)
        assertEquals(requirement.id, alias.requirementId)

        val evidence = requirement.provenance.evidence
        assertTrue(evidence.any { it is InputEvidence.MapperMethodParameter && it.index == 0 })
        assertTrue(evidence.any { it is InputEvidence.ExplicitParamAlias && it.alias == "id" })
        assertTrue(
            evidence.any {
                it is InputEvidence.Placeholder &&
                    it.kind == InputKind.BOUND &&
                    it.expression == "id"
            },
        )
    }

    @Test
    fun rawStringExplicitParamBecomesRawTextRequirement() {
        val graph = graph("select * from " + raw("table"))
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.lang.String", "table", "table")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputKind.RAW_INTERPOLATION, requirement.kind)
        assertEquals(InputShape.RAW_TEXT, requirement.expectedType.shape)
        assertEquals(InputScalarType.STRING, requirement.expectedType.scalarType)
        assertEquals(JavaTypeIdentity("java.lang.String"), requirement.expectedType.javaTypeIdentity)
    }

    @Test
    fun allExplicitParametersExposeProvableGenericParamAliases() {
        val graph = graph("select * from users where id = #{param1} and name = #{param2}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "id", "id"),
                parameter(1, "java.lang.String", "name", "name"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(
            listOf("xml-java-param:0", "xml-java-param:1"),
            contract.requirements.map { it.id.value },
        )
        assertEquals(listOf("param1", "param2"), contract.aliases.map { it.name })
        assertTrue(contract.aliases.all { it.kind == InputAliasKind.GENERIC_PARAM })
        assertEquals(
            mapOf(
                XML_FILE to XML_REVISION,
                JAVA_FILE to JAVA_REVISION,
            ),
            contract.sourceRevisions,
        )

        contract.requirements.forEachIndexed { index, requirement ->
            val generated = requirement.provenance.evidence
                .filterIsInstance<InputEvidence.GeneratedAlias>()
                .single()
            assertEquals(index, generated.parameterIndex)
            assertEquals("param${index + 1}", generated.alias)
            assertEquals("mybatis-3.5.19-param-name-resolver-generic", generated.ruleId)
            assertTrue(
                requirement.provenance.evidence.any {
                    it is InputEvidence.ExplicitParamAlias && it.parameterIndex == index
                },
            )
        }
    }

    @Test
    fun explicitAliasCollisionSuppressesGenericAliasExactlyLikeMyBatis() {
        val graph = graph("select * from users where first = #{param1} and second = #{param2}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "first", "param1"),
                parameter(1, "long", "second", "second"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(
            listOf("xml-java-param:0", "xml-java-param:1"),
            contract.requirements.map { it.id.value },
        )
        assertEquals(
            listOf(InputAliasKind.EXPLICIT_PARAM, InputAliasKind.GENERIC_PARAM),
            contract.aliases.map { it.kind },
        )
        assertEquals(listOf("param1", "param2"), contract.aliases.map { it.name })
        assertEquals(
            0,
            contract.aliases.first().provenance.evidence
                .filterIsInstance<InputEvidence.GeneratedAlias>()
                .size,
        )
        assertEquals(
            1,
            contract.aliases.last().provenance.evidence
                .filterIsInstance<InputEvidence.GeneratedAlias>()
                .size,
        )
    }

    @Test
    fun explicitAliasOnDifferentParameterSuppressesFirstGeneratedAlias() {
        val graph = graph("select * from users where value = #{param1}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "first", "first"),
                parameter(1, "java.lang.String", "second", "param1"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputRequirementId("xml-java-param:1"), requirement.id)
        assertEquals(JavaTypeIdentity("java.lang.String"), requirement.expectedType.javaTypeIdentity)
        val alias = contract.aliases.single()
        assertEquals("param1", alias.name)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, alias.kind)
        assertTrue(alias.provenance.evidence.none { it is InputEvidence.GeneratedAlias })
    }

    @Test
    fun mixedAnnotatedAndUnannotatedParametersDoNotProveGenericAliases() {
        val graph = graph("select * from users where id = #{param1}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "id", "id"),
                parameter(1, "java.lang.String", "name", null),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
    }

    @Test
    fun allUnannotatedParametersDoNotProveGenericAliases() {
        val graph = graph("select * from users where id = #{param1}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "id", null),
                parameter(1, "java.lang.String", "name", null),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
    }

    @Test
    fun duplicateExplicitAliasesPreventGenericAliasWidening() {
        val graph = graph("select * from users where id = #{param1}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "first", "same"),
                parameter(1, "long", "second", "same"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
    }

    @Test
    fun generatedAliasRetainsRawStringSafetyRule() {
        val graph = graph("select * from users limit " + raw("param1"))
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long", "limit", "limit")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertEquals(InputAliasKind.GENERIC_PARAM, contract.aliases.single().kind)
        val problem = contract.blockingProblems.single()
        assertEquals("xml-raw-non-string-parameter", problem.code)
        assertEquals(contract.requirements.single().id, problem.requirementId)
    }

    @Test
    fun generatedAliasRetainsUnknownShapeSafetyRule() {
        val graph = graph("select * from users where filter = #{param1}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "com.acme.Filter", "filter", "filter")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertEquals(InputAliasKind.GENERIC_PARAM, contract.aliases.single().kind)
        assertEquals(InputShape.UNKNOWN, contract.requirements.single().expectedType.shape)
        assertEquals("xml-unproven-parameter-shape", contract.blockingProblems.single().code)
        assertEquals(contract.requirements.single().id, contract.blockingProblems.single().requirementId)
    }

    @Test
    fun explicitAndGenericNamesForSameParameterCollapseToOneRequirement() {
        val graph = graph("select * from users where id = #{id} or parent_id = #{param1}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "id", "id"),
                parameter(1, "java.lang.String", "name", "name"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        assertEquals(
            listOf("id", "param1"),
            contract.aliases.map { it.name },
        )
        assertEquals(
            listOf(InputAliasKind.EXPLICIT_PARAM, InputAliasKind.GENERIC_PARAM),
            contract.aliases.map { it.kind },
        )
        assertEquals(
            2,
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .size,
        )
    }

    @Test
    fun rawAndBoundNamesForSameParameterRemainAmbiguousAcrossAliasKinds() {
        val graph = graph("select " + raw("param1") + " from users where id = #{id}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "java.lang.String", "id", "id"),
                parameter(1, "long", "other", "other"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals("xml-mixed-raw-bound-input", contract.blockingProblems.single().code)
        assertEquals(
            1,
            contract.blockingProblems.single().provenance!!.evidence
                .filterIsInstance<InputEvidence.GeneratedAlias>()
                .size,
        )
    }

    @Test
    fun argNamesRemainUnprovenEvenWhenGenericAliasesAreProvable() {
        val graph = graph("select * from users where id = #{arg0}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "id", "id"),
                parameter(1, "java.lang.String", "name", "name"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
    }

    @Test
    fun soleUnannotatedListExposesCollectionAndListAliases() {
        val graph = graph("select * from users where id in #{collection} and id in #{list}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", null)),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        assertEquals(InputShape.LIST, contract.requirements.single().expectedType.shape)
        assertEquals(listOf("collection", "list"), contract.aliases.map { it.name })
        assertEquals(
            listOf(InputAliasKind.COLLECTION, InputAliasKind.LIST),
            contract.aliases.map { it.kind },
        )
        val generatedRequirementAliases = contract.requirements.single().provenance.evidence
            .filterIsInstance<InputEvidence.GeneratedAlias>()
        assertEquals(listOf("collection", "list"), generatedRequirementAliases.map { it.alias })
        assertTrue(
            generatedRequirementAliases.all {
                it.parameterIndex == 0 &&
                    it.ruleId == "mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection"
            },
        )
        contract.aliases.forEach { alias ->
            val generatedAlias = alias.provenance.evidence
                .filterIsInstance<InputEvidence.GeneratedAlias>()
                .single()
            assertEquals(0, generatedAlias.parameterIndex)
            assertEquals(alias.name, generatedAlias.alias)
            assertEquals(
                "mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection",
                generatedAlias.ruleId,
            )
        }
        assertEquals(
            mapOf(XML_FILE to XML_REVISION, JAVA_FILE to JAVA_REVISION),
            contract.sourceRevisions,
        )
    }

    @Test
    fun soleUnannotatedCollectionExposesOnlyCollectionAlias() {
        val graph = graph("select * from users where id in #{collection}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.util.Collection<java.lang.Long>", "ids", null)),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(InputShape.LIST, contract.requirements.single().expectedType.shape)
        assertEquals(listOf("collection"), contract.aliases.map { it.name })
        assertEquals(InputAliasKind.COLLECTION, contract.aliases.single().kind)

        val blockedGraph = graph("select * from users where id in #{list}")
        val blocked = XmlMapperMethodParameterContractFactory.build(
            blockedGraph,
            mapper(blockedGraph, listOf(parameter(0, "java.util.Collection<java.lang.Long>", "ids", null))),
        )
        assertTrue(blocked.isPreparationBlocked)
        assertTrue(blocked.requirements.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", blocked.blockingProblems.single().code)
    }

    @Test
    fun soleUnannotatedArrayExposesArrayAlias() {
        val graph = graph("select * from users where id in #{array}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long[]", "ids", null)),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(InputShape.ARRAY, contract.requirements.single().expectedType.shape)
        assertEquals("array", contract.aliases.single().name)
        assertEquals(InputAliasKind.ARRAY, contract.aliases.single().kind)
    }

    @Test
    fun explicitParamDisablesSingleCollectionShortcuts() {
        val graph = graph("select * from users where id in #{collection}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
    }

    @Test
    fun multiParameterMethodDoesNotExposeSingleCollectionShortcuts() {
        val graph = graph("select * from users where id in #{list}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "java.util.List<java.lang.Long>", "ids", null),
                parameter(1, "long", "limit", null),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
    }

    @Test
    fun rawCollectionShortcutRemainsBlockedAsNonString() {
        val graph = graph("select " + raw("list") + " from users")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.util.List<java.lang.String>", "columns", null)),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertEquals(InputAliasKind.LIST, contract.aliases.single().kind)
        assertEquals("xml-raw-non-string-parameter", contract.blockingProblems.single().code)
        assertEquals(contract.requirements.single().id, contract.blockingProblems.single().requirementId)
    }

    @Test
    fun unsupportedCollectionSubtypeIsNotGuessedFromName() {
        val graph = graph("select * from users where id in #{collection}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.util.ArrayList<java.lang.Long>", "ids", null)),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
    }

    @Test
    fun sourceNameWithoutExplicitParamRemainsBlocked() {
        val graph = graph("select * from users where id = #{id}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long", "id", null)),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals(
            listOf("xml-caller-input-authority-unproven"),
            contract.blockingProblems.map { it.code },
        )
    }

    @Test
    fun unmatchedPlaceholderRemainsBlockedEvenWhenOtherParamExists() {
        val graph = graph("select * from users where id = #{missing}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long", "id", "id")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
    }

    @Test
    fun duplicateExplicitParamAliasIsAmbiguous() {
        val graph = graph("select * from users where id = #{same}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "first", "same"),
                parameter(1, "long", "second", "same"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        val problem = contract.blockingProblems.single()
        assertEquals(InputContractProblemKind.AMBIGUOUS, problem.kind)
        assertEquals("xml-duplicate-explicit-param-alias", problem.code)
        assertEquals(
            2,
            problem.provenance!!.evidence.filterIsInstance<InputEvidence.ExplicitParamAlias>().size,
        )
    }

    @Test
    fun repeatedPlaceholderUseCollapsesToOneRequirementAndRetainsAllUses() {
        val graph = graph("select * from users where id = #{id} or parent_id = #{id}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long", "id", "id")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        assertEquals(
            2,
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .size,
        )
    }

    @Test
    fun differentExplicitAliasesProduceIndependentRequirements() {
        val graph = graph("select * from users where id = #{id} and name = #{name}")
        val mapper = mapper(
            graph,
            listOf(
                parameter(0, "long", "id", "id"),
                parameter(1, "java.lang.String", "name", "name"),
            ),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(
            listOf("xml-java-param:0", "xml-java-param:1"),
            contract.requirements.map { it.id.value },
        )
        assertEquals(listOf("id", "name"), contract.aliases.map { it.name })
    }

    @Test
    fun mixedRawAndBoundUseOfOneAliasRemainsAmbiguous() {
        val graph = graph("select " + raw("value") + " from users where id = #{value}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "java.lang.String", "value", "value")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals("xml-mixed-raw-bound-input", contract.blockingProblems.single().code)
    }

    @Test
    fun rawInterpolationOfNonStringParameterIsUnsupported() {
        val graph = graph("select * from users limit " + raw("limit"))
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long", "limit", "limit")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        val problem = contract.blockingProblems.single()
        assertEquals("xml-raw-non-string-parameter", problem.code)
        assertEquals(contract.requirements.single().id, problem.requirementId)
    }

    @Test
    fun unknownJavaObjectShapeRetainsRequirementAndBlocks() {
        val graph = graph("select * from users where filter = #{filter}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "com.acme.Filter", "filter", "filter")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputShape.UNKNOWN, requirement.expectedType.shape)
        assertEquals(JavaTypeIdentity("com.acme.Filter"), requirement.expectedType.javaTypeIdentity)
        assertEquals("xml-unproven-parameter-shape", contract.blockingProblems.single().code)
        assertEquals(requirement.id, contract.blockingProblems.single().requirementId)
    }

    @Test
    fun mismatchedMapperCaptureIdentityBlocksAllAuthority() {
        val graph = graph("select * from users where id = #{id}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long", "id", "id")),
            statementId = XmlStatementId(XML_FILE, "com.acme.OtherMapper", "find"),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals("xml-mapper-method-authority-mismatch", contract.blockingProblems.single().code)
    }

    @Test
    fun xmlAndJavaSourceRevisionsAndRangesAreRetainedInProvenance() {
        val graph = graph("select * from users where id = #{id}")
        val mapper = mapper(
            graph,
            listOf(parameter(0, "long", "sourceId", "id")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, mapper)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(
            mapOf(
                XML_FILE to XML_REVISION,
                JAVA_FILE to JAVA_REVISION,
            ),
            contract.sourceRevisions,
        )

        val evidence = contract.requirements.single().provenance.evidence
        val mapperEvidence = evidence.filterIsInstance<InputEvidence.MapperMethodParameter>().single()
        assertEquals(JAVA_FILE, mapperEvidence.source.sourceFileId)
        assertEquals(JAVA_REVISION, mapperEvidence.source.sourceRevision)
        assertEquals(JAVA_METHOD_RANGE, mapperEvidence.source.sourceRange)

        val placeholder = evidence.filterIsInstance<InputEvidence.Placeholder>().single()
        assertEquals(XML_FILE, placeholder.source.sourceFileId)
        assertEquals(XML_REVISION, placeholder.source.sourceRevision)
        assertEquals(null, placeholder.source.sourceRange)
    }

    @Test
    fun mapperAuthorityCannotOverrideDependencyOrNestedXmlRefusal() {
        val base = graph("select * from users where id = #{id}")
        val dependentFile = SourceFileId("src/main/resources/com/acme/Common.xml")
        val dependentRevision = SourceRevision("document:101")
        val dependentGraph = StatementSourceGraph(
            rootStatement = base.rootStatement,
            sourceSnapshots = base.sourceSnapshots + SourceSnapshot(
                dependentFile,
                dependentRevision,
                "<mapper namespace=\"com.acme.Common\"><sql id=\"base\">id</sql></mapper>",
            ),
            dependencies = listOf(
                SourceDependencyEdge(
                    dependentFileId = XML_FILE,
                    requiredFileId = dependentFile,
                    referenceRange = SourceRange(0, 1),
                ),
            ),
        )
        val mapper = mapper(
            base,
            listOf(parameter(0, "long", "id", "id")),
        )

        val dependencyContract = XmlMapperMethodParameterContractFactory.build(dependentGraph, mapper)
        assertTrue(dependencyContract.isPreparationBlocked)
        assertTrue(dependencyContract.requirements.isEmpty())
        assertEquals(
            "xml-dependent-fragment-provenance-unsupported",
            dependencyContract.blockingProblems.single().code,
        )

        val nestedGraph = graph(
            "select * from users <if test=\"id != null\">where id = #{id}</if>",
        )
        val nestedContract = XmlMapperMethodParameterContractFactory.build(
            nestedGraph,
            mapper(nestedGraph, listOf(parameter(0, "long", "id", "id"))),
        )
        assertTrue(nestedContract.isPreparationBlocked)
        assertTrue(nestedContract.requirements.isEmpty())
        assertEquals(
            "xml-nested-element-input-discovery-unsupported",
            nestedContract.blockingProblems.single().code,
        )
    }

    @Test
    fun conflictingMapperSourceRevisionFailsClosedBeforeAuthorityWidening() {
        val graph = graph("select * from users where id = #{id}")
        val conflictingMapper = XmlMapperMethodCapture(
            statementId = graph.rootStatement.id as XmlStatementId,
            mapperSource = SourceSnapshot(
                XML_FILE,
                SourceRevision("document:conflict"),
                "x".repeat(256),
            ),
            methodSourceRange = JAVA_METHOD_RANGE,
            parameters = listOf(parameter(0, "long", "id", "id")),
        )

        val contract = XmlMapperMethodParameterContractFactory.build(graph, conflictingMapper)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals(
            "xml-mapper-method-source-revision-conflict",
            contract.blockingProblems.single().code,
        )
        assertEquals(mapOf(XML_FILE to XML_REVISION), contract.sourceRevisions)
    }

    @Test
    fun provenanceOnlyFactoryBehaviorRemainsUnchangedWithoutMapperAuthority() {
        val graph = graph("select * from users where id = #{id}")

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertEquals("xml-caller-input-authority-unproven", contract.blockingProblems.single().code)
        assertEquals(mapOf(XML_FILE to XML_REVISION), contract.sourceRevisions)
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
        statementId: XmlStatementId = graph.rootStatement.id as XmlStatementId,
    ): XmlMapperMethodCapture = XmlMapperMethodCapture(
        statementId = statementId,
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
        val XML_REVISION = SourceRevision("document:73")
        val JAVA_FILE = SourceFileId("src/main/java/com/acme/UserMapper.java")
        val JAVA_REVISION = SourceRevision("document:91")
        val JAVA_METHOD_RANGE = SourceRange(20, 120)
    }
}
