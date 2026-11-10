package dev.local.catleaf.nativeapp.ui.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderPagingTest {
    @Test fun advancesOneViewportWithOverlapAndClampsAtBothEnds() {
        assertEquals(840, readerPageTarget(0, 900, 60, 2000, 1))
        assertEquals(2000, readerPageTarget(1900, 900, 60, 2000, 1))
        assertEquals(0, readerPageTarget(100, 900, 60, 2000, -1))
        assertEquals(0, readerPageTarget(0, 900, 60, 0, 1))
        assertEquals(0, readerPageTarget(0, 0, 60, 2000, 1))
        assertEquals(1600, readerPageTarget(1200, 400, 1000, 2000, 1))
    }

    @Test fun onlyOuterZonesTurnPages() {
        assertEquals(-1, readerPageDirection(10f, 100f))
        assertEquals(0, readerPageDirection(50f, 100f))
        assertEquals(1, readerPageDirection(90f, 100f))
        assertEquals(0, readerPageDirection(10f, 0f))
        assertEquals(0, readerPageDirection(-1f, 100f))
    }
}
