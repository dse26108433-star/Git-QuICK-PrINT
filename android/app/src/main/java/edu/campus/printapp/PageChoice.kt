package edu.campus.printapp

/**
 * Which pages of a PDF to print, written the way every print dialog accepts it:
 *   102      333-390      1-5, 8, 12-15      333-   (to the end)
 *
 * Same rules and messages as the website and the server (PageRanges.java);
 * the server checks again, and its answer is the one that counts. Pages print
 * in order, each once.
 */
object PageChoice {

    /** spec is what the server gets ("" = every page); count is how many pages print. */
    data class Result(val ranges: List<IntRange>, val count: Int, val spec: String)

    private val PART = Regex("""(\d{1,6})(?:\s*-\s*(\d{1,6})?)?""")

    /** @throws UserError with a message the student can act on */
    fun parse(text: String, total: Int): Result {
        val t = text.trim().replace('–', '-').replace('—', '-').replace(';', ',')
        if (t.length > 200) throw UserError("The page list is too long. Use ranges like 10-50.")
        val parts = mutableListOf<IntArray>()
        for (raw in t.split(',')) {
            val p = raw.trim()
            if (p.isEmpty()) continue
            val m = PART.matchEntire(p)
                ?: throw UserError("“$p” is not a page number. Write pages like 5, 10-20 or 1-3, 8.")
            var from = m.groupValues[1].toInt()
            var to = if (!p.contains('-')) from else (m.groups[2]?.value?.toInt() ?: total)
            if (to < from) { val x = from; from = to; to = x }
            if (from < 1) throw UserError("Pages start at 1.")
            if (to > total) {
                throw UserError("Page $to does not exist: this PDF has $total " + (if (total == 1) "page." else "pages."))
            }
            parts += intArrayOf(from, to)
        }
        if (parts.isEmpty()) throw UserError("Write the pages you want, like 5 or 10-20.")
        parts.sortBy { it[0] }
        val merged = mutableListOf<IntArray>()
        for (r in parts) {
            val last = merged.lastOrNull()
            if (last != null && r[0] <= last[1] + 1) last[1] = maxOf(last[1], r[1]) else merged += r.copyOf()
        }
        val count = merged.sumOf { it[1] - it[0] + 1 }
        val spec = merged.joinToString(",") { if (it[0] == it[1]) "${it[0]}" else "${it[0]}-${it[1]}" }
        return Result(merged.map { it[0]..it[1] }, count, if (count == total) "" else spec)
    }
}
