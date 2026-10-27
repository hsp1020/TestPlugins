package com.hsp1020

internal data class SubtitleRefreshCandidate(val id: String?, val language: String?, val url: String)

/** Candidates must come from the originating addon request, never a merged subtitle list. */
internal fun selectRefreshedSubtitleUrl(
    id: String?, language: String?, originalUrl: String, latestUrl: String,
    candidates: List<SubtitleRefreshCandidate>
): String? {
    val matching = candidates.filter {
        it.url.isNotBlank() && (id == null || it.id == id) &&
            (language == null || language.equals(it.language, true))
    }
    val exactUrls = matching.filter { it.url == latestUrl || it.url == originalUrl }
        .map { it.url }.distinct()
    if (exactUrls.size == 1) return exactUrls.single()
    if (exactUrls.size > 1) return null
    // A language or array position alone must never identify a changed URL.
    return if (id != null) matching.singleOrNull()?.url else null
}
