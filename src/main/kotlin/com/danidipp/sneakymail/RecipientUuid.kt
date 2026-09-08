package com.danidipp.sneakymail

import java.util.UUID

private val COMPACT_UUID = Regex("^[0-9a-fA-F]{32}$")
private val CANONICAL_UUID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

internal fun recipientUuidOrNull(value: String?): UUID? {
    val candidate = value?.trim()?.takeUnless {
        it.isEmpty() || it.equals("null", ignoreCase = true)
    } ?: return null

    val normalized = if (COMPACT_UUID.matches(candidate)) {
        candidate.replaceFirst(
            "(.{8})(.{4})(.{4})(.{4})(.{12})".toRegex(),
            "$1-$2-$3-$4-$5"
        )
    } else {
        candidate
    }

    if (!CANONICAL_UUID.matches(normalized)) return null
    return runCatching { UUID.fromString(normalized) }.getOrNull()
}

internal fun resolveRecipientUuid(
    value: String?,
    recipientName: String?,
    lookupByName: (String) -> UUID?
): UUID? {
    recipientUuidOrNull(value)?.let { return it }
    if (!value.isMissingRecipientUuid()) return null

    val name = recipientName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return lookupByName(name)
}

internal fun String?.isMissingRecipientUuid(): Boolean {
    val candidate = this?.trim()
    return candidate.isNullOrEmpty() || candidate.equals("null", ignoreCase = true)
}
