package com.architect.driveaggregator.update

object SemVerComparator : Comparator<String> {
    override fun compare(v1: String, v2: String): Int {
        val s1 = SemVer.parse(v1)
        val s2 = SemVer.parse(v2)
        return s1.compareTo(s2)
    }

    fun isGreater(latest: String, current: String): Boolean {
        return compare(latest, current) > 0
    }
}
