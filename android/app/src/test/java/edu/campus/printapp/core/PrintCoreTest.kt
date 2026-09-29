package edu.campus.printapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app tidies and checks settings like the server's SettingsChecker, and
 * offers only what the printers can do: the same checks the website's rules
 * pass (spec/web-core.test.js), plus the page cases the older app was held to.
 */
class PrintCoreTest {

    private val printers = listOf(
        Printer(color = false, bw = true, features = Features(listOf("A4", "A3"), duplex = true, finishing = listOf("STAPLE_TOP_LEFT"))),
        Printer(color = true, bw = true, features = Features(listOf("A4", "PHOTO_4X6"), borderless = true, highQuality = true))
    )
    private val limits = Limits(50, 300)
    private fun pdf(pages: Int) = Facts("PDF", pages)

    @Test
    fun picturesLosePageListAndPagesPerSheet() {
        val r = normalize(PrintSettings(pages = "1-3", pagesPerSheet = 4, rotation = -90, scaling = "FILL"), Facts("JPEG", 1), limits, printers)
        assertNull(r.settings.pages)
        assertEquals(1, r.settings.pagesPerSheet)
        assertEquals(270, r.settings.rotation)
        assertEquals("FILL", r.settings.scaling)
        assertNull(r.error)
    }

    @Test
    fun severalPerSheetAreFittedAndOneCopyIsCollated() {
        val r = normalize(PrintSettings(pages = "8, 1-3, 2", pagesPerSheet = 2, scaling = "ACTUAL", collate = false), pdf(12), limits, printers)
        assertEquals("1-3,8", r.settings.pages)
        assertEquals("FIT", r.settings.scaling)
        assertTrue(r.settings.collate)
        assertEquals(2, r.plan.sides)
    }

    @Test
    fun pdfsAreNeverTurnedAndNeverFilled() {
        val r = normalize(PrintSettings(rotation = 90, center = false, scaling = "FILL"), pdf(3), limits, printers)
        assertEquals(0, r.settings.rotation)
        assertTrue(r.settings.center)
        assertEquals("FIT", r.settings.scaling)
    }

    @Test
    fun staplingOneSheetIsRefused() {
        val r = normalize(PrintSettings(pages = "2", staple = "TOP_LEFT"), pdf(3), limits, printers)
        assertTrue(r.error!!.startsWith("Stapling needs at least 2 sheets"))
    }

    @Test
    fun combinationsNoPrinterCanDoAreRefusedWithWordsToActOn() {
        val r = normalize(PrintSettings(color = true, duplex = "LONG_EDGE"), pdf(3), limits, printers)
        assertEquals("No single printer can do colour + two-sided together. Change one of these choices.", r.error)
        val legal = Facts("PDF", 1) { id -> if (id == "LEGAL") "Legal" else id }
        assertEquals("No printer takes Legal paper. Choose another paper size.",
            normalize(PrintSettings(paperSize = "LEGAL"), legal, limits, printers).error)
        assertEquals("No printer is taking orders right now. Please try again later.",
            normalize(PrintSettings(), pdf(1), limits, emptyList()).error)
    }

    @Test
    fun noPagesChosenAndTooManyPages() {
        val none = normalize(PrintSettings(pages = ""), pdf(5), limits, printers)
        assertEquals("Choose at least one page to print.", none.error)
        assertEquals(0, none.plan.printPages)
        val many = normalize(PrintSettings(), pdf(400), limits, printers)
        assertEquals("This file has 400 pages. Up to 300 pages can be printed from one document: choose the pages you need.", many.error)
        val chosen = normalize(PrintSettings(pages = "1-301"), pdf(400), limits, printers)
        assertTrue(chosen.error!!.startsWith("You chose 301 pages."))
        assertEquals("Page 9 does not exist: this PDF has 5 pages.", normalize(PrintSettings(pages = "9"), pdf(5), limits, printers).error)
    }

    @Test
    fun copiesOutsideTheLimitAreRefused() {
        assertEquals("Choose between 1 and 50 copies.", normalize(PrintSettings(copies = 51), pdf(1), limits, printers).error)
        assertEquals("Choose between 1 and 50 copies.", normalize(PrintSettings(copies = 0), pdf(1), limits, printers).error)
    }

    @Test
    fun optionsNobodyHasAreHiddenConflictsSayWhatTheyChange() {
        val s = PrintSettings(duplex = "LONG_EDGE")
        val colour = availability(Dim.COLOR, listOf(false, true), s, printers)
        assertTrue(colour[1].supported)
        assertFalse(colour[1].available)
        assertEquals(listOf("one-sided"), colour[1].fix!!.changes)
        assertEquals("ONE_SIDED", colour[1].fix!!.settings.duplex)
        val sizes = availability(Dim.PAPER_SIZE, listOf("A4", "A3", "LEGAL"), s, printers)
        assertFalse(sizes[2].supported)                      // nobody has Legal: not shown
        assertTrue(sizes[1].available)                       // two-sided A3 on the B/W printer
        assertEquals("→ one-sided", conflictWords(colour[1]))
    }

    @Test
    fun theValueChosenNowStaysVisibleWhenNoPrinterCanDoItAnyMore() {
        val s = PrintSettings(paperSize = "LEGAL")
        val c = choices(Dim.PAPER_SIZE, listOf("A4", "A3"), s, printers)
        assertEquals(listOf("A4", "A3", "LEGAL"), c.map { it.value })
        assertTrue(c[2].gone)
        assertEquals("not available now", conflictWords(c[2]))
        // a value nobody offers and nobody chose is not shown at all
        assertEquals(listOf("A4", "A3"), choices(Dim.PAPER_SIZE, listOf("A4", "A3", "LETTER"), PrintSettings(), printers).map { it.value })
    }

    @Test
    fun settingsThePrintersCannotDoAnyMoreGetTheSmallestFix() {
        val s = PrintSettings(color = true, duplex = "LONG_EDGE")
        val fix = quickFix(s, printers)!!
        assertEquals(listOf("one-sided"), fix.changes)
        assertTrue(fix.settings.color)                        // the colour stays as chosen
        assertEquals("ONE_SIDED", fix.settings.duplex)
        assertEquals("LONG_EDGE", s.duplex)                   // the original is not touched
        assertNull(quickFix(fix.settings, printers))
        assertNull(quickFix(s, emptyList()))                  // no printer at all: nothing to offer
    }

    @Test
    fun borderlessNeedsTheRightPrinterAndGivesUpForAMargin() {
        val s = PrintSettings(marginMm = 0, duplex = "LONG_EDGE")
        val fix = quickFix(s, printers)!!
        assertEquals(listOf("a margin"), fix.changes)
        assertEquals(DEFAULT_MARGIN, fix.settings.marginMm)
        val photo = PrintSettings(color = true, paperSize = "PHOTO_4X6", marginMm = 0)
        assertTrue(anyCanDo(printers, requirements(photo)))
    }

    @Test
    fun whatThePrintersOfferAndFinishingPositions() {
        val o = offered(printers)
        assertTrue(o.color && o.bw && o.duplex && o.borderless && o.highQuality)
        assertEquals(listOf("A4", "A3", "PHOTO_4X6"), o.paperSizes)
        assertEquals(listOf("TOP_LEFT"), finishingPositions(o.finishing, "STAPLE"))
        assertEquals(emptyList<String>(), finishingPositions(o.finishing, "PUNCH"))
    }

    @Test
    fun picturesKeepTheirProportionsAfterAnyTurn() {
        val a = pictureSize(ImageInfo(4000, 3000, 72.0, 6), 0)
        assertEquals(0.75, a.w / a.h, 1e-9)
        val b = pictureSize(ImageInfo(4000, 3000, 72.0, 6), 90)
        assertEquals(4.0 / 3, b.w / b.h, 1e-9)
        // fill on A4 inside 5 mm: covers the area, same factor both ways, clipped to the margins
        val o = layoutOptions(PrintSettings(scaling = "FILL"), Paper("A4", "A4", 210.0, 297.0), true)
        val sheet = layoutSheet(0, 1, { b }, o)
        val cell = sheet.cells[0]
        assertEquals(b.w / b.h, cell.w / cell.h, 1e-9)
        assertTrue(sheet.clip != null)
    }

    @Test
    fun theOlderAppsPageCasesStillRead() {
        fun run(t: String) = try {
            val r = parsePages(t, 1000)
            "OK ${r.count} [${r.spec ?: "ALL"}]"
        } catch (e: UserError) {
            "ERR " + e.message!!.replace('“', '"').replace('”', '"')
        }
        val cases = listOf(
            "102" to "OK 1 [102]", "333-390" to "OK 58 [333-390]", "1-5, 8, 12-15" to "OK 10 [1-5,8,12-15]",
            "333-" to "OK 668 [333-1000]", "390-333" to "OK 58 [333-390]", "8, 1-3, 2" to "OK 4 [1-3,8]",
            "1-1000" to "OK 1000 [ALL]", "5 - 10 ; 20" to "OK 7 [5-10,20]", "3–7" to "OK 5 [3-7]",
            "1200" to "ERR Page 1200 does not exist: this PDF has 1000 pages.", "0" to "ERR Pages start at 1.",
            "0-5" to "ERR Pages start at 1.", "abc" to "ERR \"abc\" is not a page number. Write pages like 5, 10-20 or 1-3, 8.",
            "5,,6" to "OK 2 [5-6]", "," to "ERR Write the pages you want, like 5 or 10-20.",
            "1-2-3" to "ERR \"1-2-3\" is not a page number. Write pages like 5, 10-20 or 1-3, 8.",
            "999999" to "ERR Page 999999 does not exist: this PDF has 1000 pages.", " 7 " to "OK 1 [7]",
            "10-" to "OK 991 [10-1000]", "1,2,3,4,5,6,7,8,9,10" to "OK 10 [1-10]"
        )
        for ((input, want) in cases) assertEquals("pages \"$input\"", want, run(input))
    }

    @Test
    fun pagesFromThePicturesAndWords() {
        assertEquals("", specFromPages(emptySet(), 5))
        assertNull(specFromPages(setOf(1, 2, 3, 4, 5), 5))
        assertEquals("1-3,5", specFromPages(listOf(5, 1, 2, 3, 3), 6))
        assertEquals(emptySet<Int>(), pagesFromSpec("", 5))
        assertEquals("3, 7, 10–12", displaySpec("3,7,10-12"))
        assertEquals("₹2", rupees(200))
        assertEquals("₹12.50", rupees(1250))
        assertEquals("1 sheet", plural(1, "sheet", "sheets"))
        assertEquals("4×6", shortPaper("PHOTO_4X6"))
        assertEquals("215.9 × 355.6 mm", dims(Paper("LEGAL", "Legal", 215.9, 355.6)))
        assertEquals("1.5 MB", fileSize(1572864))
    }
}
