package com.famelack.tv

import com.famelack.tv.util.OtaManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaManagerTest {

    @Test
    fun testIsNewer_greaterMinor_returnsTrue() {
        assertTrue(OtaManager.isNewer("1.2.0", "1.1.0"))
    }

    @Test
    fun testIsNewer_greaterMajor_returnsTrue() {
        assertTrue(OtaManager.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun testIsNewer_greaterPatch_returnsTrue() {
        assertTrue(OtaManager.isNewer("1.1.1", "1.1.0"))
    }

    @Test
    fun testIsNewer_vPrefix_handledCorrectly() {
        assertTrue(OtaManager.isNewer("v1.2.0", "1.1.0"))
        assertTrue(OtaManager.isNewer("V1.2.0", "v1.1.0"))
    }

    @Test
    fun testIsNewer_equalVersions_returnsFalse() {
        assertFalse(OtaManager.isNewer("1.1.0", "1.1.0"))
        assertFalse(OtaManager.isNewer("v1.1.0", "1.1.0"))
    }

    @Test
    fun testIsNewer_olderVersion_returnsFalse() {
        assertFalse(OtaManager.isNewer("1.0.9", "1.1.0"))
        assertFalse(OtaManager.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun testIsNewer_differentLengthVersions() {
        assertTrue(OtaManager.isNewer("1.2", "1.1.5"))
        assertFalse(OtaManager.isNewer("1.1", "1.1.0"))
    }
}

