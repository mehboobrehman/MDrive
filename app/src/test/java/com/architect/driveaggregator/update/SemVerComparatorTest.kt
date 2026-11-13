package com.architect.driveaggregator.update

import org.junit.Assert.*
import org.junit.Test

class SemVerComparatorTest {

    @Test
    fun testParseSimpleVersion() {
        val semVer = SemVer.parse("1.0.23")
        assertEquals(1, semVer.major)
        assertEquals(0, semVer.minor)
        assertEquals(23, semVer.patch)
        assertEquals("", semVer.preRelease)
        assertEquals("", semVer.buildMetadata)
    }

    @Test
    fun testParseFullVersionWithVPrefixAndMetadata() {
        val semVer = SemVer.parse("v2.1.4-alpha.1+build.123")
        assertEquals(2, semVer.major)
        assertEquals(1, semVer.minor)
        assertEquals(4, semVer.patch)
        assertEquals("alpha.1", semVer.preRelease)
        assertEquals("build.123", semVer.buildMetadata)
    }

    @Test
    fun testComparisonMajorMinorPatch() {
        assertTrue(SemVerComparator.isGreater("1.1.0", "1.0.23"))
        assertTrue(SemVerComparator.isGreater("2.0.0", "1.9.9"))
        assertTrue(SemVerComparator.isGreater("1.0.24", "1.0.23"))
        assertFalse(SemVerComparator.isGreater("1.0.23", "1.0.23"))
        assertFalse(SemVerComparator.isGreater("1.0.22", "1.0.23"))
    }

    @Test
    fun testPreReleaseComparison() {
        assertTrue(SemVerComparator.isGreater("1.0.0", "1.0.0-alpha"))
        assertTrue(SemVerComparator.isGreater("1.0.0-beta", "1.0.0-alpha"))
        assertTrue(SemVerComparator.isGreater("1.0.0-beta.2", "1.0.0-beta.1"))
        assertTrue(SemVerComparator.isGreater("1.0.0", "1.0.0-beta.2"))
    }
}
