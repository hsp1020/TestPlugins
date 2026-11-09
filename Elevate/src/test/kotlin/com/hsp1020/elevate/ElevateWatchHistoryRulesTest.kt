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
        assertFalse(ElevateWatchHistoryRules.canRemoveWholeSeries(true,
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

    @Test fun seasonIdsDoNotLeakAcrossEpisodesOrSeasons() {
        val mapping = mapOf(
            1 to mapOf("kitsu" to "47083", "mal" to "123", "anilist" to "55"),
            2 to mapOf("kitsu" to "50021", "mal" to "456", "anilist" to "55"),
            3 to mapOf("kitsu" to "50022", "mal" to "789"))
        assertEquals(mapOf("kitsu" to "47083", "mal" to "123"),
            ElevateWatchHistoryRules.independentSeasonIds(1, mapping))
        assertEquals(mapOf("kitsu" to "50021", "mal" to "456"),
            ElevateWatchHistoryRules.independentSeasonIds(2, mapping))
        assertEquals(mapOf("kitsu" to "50022", "mal" to "789"),
            ElevateWatchHistoryRules.independentSeasonIds(3, mapping))
        assertTrue(ElevateWatchHistoryRules.independentSeasonIds(4, mapping).isEmpty())
        assertEquals(mapOf("kitsu" to "50021"),
            ElevateWatchHistoryRules.independentSeasonIds(1,
                mapOf(1 to mapOf("kitsu" to "50021"))))
    }

    @Test fun allProviderModesIncludeMoviesAndSingleSeasonSeries() {
        val rules = ElevateWatchHistoryRules
        assertEquals(ElevateWatchHistoryRules.MediaMode.MOVIE,
            rules.mode(movie = true, anime = false, seasons = 0))
        assertEquals(ElevateWatchHistoryRules.MediaMode.MOVIE,
            rules.mode(movie = true, anime = true, seasons = 1))
        assertEquals(ElevateWatchHistoryRules.MediaMode.GENERAL_SERIES,
            rules.mode(movie = false, anime = false, seasons = 1))
        assertEquals(ElevateWatchHistoryRules.MediaMode.GENERAL_SERIES,
            rules.mode(movie = false, anime = false, seasons = 12))
        assertEquals(ElevateWatchHistoryRules.MediaMode.SINGLE_ANIME,
            rules.mode(movie = false, anime = true, seasons = 1))
        assertEquals(ElevateWatchHistoryRules.MediaMode.SEASONAL_ANIME,
            rules.mode(movie = false, anime = true, seasons = 2))
    }

    @Test fun mapMustNotMixDifferentSeasonsOrDifferentProviderIds() {
        val rules = ElevateWatchHistoryRules
        for (season in 1..40) {
            assertTrue(rules.consistentSeason(season, listOf(season, null, season)))
            assertFalse(rules.consistentSeason(season, listOf(null, null)))
            assertFalse(rules.consistentSeason(season, listOf(season, season + 1)))
            assertFalse(rules.consistentSeason(season, listOf(season + 1)))
            assertTrue(rules.compatibleIds(
                mapOf("mal" to "$season", "kitsu" to "${season + 1000}"),
                mapOf("mal" to "$season")))
            assertFalse(rules.compatibleIds(
                mapOf("mal" to "$season", "kitsu" to "${season + 1000}"),
                mapOf("mal" to "${season + 1}")))
        }
    }

    @Test fun standaloneKitsuAndMalSeasonIdsAreNotReassignedToSeasonOne() {
        val rules = ElevateWatchHistoryRules
        assertEquals(mapOf("kitsu" to "50021"),
            rules.originalSeasonId("kitsu:50021", singleSeason = true))
        assertEquals(mapOf("mal" to "76543"),
            rules.originalSeasonId("mal:76543", singleSeason = true))
        assertEquals(mapOf("anilist" to "12345"),
            rules.originalSeasonId("anilist:12345", singleSeason = true))
        assertTrue(rules.originalSeasonId("kitsu:50021", singleSeason = false).isEmpty())
        assertTrue(rules.originalSeasonId("kitsu:0", singleSeason = true).isEmpty())
        assertTrue(rules.originalSeasonId("tt123456", singleSeason = true).isEmpty())
        assertTrue(rules.originalSeasonId("kitsu:invalid", singleSeason = true).isEmpty())
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
