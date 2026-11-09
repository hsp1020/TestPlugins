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

    @Test fun allSeriesDeletionRequiresOtherSeasonsToHaveNoWatchedEpisodes() {
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

    @Test fun seasonIdsDoNotLeakAcrossEpisodesOrSeasons() {
        val mapping = mapOf(
            1 to mapOf("kitsu" to "47083", "mal" to "123", "anilist" to "55"),
            2 to mapOf("kitsu" to "50021", "mal" to "456", "anilist" to "55"),
            3 to mapOf("kitsu" to "50022", "mal" to "789"))
        assertEquals(mapOf("kitsu" to "47083", "mal" to "123", "anilist" to "55"),
            ElevateWatchHistoryRules.seasonIdsFor(1, mapping))
        assertEquals(mapOf("kitsu" to "50021", "mal" to "456", "anilist" to "55"),
            ElevateWatchHistoryRules.seasonIdsFor(2, mapping))
        assertEquals(mapOf("kitsu" to "50022", "mal" to "789"),
            ElevateWatchHistoryRules.seasonIdsFor(3, mapping))
        assertTrue(ElevateWatchHistoryRules.seasonIdsFor(4, mapping).isEmpty())
        assertEquals(mapOf("kitsu" to "50021"),
            ElevateWatchHistoryRules.seasonIdsFor(1,
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

    @Test fun seasonMappingPreservesOriginalSourcePriorityAndTrackerChecks() {
        val rules = ElevateWatchHistoryRules
        assertEquals(2, rules.preferredSeason(listOf(2, 3, 3, 3)))
        assertEquals(3, rules.preferredSeason(listOf(null, 3, 2, 2)))
        assertEquals(4, rules.preferredSeason(listOf(0, null, 4, 5)))
        assertNull(rules.preferredSeason(listOf(null, 0, -1)))
        assertTrue(rules.consistentSeason(1, listOf(null, null)))
        assertTrue(rules.consistentSeason(1, listOf(2, 2)))
        assertFalse(rules.consistentSeason(0, listOf(0)))
        for (season in 2..40) {
            assertTrue(rules.consistentSeason(season, listOf(season, null, season)))
            assertTrue(rules.consistentSeason(season, listOf(season + 1, season)))
            assertFalse(rules.consistentSeason(season, listOf(null, null)))
            assertFalse(rules.consistentSeason(season, listOf(season + 1)))
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

    @Test fun completionNeverUsesOtherProvidersOrUnverifiedEpisodeCounts() {
        val rules = ElevateWatchHistoryRules
        assertEquals(12, rules.verifiedCompletionEpisodes("kitsu", 12, "kitsu-episodeCount"))
        assertEquals(12, rules.verifiedCompletionEpisodes("anilist", 12, "provider-metadata"))
        assertEquals(12, rules.verifiedCompletionEpisodes("anilist", 12, "provider-status"))
        for (provider in listOf("kitsu", "anilist")) {
            for (source in listOf("season-mapped-peer", "single-season-peer", "unknown")) {
                assertNull(rules.verifiedCompletionEpisodes(provider, 12, source))
            }
            assertNull(rules.verifiedCompletionEpisodes(provider, 0,
                if (provider == "kitsu") "kitsu-episodeCount" else "provider-metadata"))
        }
        assertNull(rules.verifiedCompletionEpisodes("kitsu", 12, "provider-status"))
        assertNull(rules.verifiedCompletionEpisodes("anilist", 12, "kitsu-episodeCount"))
        assertNull(rules.verifiedCompletionEpisodes("mal", 12, "mal-lightweight"))
        assertNull(rules.verifiedCompletionEpisodes("simkl", 12, "simkl-season-episode-keys"))
    }

    @Test fun completedReadbackRequiresCompletedStateAndFullProgress() {
        val rules = ElevateWatchHistoryRules
        assertTrue(rules.completedReadbackMatches(12, true, 12))
        assertFalse(rules.completedReadbackMatches(12, true, 0))
        assertFalse(rules.completedReadbackMatches(12, true, 11))
        assertFalse(rules.completedReadbackMatches(12, false, 12))
        assertFalse(rules.completedReadbackMatches(12, true, null))
        assertFalse(rules.completedReadbackMatches(null, true, 12))
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
