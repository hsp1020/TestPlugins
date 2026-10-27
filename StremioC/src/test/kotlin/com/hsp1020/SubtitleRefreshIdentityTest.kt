package com.hsp1020

import org.junit.Assert.*
import org.junit.Test

class SubtitleRefreshIdentityTest {
    @Test fun stableIdFollowsChangedUrl() {
        assertEquals("new", selectRefreshedSubtitleUrl("a", "eng", "old", "old",
            listOf(SubtitleRefreshCandidate("b", "eng", "other"), SubtitleRefreshCandidate("a", "eng", "new"))))
    }

    @Test fun absentIdNeverFallsBackToLanguageOrPosition() {
        assertNull(selectRefreshedSubtitleUrl(null, "eng", "old", "old",
            listOf(SubtitleRefreshCandidate(null, "eng", "new"))))
        assertEquals("old", selectRefreshedSubtitleUrl(null, "eng", "old", "old",
            listOf(SubtitleRefreshCandidate(null, "eng", "new"), SubtitleRefreshCandidate(null, "ENG", "old"))))
    }

    @Test fun duplicateIdNeedsExactUrl() {
        val candidates = listOf(SubtitleRefreshCandidate("a", "eng", "one"), SubtitleRefreshCandidate("a", "eng", "two"))
        assertNull(selectRefreshedSubtitleUrl("a", "eng", "old", "old", candidates))
        assertEquals("one", selectRefreshedSubtitleUrl("a", "eng", "one", "one", candidates))
    }

    @Test fun missingItemAndChangedLanguageFailClosed() {
        assertNull(selectRefreshedSubtitleUrl("a", "eng", "old", "old", emptyList()))
        assertNull(selectRefreshedSubtitleUrl("a", "eng", "old", "old",
            listOf(SubtitleRefreshCandidate("a", "kor", "old"))))
    }
}
