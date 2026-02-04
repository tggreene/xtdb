package xtdb.bitemporal

import com.carrotsearch.hppc.LongArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.carrotsearch.hppc.LongArrayList as longs
import kotlin.Long.Companion.MAX_VALUE as MAX_LONG
import kotlin.Long.Companion.MIN_VALUE as MIN_LONG

internal class CeilingTest {
    private lateinit var ceiling: Ceiling

    @BeforeEach
    fun setUp() {
        ceiling = Ceiling()
    }

    @Test
    fun testReverseLinearSearch() {
        val list = longs.from(10, 8, 6, 4, 2)
        assertEquals(0, list.reverseLinearSearch(10))
        assertEquals(2, list.reverseLinearSearch(6))
        assertEquals(4, list.reverseLinearSearch(2))
        assertEquals(-2, list.reverseLinearSearch(9))
        assertEquals(-1, list.reverseLinearSearch(11))
        assertEquals(-5, list.reverseLinearSearch(3))
        assertEquals(-6, list.reverseLinearSearch(1))
    }

    @Test
    fun testBinarySearch() {
        val list = longs.from(10, 8, 6, 4, 2)
        assertEquals(0, list.binarySearch(10))
        assertEquals(2, list.binarySearch(6))
        assertEquals(4, list.binarySearch(2))
        assertEquals(-2, list.binarySearch(9))
        assertEquals(-1, list.binarySearch(11))
        assertEquals(-5, list.binarySearch(3))
        assertEquals(-6, list.binarySearch(1))
    }

    @Test
    fun testGetCeilingIndex() {
        val list = longs.from(10, 8, 6, 4, 2)
        // the second part shouldn't be used
        val ceiling = Ceiling(list, LongArrayList())
        assertEquals(0, ceiling.getCeilingIndex(1))
        assertEquals(0, ceiling.getCeilingIndex(2))
        assertEquals(4, ceiling.getCeilingIndex(10))
        assertEquals(4, ceiling.getCeilingIndex(11))
        assertEquals(1, ceiling.getCeilingIndex(5))
    }

    @Test
    fun testAppliesLogs() {
        assertEquals(longs.from(MAX_LONG, MIN_LONG), ceiling.validTimes)
        assertEquals(longs.from(MAX_LONG), ceiling.sysTimeCeilings)

        ceiling.applyLog(4, 4, MAX_LONG)
        assertEquals(longs.from(MAX_LONG, 4, MIN_LONG), ceiling.validTimes)
        assertEquals(longs.from(4, MAX_LONG), ceiling.sysTimeCeilings)

        // lower the whole ceiling
        ceiling.applyLog(3, 2, MAX_LONG)
        assertEquals(longs.from(MAX_LONG, 2, MIN_LONG), ceiling.validTimes)
        assertEquals(longs.from(3, MAX_LONG), ceiling.sysTimeCeilings)

        // lower part of the ceiling
        ceiling.applyLog(2, 1, 4)
        assertEquals(longs.from(MAX_LONG, 4, 1, MIN_LONG), ceiling.validTimes)
        assertEquals(longs.from(3, 2, MAX_LONG), ceiling.sysTimeCeilings)

        // replace a range exactly
        ceiling.applyLog(1, 1, 4)
        assertEquals(longs.from(MAX_LONG, 4, 1, MIN_LONG), ceiling.validTimes)
        assertEquals(longs.from(3, 1, MAX_LONG), ceiling.sysTimeCeilings)

        // replace the whole middle section
        ceiling.applyLog(0, 0, 6)
        assertEquals(longs.from(MAX_LONG, 6, 0, MIN_LONG), ceiling.validTimes)
        assertEquals(longs.from(3, 0, MAX_LONG), ceiling.sysTimeCeilings)
    }

    @Test
    fun `test replace within a range`() {
        ceiling.applyLog(4, 4, 6)
        assertEquals(longs.from(MAX_LONG, 6, 4, MIN_LONG), ceiling.validTimes)
        assertEquals(longs.from(MAX_LONG, 4, MAX_LONG), ceiling.sysTimeCeilings)
    }

    @Test
    fun testSplice() {
        // pure removal
        val a = longs.from(10, 8, 6, 4, 2)
        a.splice(1, 3)
        assertEquals(longs.from(10, 4, 2), a)

        // pure insertion
        val b = longs.from(10, 4, 2)
        b.splice(1, 1, 8, 6)
        assertEquals(longs.from(10, 8, 6, 4, 2), b)

        // replace same size
        val c = longs.from(10, 8, 6, 4, 2)
        c.splice(1, 3, 99, 88)
        assertEquals(longs.from(10, 99, 88, 4, 2), c)

        // replace with fewer (shrink)
        val d = longs.from(10, 8, 6, 4, 2)
        d.splice(1, 4, 99)
        assertEquals(longs.from(10, 99, 2), d)

        // replace with more (grow)
        val e = longs.from(10, 8, 6, 4, 2)
        e.splice(2, 3, 99, 88, 77)
        assertEquals(longs.from(10, 8, 99, 88, 77, 4, 2), e)

        // splice at start
        val f = longs.from(10, 8, 6)
        f.splice(0, 1, 99, 88)
        assertEquals(longs.from(99, 88, 8, 6), f)

        // splice at end
        val g = longs.from(10, 8, 6)
        g.splice(2, 3, 99, 88)
        assertEquals(longs.from(10, 8, 99, 88), g)
    }

    @Test
    fun `test applyLog all 5 branches individually`() {
        // Case 1: both endpoints exist (!insertedEnd && !insertedStart)
        val c1 = Ceiling()
        c1.applyLog(5, 2, 8)
        c1.applyLog(3, 2, 8) // both 2 and 8 already exist
        assertEquals(longs.from(MAX_LONG, 8, 2, MIN_LONG), c1.validTimes)
        assertEquals(longs.from(MAX_LONG, 3, MAX_LONG), c1.sysTimeCeilings)

        // Case 2: end exists, start needs inserting (!insertedEnd && insertedStart)
        val c2 = Ceiling()
        c2.applyLog(5, 2, 8)
        c2.applyLog(3, 4, 8) // 8 exists, 4 doesn't
        assertEquals(longs.from(MAX_LONG, 8, 4, 2, MIN_LONG), c2.validTimes)
        assertEquals(longs.from(MAX_LONG, 3, 5, MAX_LONG), c2.sysTimeCeilings)

        // Case 3: start exists, end needs inserting (insertedEnd && !insertedStart)
        val c3 = Ceiling()
        c3.applyLog(5, 2, 8)
        c3.applyLog(3, 2, 6) // 2 exists, 6 doesn't
        assertEquals(longs.from(MAX_LONG, 8, 6, 2, MIN_LONG), c3.validTimes)
        assertEquals(longs.from(MAX_LONG, 5, 3, MAX_LONG), c3.sysTimeCeilings)

        // Case 4: neither exists, same insertion point (end == start)
        val c4 = Ceiling()
        c4.applyLog(5, 3, 7) // 3 and 7 are both new, inserted at same point
        assertEquals(longs.from(MAX_LONG, 7, 3, MIN_LONG), c4.validTimes)
        assertEquals(longs.from(MAX_LONG, 5, MAX_LONG), c4.sysTimeCeilings)

        // Case 5: neither exists, different insertion points (end != start)
        val c5 = Ceiling()
        c5.applyLog(5, 2, 8)
        c5.applyLog(3, 4, 6) // 4 and 6 are both new, different positions
        assertEquals(longs.from(MAX_LONG, 8, 6, 4, 2, MIN_LONG), c5.validTimes)
        assertEquals(longs.from(MAX_LONG, 5, 3, 5, MAX_LONG), c5.sysTimeCeilings)
    }

    @Test
    fun `test applyLog many sequential events`() {
        // simulate a time-series workload: many events for one entity
        for (i in 0L until 100L) {
            val systemFrom = 200L - i // decreasing system time (reverse order)
            val validFrom = i * 10
            val validTo = (i + 1) * 10
            ceiling.applyLog(systemFrom, validFrom, validTo)
        }

        // verify structure is self-consistent: validTimes descending, correct element counts
        val vtCount = ceiling.validTimes.elementsCount
        val stCount = ceiling.sysTimeCeilings.elementsCount
        assertEquals(vtCount - 1, stCount)

        for (i in 0 until vtCount - 1) {
            assert(ceiling.validTimes[i] > ceiling.validTimes[i + 1]) {
                "validTimes should be strictly descending at index $i"
            }
        }
    }
}
