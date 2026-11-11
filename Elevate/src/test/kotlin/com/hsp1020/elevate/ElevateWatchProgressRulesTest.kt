package com.hsp1020.elevate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevateWatchProgressRulesTest {
    @Test fun explicitCatalogProgressPolicyIsDistinctFromAbsentPolicy() {
        assertEquals(true, ElevateWatchProgressRules.flag("true"))
        assertEquals(false, ElevateWatchProgressRules.flag("false"))
        assertEquals(false, ElevateWatchProgressRules.flag(" FALSE "))
        assertNull(ElevateWatchProgressRules.flag(null))
        assertNull(ElevateWatchProgressRules.flag(""))
        assertNull(ElevateWatchProgressRules.flag("off"))
    }
}
