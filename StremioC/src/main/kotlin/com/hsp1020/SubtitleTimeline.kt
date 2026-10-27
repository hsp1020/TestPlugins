package com.hsp1020

/** Immutable index built off the UI thread. Intervals are start-inclusive, end-exclusive. */
internal class SubtitleTimeline(private val starts: LongArray, private val ends: LongArray) {
    val boundaries = (starts.asSequence() + ends.asSequence()).distinct().sorted().toList().toLongArray()
    private val prefixEnds = LongArray(ends.size)

    init {
        require(starts.size == ends.size)
        var end = Long.MIN_VALUE
        for (index in starts.indices) {
            require(ends[index] > starts[index])
            require(index == 0 || starts[index - 1] <= starts[index])
            end = maxOf(end, ends[index])
            prefixEnds[index] = end
        }
    }

    fun activeAt(time: Long): List<Int> {
        var low = 0
        var high = prefixEnds.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (prefixEnds[middle] <= time) low = middle + 1 else high = middle
        }
        val active = ArrayList<Int>(2)
        for (index in low until starts.size) {
            if (starts[index] > time) break
            if (time < ends[index]) active.add(index)
        }
        return active
    }

    fun nextAfter(time: Long): Long? {
        var low = 0
        var high = boundaries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (boundaries[middle] <= time) low = middle + 1 else high = middle
        }
        return boundaries.getOrNull(low)
    }
}
