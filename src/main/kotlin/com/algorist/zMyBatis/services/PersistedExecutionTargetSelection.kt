package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.source.SourceFileId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Versioned project-scoped persistence payload for one source -> execution-target selection.
 *
 * The source association and target descriptor remain distinct domain authorities even though one
 * compact record stores them together. Project identity is provided by the project-level store and
 * is intentionally not serialized. No live Database Tools resource belongs in this type.
 */
internal data class PersistedExecutionTargetSelection(
    val association: SourceTargetAssociation,
    val descriptor: ExecutionTargetDescriptor,
) {
    init {
        require(association.targetId == descriptor.targetId) {
            "persisted source association and target descriptor must reference the same target"
        }
    }
}

internal object ExecutionTargetSelectionPersistenceFormat {
    const val VERSION = "v3"
    private const val NULL_DISPLAY_NAME = "~"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun selectionId(sourceFileId: SourceFileId): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceFileId.value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun encode(selection: PersistedExecutionTargetSelection): String {
        val targetId = selection.descriptor.targetId
        require(selection.association.targetId == targetId) {
            "persisted source association and target descriptor must reference the same target"
        }

        return listOf(
            VERSION,
            encodeField(selection.association.sourceFileId.value),
            encodeField(targetId.dataSourceId.value),
            encodeField(targetId.schema.value),
            selection.descriptor.dataSourceDisplayName?.let(::encodeField) ?: NULL_DISPLAY_NAME,
        ).joinToString("|")
    }

    fun decode(raw: String): PersistedExecutionTargetSelection? {
        val parts = raw.split('|', limit = 5)
        if (parts.size != 5 || parts[0] != VERSION) return null

        return try {
            val sourceFileId = SourceFileId(decodeField(parts[1]))
            val targetId = ExecutionTargetId(
                dataSourceId = StableDataSourceId(decodeField(parts[2])),
                schema = ExplicitSchemaIdentity(decodeField(parts[3])),
            )
            val displayName = when (parts[4]) {
                NULL_DISPLAY_NAME -> null
                else -> decodeField(parts[4])
            }

            PersistedExecutionTargetSelection(
                association = SourceTargetAssociation(sourceFileId, targetId),
                descriptor = ExecutionTargetDescriptor(targetId, displayName),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Converts a structurally valid v2 session only when the caller has already proved the
     * canonical source identity and it exactly matches the v2 mapper URL convention.
     */
    fun fromLegacyV2(
        session: PersistedConsoleSession,
        canonicalSourceFileId: SourceFileId?,
    ): PersistedExecutionTargetSelection? {
        val sourceFileId = canonicalSourceFileId ?: return null
        if (sourceFileId.value != "vfs:${session.mapperKey}") return null

        return try {
            val targetId = ExecutionTargetId(
                dataSourceId = StableDataSourceId(session.dataSourceId),
                schema = ExplicitSchemaIdentity(session.schemaName),
            )
            PersistedExecutionTargetSelection(
                association = SourceTargetAssociation(sourceFileId, targetId),
                descriptor = ExecutionTargetDescriptor(
                    targetId = targetId,
                    dataSourceDisplayName = session.dataSourceName.takeIf { it.isNotEmpty() },
                ),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun isValidSelectionId(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)
}
