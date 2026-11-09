package com.hsp1020.elevate

/** Pure, deterministic safety rules shared by the season-history UI and JVM regression tests. */
internal object ElevateWatchHistoryRules {
    fun selectedSeason(seasons: List<Int>, index: Int?, episodeBased: Boolean): Int? {
        val selected = if (seasons.isNotEmpty()) index?.let { seasons.getOrNull(it) }
            else if (!episodeBased) 1 else null
        return selected?.takeIf { it > 0 }
    }

    fun specialSeasonLabel(label: String): Boolean =
        label.trim().let { it.equals("S", ignoreCase = true) || it.endsWith(" S") }

    fun canRemoveWholeSeries(
        clearSelectedSeason: Boolean, otherSeasons: Map<Int, Set<Int>>
    ): Boolean = clearSelectedSeason && otherSeasons.values.all { it.isEmpty() }

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
