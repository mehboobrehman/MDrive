package com.architect.driveaggregator.update

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String = "",
    val buildMetadata: String = ""
) : Comparable<SemVer> {
    companion object {
        fun parse(versionStr: String): SemVer {
            var clean = versionStr.trim()
            if (clean.startsWith('v') || clean.startsWith('V')) {
                clean = clean.substring(1)
            }
            val buildSplit = clean.split('+', limit = 2)
            val coreAndPre = buildSplit[0]
            val buildMeta = if (buildSplit.size > 1) buildSplit[1] else ""

            val preSplit = coreAndPre.split('-', limit = 2)
            val core = preSplit[0]
            val pre = if (preSplit.size > 1) preSplit[1] else ""

            val parts = core.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

            return SemVer(major, minor, patch, pre, buildMeta)
        }
    }

    override fun compareTo(other: SemVer): Int {
        if (this.major != other.major) return this.major.compareTo(other.major)
        if (this.minor != other.minor) return this.minor.compareTo(other.minor)
        if (this.patch != other.patch) return this.patch.compareTo(other.patch)

        if (this.preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (this.preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
        if (this.preRelease.isEmpty() && other.preRelease.isEmpty()) return 0

        return comparePreRelease(this.preRelease, other.preRelease)
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val aParts = a.split('.')
        val bParts = b.split('.')
        val maxLen = maxOf(aParts.size, bParts.size)
        for (i in 0 until maxLen) {
            val partA = aParts.getOrNull(i)
            val partB = bParts.getOrNull(i)
            if (partA == null) return -1
            if (partB == null) return 1

            val numA = partA.toIntOrNull()
            val numB = partB.toIntOrNull()

            val cmp = if (numA != null && numB != null) {
                numA.compareTo(numB)
            } else if (numA != null) {
                -1
            } else if (numB != null) {
                1
            } else {
                partA.compareTo(partB)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
