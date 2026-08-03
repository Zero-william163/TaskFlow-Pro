package com.taskflow.app.update

/**
 * Lightweight semantic version parser. Handles strings like `1.9.3`, `v1.9.3`,
 * `1.9.3-beta.1`. Comparison uses major.minor.patch, falling back to numeric segment
 * comparison for any extra components.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: String? = null
) : Comparable<SemanticVersion> {

    val versionName: String
        get() = buildString {
            append(major).append('.').append(minor).append('.').append(patch)
            if (!suffix.isNullOrBlank()) append(suffix)
        }

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // A version with no suffix is newer than one with a pre-release suffix.
        return when {
            suffix == null && other.suffix == null -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    companion object {
        fun parse(raw: String?): SemanticVersion? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            val main = cleaned.substringBefore('-', cleaned)
            val suffix = if (cleaned.contains('-')) "-" + cleaned.substringAfter('-') else null
            val parts = main.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.isEmpty()) return null
            val major = parts.getOrNull(0) ?: 0
            val minor = parts.getOrNull(1) ?: 0
            val patch = parts.getOrNull(2) ?: 0
            return SemanticVersion(major, minor, patch, suffix)
        }
    }
}
