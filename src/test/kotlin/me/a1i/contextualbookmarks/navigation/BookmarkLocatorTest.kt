package me.a1i.contextualbookmarks.navigation

import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.LocationSignatures
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkLocatorTest {
    @Test
    fun `bounded line window preserves absolute document positions`() {
        val record = BookmarkRecord(
            line = 100,
            currentLineHash = LocationSignatures.hash("target"),
        )

        val result = BookmarkLocator().locate(
            record = record,
            lines = listOf("before", "target", "after"),
            firstLine = 100,
        )

        assertEquals(BookmarkLocationResult.Relocated(101), result)
    }

    private val locator = BookmarkLocator()

    @Test
    fun `valid live marker wins`() {
        assertEquals(BookmarkLocationResult.Live(2), locator.locate(record(0, listOf("a")), listOf("x", "y", "z"), 2))
    }

    @Test
    fun `exact signed line is retained`() {
        val lines = listOf("before", "target", "after")
        assertEquals(BookmarkLocationResult.Exact(1), locator.locate(record(1, lines), lines))
    }

    @Test
    fun `inserted lines relocate by signature`() {
        val original = listOf("before", "target", "after")
        val edited = listOf("new", "newer") + original
        assertEquals(BookmarkLocationResult.Relocated(3), locator.locate(record(1, original), edited))
    }

    @Test
    fun `neighbor signature breaks repeated-line tie`() {
        val original = listOf("unique-before", "target", "unique-after")
        val edited = listOf("target", "noise", "unique-before", "target", "unique-after", "target")
        assertEquals(BookmarkLocationResult.Relocated(3), locator.locate(record(1, original), edited))
    }

    @Test
    fun `ambiguous repeated signatures do not guess`() {
        val signature = LocationSignatures.fromLines(listOf("target"), 0)
        val record = BookmarkRecord(
            line = 9,
            currentLineHash = signature.currentLineHash,
        )
        assertEquals(BookmarkLocationResult.Ambiguous(listOf(0, 2)), locator.locate(record, listOf("target", "x", "target")))
    }

    @Test
    fun `empty deleted and far-away lines are missing`() {
        val original = listOf("target")
        assertEquals(BookmarkLocationResult.Missing, locator.locate(record(0, original), emptyList()))
        assertEquals(BookmarkLocationResult.Missing, locator.locate(record(0, original), listOf("deleted")))

        val far = MutableList(205) { "line-$it" }.apply { add("target") }
        assertEquals(BookmarkLocationResult.Missing, BookmarkLocator(200).locate(record(0, original), far))
    }

    @Test
    fun `unsigned out-of-range position is missing`() {
        assertEquals(BookmarkLocationResult.Missing, locator.locate(BookmarkRecord(line = 12), listOf("only")))
    }

    private fun record(line: Int, lines: List<String>): BookmarkRecord {
        val signature = LocationSignatures.fromLines(lines, line)
        return BookmarkRecord(
            line = line,
            currentLineHash = signature.currentLineHash,
            previousLineHash = signature.previousLineHash,
            nextLineHash = signature.nextLineHash,
        )
    }
}
