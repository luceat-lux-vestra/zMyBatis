package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind

/**
 * Rejects ParameterMapping options that would require loading arbitrary application code.
 *
 * Standard MyBatis mapping semantics remain stock-owned. Explicit stock MyBatis TypeHandlers and
 * javaType/jdbcType metadata continue to the isolated MyBatis runtime.
 */
internal object MyBatisParameterMappingCapabilityAdmission {
    private const val CUSTOM_TYPE_HANDLER_UNSUPPORTED = "mybatis-custom-type-handler-unsupported"
    private val typeHandlerOption = Regex(
        pattern = """(?:^|,)\s*typeHandler\s*=\s*([^,}\s]+)""",
        option = RegexOption.IGNORE_CASE,
    )

    fun failureForSourceTexts(texts: Iterable<String>): PreparationFailure? {
        for (text in texts) {
            failureForSourceText(text)?.let { return it }
        }
        return null
    }

    fun failureForSourceText(text: String): PreparationFailure? {
        var cursor = 0
        while (cursor < text.length - 1) {
            val open = text.indexOf("#{", startIndex = cursor)
            if (open < 0) return null
            if (open > 0 && text[open - 1] == '\\') {
                cursor = open + 2
                continue
            }
            val close = text.indexOf('}', startIndex = open + 2)
            if (close < 0) return null

            val payload = text.substring(open + 2, close)
            val handler = typeHandlerOption.find(payload)
                ?.groupValues
                ?.get(1)
                ?.trim()
            if (handler != null && !handler.startsWith("org.apache.ibatis.type.")) {
                return PreparationFailure(
                    kind = PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER,
                    code = CUSTOM_TYPE_HANDLER_UNSUPPORTED,
                )
            }
            cursor = close + 1
        }
        return null
    }
}
