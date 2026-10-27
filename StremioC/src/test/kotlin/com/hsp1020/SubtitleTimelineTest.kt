package com.hsp1020

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SubtitleTimelineTest {
    @Test fun overlapGapsAndExactBoundaries() {
        val timeline = SubtitleTimeline(longArrayOf(100, 200, 200, 700), longArrayOf(500, 300, 600, 800))
        assertEquals(emptyList<Int>(), timeline.activeAt(99))
        assertEquals(listOf(0), timeline.activeAt(100))
        assertEquals(listOf(0, 1, 2), timeline.activeAt(200))
        assertEquals(listOf(0, 2), timeline.activeAt(300))
        assertEquals(listOf(2), timeline.activeAt(500))
        assertEquals(emptyList<Int>(), timeline.activeAt(600))
        assertEquals(700L, timeline.nextAfter(600))
        assertNull(timeline.nextAfter(800))
        assertNull(timeline.nextAfter(Long.MAX_VALUE))
    }

    @Test fun seekingInBothDirectionsMatchesAllIntervals() {
        val random = Random(42)
        val starts = LongArray(2000) { random.nextLong(0, 100000) }.sortedArray()
        val ends = LongArray(starts.size) { starts[it] + random.nextLong(1, 10000) }
        val timeline = SubtitleTimeline(starts, ends)
        repeat(1000) {
            val time = random.nextLong(-1000, 110000)
            assertEquals(starts.indices.filter { starts[it] <= time && time < ends[it] }, timeline.activeAt(time))
            assertEquals((starts.toList() + ends.toList()).filter { it > time }.minOrNull(), timeline.nextAfter(time))
        }
    }

    @Test fun emptyTimelineHasNoWork() {
        val timeline = SubtitleTimeline(longArrayOf(), longArrayOf())
        assertTrue(timeline.activeAt(0).isEmpty())
        assertNull(timeline.nextAfter(0))
    }
}
