package xtdb.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class RowCounterTest {
    @Test
    fun testRowCounter() {
        val rc = RowCounter()

        assertEquals(0, rc.blockRowCount)

        rc.addRows(15)
        assertEquals(15, rc.blockRowCount)

        rc.addRows(15)
        assertEquals(30, rc.blockRowCount)
    }
}
