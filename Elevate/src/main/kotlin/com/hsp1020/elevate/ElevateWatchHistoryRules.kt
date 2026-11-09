package com.hsp1020.elevate

/** Pure, deterministic safety rules shared by the season-history UI and JVM regression tests. */
internal object ElevateWatchHistoryRules {
    internal enum class MediaMode { MOVIE, GENERAL_SERIES, SINGLE_ANIME, SEASONAL_ANIME }

    fun mode(movie: Boolean, anime: Boolean, seasons: Int): MediaMode = when {
        movie -> MediaMode.MOVIE
        !anime -> MediaMode.GENERAL_SERIES
        seasons > 1 -> MediaMode.SEASONAL_ANIME
        else -> MediaMode.SINGLE_ANIME
    }

    /** StremioC selects the first valid IMDb, TVDB, Trakt or TMDB season. */
    fun preferredSeason(claims: List<Int?>): Int? = claims.firstOrNull { it != null && it > 0 }

    /** StremioC accepts season 1 or any matching provider claim for later seasons. */
    fun consistentSeason(season: Int, claims: Collection<Int?>): Boolean =
        season > 0 && (season == 1 || claims.any { it == season })

    /** A standalone MAL/Kitsu/AniList page is already a specific anime season. */
    fun originalSeasonId(original: String?, singleSeason: Boolean): Map<String, String> {
        if (!singleSeason) return emptyMap()
        val found = original?.let {
            Regex("^(mal|anilist|kitsu):([0-9]+)$").matchEntire(it)
        } ?: return emptyMap()
        val id = found.groupValues[2].toLongOrNull()?.takeIf { it > 0L }
            ?: return emptyMap()
        return mapOf(found.groupValues[1] to id.toString())
    }

    fun selectedSeason(seasons: List<Int>, index: Int?, episodeBased: Boolean): Int? {
        val selected = if (seasons.isNotEmpty()) index?.let { seasons.getOrNull(it) }
            else if (!episodeBased) 1 else null
        return selected?.takeIf { it > 0 }
    }

    /** Keep IDs found in this season even when another season shares them. */
    fun seasonIdsFor(season: Int, bySeason: Map<Int, Map<String, String>>)
        : Map<String, String> = bySeason[season].orEmpty().filterValues { it.isNotBlank() }

    fun specialSeasonLabel(label: String): Boolean =
        label.trim().let { it.equals("S", ignoreCase = true) || it.endsWith(" S") }

    fun canRemoveWholeSeries(
        clearSelectedSeason: Boolean, otherSeasons: Map<Int, Set<Int>>
    ): Boolean = clearSelectedSeason && otherSeasons.values.all { it.isEmpty() }

    /** Completion must use the selected provider's own title/season episode count. */
    fun verifiedCompletionEpisodes(provider: String, total: Int?, source: String): Int? =
        total?.takeIf { count ->
            count > 0 && when (provider) {
                "kitsu" -> source == "kitsu-episodeCount"
                "anilist" -> source == "provider-metadata" || source == "provider-status"
                else -> false
            }
        }

    fun removedLibraryEntryMatches(none: Boolean, watched: Int?): Boolean =
        none && (watched ?: 0) == 0

    fun completedReadbackMatches(total: Int?, completed: Boolean, watched: Int?): Boolean =
        total != null && total > 0 && completed && watched == total

    /** The returned delta consists exclusively of verified episode keys in the selected season. */
    fun episodeDelta(
        available: List<Int>, watched: Set<Int>, desiredWatched: Int
    ): List<Int>? {
        if (available.isEmpty() || available.any { it <= 0 } ||
            available.distinct().size != available.size ||
            watched.any { it !in available } || desiredWatched !in 0..available.size) return null
        val diff = desiredWatched - watched.size
        val delta = when {
            diff > 0 -> available.filterNot { it in watched }.take(diff)
            diff < 0 -> available.filter { it in watched }.takeLast(-diff)
            else -> emptyList()
        }
        return delta.takeIf { it.size == kotlin.math.abs(diff) }
    }
}
