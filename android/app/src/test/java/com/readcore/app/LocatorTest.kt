package com.readcore.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatorTest {
    private val weights = listOf(10, 30, 60) // non-whitespace characters per paragraph

    @Test fun ignoresWhitespaceWhenWeighing() {
        assertEquals(Locator.weight("a b\n c"), Locator.weight("abc"))
        assertEquals(listOf("One.", "Two"), Locator.paragraphs("One.\n\n\n  Two  \n\n"))
    }

    @Test fun progressionAndLocateAreInverse() {
        assertEquals(0.0, Locator.progressionOf(weights, 0, 0.0), 1e-9)
        assertEquals(0.25, Locator.progressionOf(weights, 1, 0.5), 1e-9)
        val (index, within) = Locator.locate(weights, 0.25)
        assertEquals(1, index)
        assertEquals(0.5, within, 1e-9)
        for (p in listOf(0.0, 0.07, 0.4, 0.73, 0.999)) {
            val (i, w) = Locator.locate(weights, p)
            assertEquals(p, Locator.progressionOf(weights, i, w), 1e-9)
        }
        assertEquals(2 to 1.0, Locator.locate(weights, 1.0))
    }

    @Test fun skipsEmptyParagraphsAndHandlesEmptyChapters() {
        assertEquals(1 to 0.0, Locator.locate(listOf(0, 10), 0.0))
        assertEquals(0 to 0.0, Locator.locate(emptyList(), 0.5))
        assertEquals(0.0, Locator.progressionOf(emptyList(), 3, 0.5), 1e-9)
    }

    @Test fun percentIsWeightedByChapterLength() {
        // A short first chapter must not count as a third of the book.
        assertEquals(10.0, Locator.percent(listOf(10, 90), 1, 0.0), 1e-9)
        assertEquals(55.0, Locator.percent(listOf(10, 90), 1, 0.5), 1e-9)
        assertEquals(100.0, Locator.percent(listOf(10, 90), 1, 1.0), 1e-9)
    }

    @Test fun smallMovementsDoNotTriggerAJumpOffer() {
        assertFalse(Locator.differs(3, 0.50, 3, 0.51))
        assertTrue(Locator.differs(3, 0.50, 3, 0.60))
        assertTrue(Locator.differs(3, 0.50, 4, 0.50))
    }
}
