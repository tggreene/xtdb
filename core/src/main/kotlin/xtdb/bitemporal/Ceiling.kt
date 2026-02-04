package xtdb.bitemporal

import com.carrotsearch.hppc.LongArrayList

/**
 * searches a descending-sorted list for the last element greater than or equal to the needle
 *
 * @return the index of the element if found, otherwise `- (insertion point) - 1` of the element that would be inserted
 */
/*
 * we opt for a linear search here rather than binary because of the probability that the needle is close to the end -
 * we're scanning the events in reverse system-time order, so with vt≈tt our valid-from/valid-to are likely to be
 * older than any we've seen so far.
 */
internal fun LongArrayList.reverseLinearSearch(needle: Long): Int {
    var idx = elementsCount
    while (--idx >= 0) {
        val x = buffer[idx]
        when {
            x == needle -> return idx
            x > needle -> return -idx - 2
        }
    }

    return -1
}

internal fun LongArrayList.binarySearch(needle: Long): Int {
    var left = 0
    var right = elementsCount
    while (left < right) {
        val mid = (left + right) / 2
        val x = buffer[mid]
        when {
            x == needle -> return mid
            x > needle -> left = mid + 1
            else -> right = mid
        }
    }
    return -left - 1
}

/**
 * Replaces elements in [fromIndex, toIndex) with [values] using a single arraycopy for the tail shift.
 * This avoids the multiple array copies that separate insert + removeRange calls would cause.
 */
internal fun LongArrayList.splice(fromIndex: Int, toIndex: Int, vararg values: Long) {
    val removeCount = toIndex - fromIndex
    val insertCount = values.size
    val delta = insertCount - removeCount
    val newSize = elementsCount + delta

    if (delta > 0) ensureCapacity(newSize)

    val tailCount = elementsCount - toIndex
    if (tailCount > 0 && delta != 0) {
        System.arraycopy(buffer, toIndex, buffer, fromIndex + insertCount, tailCount)
    }

    for (i in values.indices) {
        buffer[fromIndex + i] = values[i]
    }

    elementsCount = newSize
}

data class Ceiling(val validTimes: LongArrayList, val sysTimeCeilings: LongArrayList) {
    constructor() : this(LongArrayList(), LongArrayList()) {
        reset()
    }

    private fun reverseIdx(idx: Int) = validTimes.elementsCount - 1 - idx

    fun getValidFrom(rangeIdx: Int) = validTimes[reverseIdx(rangeIdx)]

    fun getValidTo(rangeIdx: Int) = validTimes[reverseIdx(rangeIdx + 1)]

    fun getSystemTime(rangeIdx: Int) = sysTimeCeilings[reverseIdx(rangeIdx) - 1]

    /**
     * @return the index (in reverse order) such that `validTimes[reverseIdx(idx)] <= validTime < validTimes[reverseIdx(idx + 1)]`
     * or 0 if `validTime < validTimes[reverseIdx(0)]`
     * or `validTimes.elementsCount - 1` if `validTime >= validTimes[reverseIdx(validTimes.elementsCount - 1)]`
     */
    fun getCeilingIndex(validTime: Long): Int {
        var idx = validTimes.binarySearch(validTime)
        if (idx < 0) idx = -(idx + 1)
        if (idx < validTimes.elementsCount - 1 && validTime < validTimes[idx]) idx++
        if (idx == validTimes.elementsCount) idx--
        return reverseIdx(idx)
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun reset() {
        validTimes.clear()
        validTimes.add(Long.MAX_VALUE, Long.MIN_VALUE)

        sysTimeCeilings.clear()
        sysTimeCeilings.add(Long.MAX_VALUE)
    }

    fun applyLog(systemFrom: Long, validFrom: Long, validTo: Long) {
        if (validFrom >= validTo) return

        var end = validTimes.binarySearch(validTo)
        val insertedEnd = end < 0
        if (insertedEnd) end = -(end + 1)

        var start = validTimes.binarySearch(validFrom)
        val insertedStart = start < 0
        if (insertedStart) start = -(start + 1)

        when {
            !insertedEnd && !insertedStart -> {
                validTimes.splice(end + 1, start)
                sysTimeCeilings.splice(end, start, systemFrom)
            }

            !insertedEnd -> {
                validTimes.splice(end + 1, start, validFrom)
                sysTimeCeilings.splice(end, start - 1, systemFrom)
            }

            !insertedStart -> {
                validTimes.splice(end, start, validTo)
                sysTimeCeilings.splice(end, start, systemFrom)
            }

            end == start -> {
                // splitting an existing range - preserve the ceiling for the upper portion
                val preservedCeiling = sysTimeCeilings[end - 1]
                validTimes.splice(end, end, validTo, validFrom)
                sysTimeCeilings.splice(end, end, systemFrom, preservedCeiling)
            }

            else -> {
                validTimes.splice(end, start, validTo, validFrom)
                sysTimeCeilings.splice(end, start - 1, systemFrom)
            }
        }
    }

}
