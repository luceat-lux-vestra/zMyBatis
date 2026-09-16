package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ForeachLocalRole
import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import java.util.ArrayDeque
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver
import org.apache.ibatis.parsing.XNode
import org.apache.ibatis.parsing.XPathParser
import org.apache.ibatis.scripting.xmltags.ForEachSqlNode

/**
 * Proves the source/contract authority needed before stock MyBatis is allowed to evaluate foreach.
 *
 * Generated `__frch_*` names are deliberately not authority here. This admission step reasons only
 * about authoritative source declarations and the supplied ParameterContract. The isolated MyBatis
 * runtime may later identify a generated mapping as belonging to one of these already-proven locals.
 */
internal object ForeachProvenanceAdmission {
    private const val MAX_SCRIPT_LENGTH = 2 * 1024 * 1024
    private const val MAX_DYNAMIC_NODES = 65_536
    private const val MAX_DYNAMIC_DEPTH = 64

    private const val SOURCE_CONTRACT_MISMATCH = "mybatis-foreach-source-contract-mismatch"
    private const val COLLECTION_PROVENANCE_MISSING = "mybatis-foreach-collection-provenance-missing"
    private const val LOCAL_PROVENANCE_MISSING = "mybatis-foreach-local-provenance-missing"
    private const val LOCAL_PROVENANCE_AMBIGUOUS = "mybatis-foreach-local-provenance-ambiguous"
    private const val LOCAL_SHADOWING = "mybatis-foreach-local-shadowing-unsupported"
    private const val GENERATED_NAMESPACE_COLLISION = "mybatis-foreach-generated-namespace-collision"
    private const val NESTING_UNSUPPORTED = "mybatis-foreach-nesting-unsupported"
    private const val LOCAL_IDENTIFIER_UNSUPPORTED = "mybatis-foreach-local-identifier-unsupported"
    private const val COLLECTION_EXPRESSION_UNSUPPORTED = "mybatis-foreach-collection-expression-unsupported"
    private const val PARSE_FAILURE = "mybatis-sql-source-parse-failure"

    private val simpleIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val reservedContextNames = setOf("_parameter", "_databaseId")

    fun inspect(script: String, contract: ParameterContract): Result {
        if (script.length > MAX_SCRIPT_LENGTH) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, COLLECTION_EXPRESSION_UNSUPPORTED)
        }

        val root = try {
            XPathParser(script, false, null, XMLMapperEntityResolver()).evalNode("/script")
        } catch (_: StackOverflowError) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, NESTING_UNSUPPORTED)
        } catch (failure: RuntimeException) {
            return failed(
                PreparationFailureKind.MYBATIS_PARSE,
                PARSE_FAILURE,
                diagnosticType = failure.javaClass.name,
            )
        } ?: return failed(
            PreparationFailureKind.MYBATIS_PARSE,
            PARSE_FAILURE,
            diagnosticType = IllegalArgumentException::class.java.name,
        )

        val callerAliases = contract.aliases.associateBy { it.name }
        val bindNames = contract.internalBindings
            .filter { it.kind == InternalBindingKind.BIND }
            .mapTo(linkedSetOf()) { it.name }
        val foreachContractBindings = contract.internalBindings.filter {
            it.kind == InternalBindingKind.FOREACH_ITEM || it.kind == InternalBindingKind.FOREACH_INDEX
        }

        val sourceLocals = linkedMapOf<String, InternalBindingKind>()
        val collectionRequirements = linkedSetOf<InputRequirementId>()
        val pending = ArrayDeque<PendingNode>()
        pending.addLast(PendingNode(root, depth = 0, insideForeach = false))
        var visitedNodes = 0
        var foreachCount = 0

        while (pending.isNotEmpty()) {
            if (++visitedNodes > MAX_DYNAMIC_NODES) {
                return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, COLLECTION_EXPRESSION_UNSUPPORTED)
            }
            val current = pending.removeLast()
            if (current.depth > MAX_DYNAMIC_DEPTH) {
                return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, NESTING_UNSUPPORTED)
            }

            val isForeach = current.node.name == "foreach"
            if (isForeach && current.insideForeach) {
                return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, NESTING_UNSUPPORTED)
            }
            if (isForeach) {
                foreachCount++
                val admitted = inspectForeach(
                    node = current.node,
                    contract = contract,
                    callerAliases = callerAliases,
                    bindNames = bindNames,
                    sourceLocals = sourceLocals,
                    collectionRequirements = collectionRequirements,
                )
                if (admitted != null) return Result.Failed(admitted)
            }

            val childInsideForeach = current.insideForeach || isForeach
            val children = current.node.children
            for (index in children.indices.reversed()) {
                pending.addLast(
                    PendingNode(
                        node = children[index],
                        depth = current.depth + 1,
                        insideForeach = childInsideForeach,
                    ),
                )
            }
        }

        val sourceAuthority = sourceLocals.mapTo(linkedSetOf()) { it.key to it.value }
        val contractAuthority = foreachContractBindings.mapTo(linkedSetOf()) { it.name to it.kind }
        if (sourceAuthority != contractAuthority) {
            return failed(PreparationFailureKind.BINDING_RESOLUTION, SOURCE_CONTRACT_MISMATCH)
        }

        if (foreachCount > 0) {
            val generatedPrefix = ForEachSqlNode.ITEM_PREFIX
            val namespaceCollision =
                script.contains(generatedPrefix) ||
                    callerAliases.keys.any { it.startsWith(generatedPrefix) } ||
                    bindNames.any { it.startsWith(generatedPrefix) } ||
                    sourceLocals.keys.any { it.startsWith(generatedPrefix) }
            if (namespaceCollision) {
                return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, GENERATED_NAMESPACE_COLLISION)
            }
        }

        return Result.Admitted(
            locals = sourceLocals.toMap(),
            collectionRequirementIds = collectionRequirements.toSet(),
        )
    }

    private fun inspectForeach(
        node: XNode,
        contract: ParameterContract,
        callerAliases: Map<String, InputAlias>,
        bindNames: Set<String>,
        sourceLocals: MutableMap<String, InternalBindingKind>,
        collectionRequirements: MutableSet<InputRequirementId>,
    ): PreparationFailure? {
        val collection = node.getStringAttribute("collection")?.takeIf { it.isNotBlank() }
            ?: return failure(PreparationFailureKind.UNSUPPORTED_SEMANTIC, COLLECTION_EXPRESSION_UNSUPPORTED)
        if (!simpleIdentifier.matches(collection) || collection in reservedContextNames) {
            return failure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                COLLECTION_EXPRESSION_UNSUPPORTED,
                collection,
            )
        }
        val alias = callerAliases[collection]
            ?: return failure(
                PreparationFailureKind.BINDING_RESOLUTION,
                COLLECTION_PROVENANCE_MISSING,
                collection,
            )
        val requirement = contract.requirement(alias.requirementId)
            ?: return failure(
                PreparationFailureKind.BINDING_RESOLUTION,
                COLLECTION_PROVENANCE_MISSING,
                collection,
            )
        val collectionEvidence = requirement.provenance.evidence.filterIsInstance<InputEvidence.ForeachCollection>()
        if (collectionEvidence.size != 1 || collectionEvidence.single().expression != collection) {
            return failure(
                PreparationFailureKind.BINDING_RESOLUTION,
                COLLECTION_PROVENANCE_MISSING,
                collection,
            )
        }
        collectionRequirements += requirement.id

        val item = node.getStringAttribute("item")?.takeIf { it.isNotBlank() }
            ?: return failure(PreparationFailureKind.UNSUPPORTED_SEMANTIC, LOCAL_IDENTIFIER_UNSUPPORTED)
        val itemFailure = inspectLocal(
            name = item,
            expectedKind = InternalBindingKind.FOREACH_ITEM,
            expectedRole = ForeachLocalRole.ITEM,
            contract = contract,
            callerAliases = callerAliases.keys,
            bindNames = bindNames,
            sourceLocals = sourceLocals,
        )
        if (itemFailure != null) return itemFailure

        val index = node.getStringAttribute("index")?.takeIf { it.isNotBlank() }
        if (index != null) {
            return inspectLocal(
                name = index,
                expectedKind = InternalBindingKind.FOREACH_INDEX,
                expectedRole = ForeachLocalRole.INDEX,
                contract = contract,
                callerAliases = callerAliases.keys,
                bindNames = bindNames,
                sourceLocals = sourceLocals,
            )
        }
        return null
    }

    private fun inspectLocal(
        name: String,
        expectedKind: InternalBindingKind,
        expectedRole: ForeachLocalRole,
        contract: ParameterContract,
        callerAliases: Set<String>,
        bindNames: Set<String>,
        sourceLocals: MutableMap<String, InternalBindingKind>,
    ): PreparationFailure? {
        if (!simpleIdentifier.matches(name) || name in reservedContextNames) {
            return failure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                LOCAL_IDENTIFIER_UNSUPPORTED,
                name,
            )
        }
        if (name in callerAliases || name in bindNames || sourceLocals.containsKey(name)) {
            return failure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                LOCAL_SHADOWING,
                name,
            )
        }

        val candidates = contract.internalBindings.filter { it.name == name }
        if (candidates.isEmpty()) {
            return failure(
                PreparationFailureKind.BINDING_RESOLUTION,
                LOCAL_PROVENANCE_MISSING,
                name,
            )
        }
        if (candidates.size != 1) {
            return failure(
                PreparationFailureKind.BINDING_RESOLUTION,
                LOCAL_PROVENANCE_AMBIGUOUS,
                name,
            )
        }
        val binding = candidates.single()
        if (binding.kind != expectedKind) {
            return failure(
                PreparationFailureKind.BINDING_RESOLUTION,
                SOURCE_CONTRACT_MISMATCH,
                name,
            )
        }
        val evidence = binding.provenance.evidence.filterIsInstance<InputEvidence.ForeachLocal>()
        if (
            evidence.size != 1 ||
            evidence.single().name != name ||
            evidence.single().role != expectedRole
        ) {
            return failure(
                PreparationFailureKind.BINDING_RESOLUTION,
                SOURCE_CONTRACT_MISMATCH,
                name,
            )
        }

        sourceLocals[name] = expectedKind
        return null
    }

    private fun failed(
        kind: PreparationFailureKind,
        code: String,
        property: String? = null,
        diagnosticType: String? = null,
    ) = Result.Failed(failure(kind, code, property, diagnosticType))

    private fun failure(
        kind: PreparationFailureKind,
        code: String,
        property: String? = null,
        diagnosticType: String? = null,
    ) = PreparationFailure(kind, code, property, diagnosticType)

    private data class PendingNode(
        val node: XNode,
        val depth: Int,
        val insideForeach: Boolean,
    )

    sealed interface Result {
        data class Admitted(
            val locals: Map<String, InternalBindingKind>,
            val collectionRequirementIds: Set<InputRequirementId>,
        ) : Result

        data class Failed(
            val failure: PreparationFailure,
        ) : Result
    }
}
