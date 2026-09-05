package com.algorist.zMyBatis.services

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Versioned, project-scoped persistence record for a cached console session.
 *
 * Project identity is provided by the project-level PropertiesComponent that stores this record;
 * it is intentionally not serialized into the value. Datasource identity is the IDE-assigned
 * stable UUID, never the mutable display name. Restart persistence also requires an explicit schema
 * name; "Use Default Schema" has no stable schema identity across IDE restarts.
 */
internal data class PersistedConsoleSession(
    val mapperKey: String,
    val dataSourceId: String,
    val dataSourceName: String,
    val schemaName: String
)

internal object ConsoleSessionPersistenceFormat {
    const val VERSION = "v2"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun sessionId(mapperKey: String): String {
        require(mapperKey.isNotBlank()) { "mapperKey must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mapperKey.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun encode(session: PersistedConsoleSession): String {
        require(session.mapperKey.isNotBlank()) { "mapperKey must not be blank" }
        require(session.dataSourceId.isNotBlank()) { "dataSourceId must not be blank" }
        require(session.schemaName.isNotBlank()) { "schemaName must not be blank" }
        return listOf(
            VERSION,
            encodeField(session.mapperKey),
            encodeField(session.dataSourceId),
            encodeField(session.dataSourceName),
            encodeField(session.schemaName)
        ).joinToString("|")
    }

    fun decode(raw: String): PersistedConsoleSession? {
        val parts = raw.split('|', limit = 5)
        if (parts.size != 5 || parts[0] != VERSION) return null
        return try {
            val mapperKey = decodeField(parts[1])
            val dataSourceId = decodeField(parts[2])
            val schemaName = decodeField(parts[4])
            if (mapperKey.isBlank() || dataSourceId.isBlank() || schemaName.isBlank()) return null
            PersistedConsoleSession(
                mapperKey = mapperKey,
                dataSourceId = dataSourceId,
                dataSourceName = decodeField(parts[3]),
                schemaName = schemaName
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun isValidSessionId(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)
}
