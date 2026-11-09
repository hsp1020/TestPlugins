package com.hsp1020.elevate

import org.junit.Assert.*
import org.junit.Test

class ElevateWatchHistoryRulesTest {
    @Test fun seasonSelectionRejectsPrematureAndInvalidStates() {
        assertNull(ElevateWatchHistoryRules.selectedSeason(emptyList(), null, true))
        assertEquals(1, ElevateWatchHistoryRules.selectedSeason(emptyList(), null, false))
        assertNull(ElevateWatchHistoryRules.selectedSeason(listOf(1, 2), null, true))
        assertNull(ElevateWatchHistoryRules.selectedSeason(listOf(1, 2), -1, true))
        assertNull(ElevateWatchHistoryRules.selectedSeason(listOf(1, 2), 2, true))
        assertNull(ElevateWatchHistoryRules.selectedSeason(listOf(0, 2), 0, true))
        assertEquals(2, ElevateWatchHistoryRules.selectedSeason(listOf(1, 2), 1, true))
        for (season in 1..30) {
            assertEquals(season, ElevateWatchHistoryRules.selectedSeason(
                listOf(1, season, 32), 1, true
            ))
        }
    }

    @Test fun localizationAndSpecialSeasonNeverDependOnEnglishWordSeason() {
        for (label in listOf("Season S", "시즌 S", "シーズン S", "S", " s ", "Temporada S")) {
            assertTrue(ElevateWatchHistoryRules.specialSeasonLabel(label))
        }
        for (label in listOf("Season 0", "Season 1", "S1", "시즌 1", "Specials")) {
            assertFalse(ElevateWatchHistoryRules.specialSeasonLabel(label))
        }
    }

    @Test fun allSeriesDeletionRequiresOtherSeasonsToBeEmpty() {
        assertTrue(ElevateWatchHistoryRules.canRemoveWholeSeries(true, emptyMap()))
        assertTrue(ElevateWatchHistoryRules.canRemoveWholeSeries(true,
            mapOf(1 to emptySet(), 3 to emptySet())))
        assertFalse(ElevateWatchHistoryRules.canRemoveWholeSeries(false, emptyMap()))
        assertFalse(ElevateWatchHistoryRules.canRemoveWholeSeries(true,
            mapOf(1 to setOf(3), 3 to emptySet())))
        for (season in 1..25) {
            assertFalse(ElevateWatchHistoryRules.canRemoveWholeSeries(true,
                mapOf(season to setOf(1))))
        }
    }

    @Test fun deltasNeverTouchAnUnselectedOrUnknownEpisode() {
        val available = listOf(1, 2, 4, 7, 9)
        for (mask in 0 until (1 shl available.size)) {
            val watched = available.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            for (wanted in 0..available.size) {
                val delta = ElevateWatchHistoryRules.episodeDelta(available, watched, wanted)
                assertNotNull("mask=$mask wanted=$wanted", delta)
                assertTrue(delta!!.all { it in available })
                assertEquals(kotlin.math.abs(wanted - watched.size), delta.size)
                val after = if (wanted >= watched.size) watched + delta else watched - delta.toSet()
                assertEquals(wanted, after.size)
            }
        }
    }

    @Test fun malformedRemoteEpisodeDataBlocksWrites() {
        assertNull(ElevateWatchHistoryRules.episodeDelta(emptyList(), emptySet(), 0))
        assertNull(ElevateWatchHistoryRules.episodeDelta(listOf(1, 1), setOf(1), 0))
        assertNull(ElevateWatchHistoryRules.episodeDelta(listOf(0, 1), emptySet(), 1))
        assertNull(ElevateWatchHistoryRules.episodeDelta(listOf(1, 2), setOf(99), 0))
        assertNull(ElevateWatchHistoryRules.episodeDelta(listOf(1, 2), setOf(1), 3))
        assertNull(ElevateWatchHistoryRules.episodeDelta(listOf(1, 2), setOf(1), -1))
    }
}
