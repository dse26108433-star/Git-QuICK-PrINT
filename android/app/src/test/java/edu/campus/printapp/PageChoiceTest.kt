package edu.campus.printapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app, the website (index.html) and the server (PageRanges.java) must read
 * a page choice the same way, or the price on the phone would differ from the
 * price charged. These are the same cases the other two were checked with,
 * for a 1000-page PDF.
 */
class PageChoiceTest {

    private fun run(text: String): String = try {
        val r = PageChoice.parse(text, 1000)
        "OK ${r.count} [${r.spec.ifEmpty { "ALL" }}]"
    } catch (e: UserError) {
        "ERR " + e.message!!.replace('“', '"').replace('”', '"')
    }

    @Test
    fun sameAnswersAsWebsiteAndServer() {
        val cases = listOf(
            "102" to "OK 1 [102]",
            "333-390" to "OK 58 [333-390]",
            "1-5, 8, 12-15" to "OK 10 [1-5,8,12-15]",
            "333-" to "OK 668 [333-1000]",
            "390-333" to "OK 58 [333-390]",
            "8, 1-3, 2" to "OK 4 [1-3,8]",
            "1-1000" to "OK 1000 [ALL]",
            "5 - 10 ; 20" to "OK 7 [5-10,20]",
            "3–7" to "OK 5 [3-7]",
            "1200" to "ERR Page 1200 does not exist: this PDF has 1000 pages.",
            "0" to "ERR Pages start at 1.",
            "0-5" to "ERR Pages start at 1.",
            "abc" to "ERR \"abc\" is not a page number. Write pages like 5, 10-20 or 1-3, 8.",
            "5,,6" to "OK 2 [5-6]",
            "," to "ERR Write the pages you want, like 5 or 10-20.",
            "1-2-3" to "ERR \"1-2-3\" is not a page number. Write pages like 5, 10-20 or 1-3, 8.",
            "999999" to "ERR Page 999999 does not exist: this PDF has 1000 pages.",
            " 7 " to "OK 1 [7]",
            "10-" to "OK 991 [10-1000]",
            "1,2,3,4,5,6,7,8,9,10" to "OK 10 [1-10]"
        )
        for ((input, expected) in cases) {
            assertEquals("pages \"$input\"", expected, run(input))
        }
    }
}
