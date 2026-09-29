package edu.campus.printapp.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Campus Print - the printing rules, the app's copy.
 *
 * The same rules as the website (web/js/print-core.js), line for line, and
 * each has a twin that decides what really prints:
 *
 *   parsePages     backend PageRanges.java          spec/cases/page-ranges.json
 *   layoutSheet    agent Layout.java                spec/cases/layout.json
 *   plan, price    backend PrintPlan/Pricing.java   spec/cases/pricing.json
 *   canDo          backend PrinterRules.java, db printer_can_do()  spec/cases/printer-rules.json
 *   normalize      backend SettingsChecker.java
 *
 * The unit tests run the shared cases against this file too. The app uses
 * these rules for the choices on screen, the live price and the preview; the
 * server checks everything again when the student reviews the order, and the
 * app then shows (and keeps) the server's answer.
 */

/** A problem the student can fix; the message is shown as it is. */
class UserError(message: String) : Exception(message)

const val MM = 72.0 / 25.4
const val GAP = 3 * MM
const val DEFAULT_MARGIN = 5
val PAGES_PER_SHEET = listOf(1, 2, 4, 6, 9, 16)
val FINISHING_GROUPS = listOf("STAPLE", "PUNCH", "BIND")
private val GRIDS = mapOf(
    2 to listOf(1 to 2, 2 to 1), 4 to listOf(2 to 2), 6 to listOf(2 to 3, 3 to 2),
    9 to listOf(3 to 3), 16 to listOf(4 to 4)
)

/** How one document is printed. Same fields and meaning as the server's PrintSettings. */
@Serializable
data class PrintSettings(
    val copies: Int = 1,
    val color: Boolean = false,
    val pages: String? = null,          // PDFs: "3,7,10-12"; null = every page; "" = none chosen yet (never sent)
    val duplex: String = "ONE_SIDED",   // ONE_SIDED, LONG_EDGE, SHORT_EDGE
    val paperSize: String = "A4",
    val orientation: String = "AUTO",   // AUTO, PORTRAIT, LANDSCAPE
    val scaling: String = "FIT",        // FIT, ACTUAL, CUSTOM, FILL (pictures)
    val scalePercent: Int = 100,
    val pagesPerSheet: Int = 1,
    val marginMm: Int = DEFAULT_MARGIN, // 0 = borderless
    val rotation: Int = 0,              // pictures: 0, 90, 180, 270
    val center: Boolean = true,
    val collate: Boolean = true,
    val staple: String? = null,
    val punch: String? = null,
    val bind: String? = null,
    val mediaType: String? = null,
    val quality: String = "STANDARD"
) {
    val twoSided: Boolean get() = duplex != "ONE_SIDED"
}

/** What the server measured in a picture (or the app, until the server has answered). */
@Serializable
data class ImageInfo(val widthPx: Int, val heightPx: Int, val dpi: Double = 96.0, val exifOrientation: Int = 1)

@Serializable
data class PricingRules(
    val paperSizePercent: Map<String, Int> = emptyMap(),
    val mediaTypePercent: Map<String, Int> = emptyMap(),
    val finishingPaise: Map<String, Int> = emptyMap()
)

// ------------------------------------------------------------------ pages

data class PageSelection(val ranges: List<IntRange>, val count: Int, val spec: String?)

private val PART = Regex("""^(\d{1,6})(?:\s*-\s*(\d{1,6})?)?$""")

/**
 * Which pages print: "102", "333-390", "1-5, 8", "333-" (to the end). Pages
 * print in order, each once. spec is the tidy form ("1-3,8") or null for
 * every page.
 * @throws UserError with words for the student
 */
fun parsePages(text: String?, total: Int): PageSelection {
    val t = (text ?: "").trim().replace('–', '-').replace('—', '-').replace(';', ',')
    if (t.isEmpty()) return PageSelection(listOf(1..total), total, null)
    if (t.length > 200) throw UserError("The page list is too long. Use ranges like 10-50.")
    val parts = mutableListOf<IntArray>()
    for (raw in t.split(',')) {
        val p = raw.trim()
        if (p.isEmpty()) continue
        val m = PART.matchEntire(p) ?: throw UserError("“$p” is not a page number. Write pages like 5, 10-20 or 1-3, 8.")
        var from = m.groupValues[1].toInt()
        var to = if (!p.contains('-')) from else (m.groups[2]?.value?.toInt() ?: total)
        if (to < from) { val x = from; from = to; to = x }
        if (from < 1) throw UserError("Pages start at 1.")
        if (to > total) throw UserError("Page $to does not exist: this PDF has $total " + (if (total == 1) "page." else "pages."))
        parts += intArrayOf(from, to)
    }
    if (parts.isEmpty()) throw UserError("Write the pages you want, like 5 or 10-20.")
    parts.sortBy { it[0] }
    val merged = mutableListOf<IntArray>()
    for (r in parts) {
        val last = merged.lastOrNull()
        if (last != null && r[0] <= last[1] + 1) last[1] = max(last[1], r[1]) else merged += r.copyOf()
    }
    val count = merged.sumOf { it[1] - it[0] + 1 }
    val spec = merged.joinToString(",") { if (it[0] == it[1]) "${it[0]}" else "${it[0]}-${it[1]}" }
    return PageSelection(merged.map { it[0]..it[1] }, count, if (count == total) null else spec)
}

/** Page numbers (1-based) -> tidy spec; null when it is every page, "" when none. */
fun specFromPages(pages: Collection<Int>, total: Int): String? {
    val list = pages.filter { it in 1..total }.distinct().sorted()
    if (list.isEmpty()) return ""
    if (list.size == total) return null
    val out = mutableListOf<String>()
    var start = list[0]
    var prev = list[0]
    for (i in 1..list.size) {
        val n = list.getOrNull(i)
        if (n != null && n == prev + 1) { prev = n; continue }
        out += if (start == prev) "$start" else "$start-$prev"
        if (n != null) { start = n; prev = n }
    }
    return out.joinToString(",")
}

/** Spec (null = all, "" = none chosen yet) -> the page numbers. */
fun pagesFromSpec(spec: String?, total: Int): Set<Int> {
    if (spec == "") return emptySet()
    val out = LinkedHashSet<Int>()
    parsePages(spec ?: "", total).ranges.forEach { r -> out.addAll(r) }
    return out
}

/** "1-3,8" -> "1–3, 8" for people. */
fun displaySpec(spec: String?): String = spec?.replace(",", ", ")?.replace("-", "–") ?: ""

// ------------------------------------------------------------------ layout

/** Sizes in points (1/72 inch), y up from the bottom of the sheet, as the Xerox PC lays out. */
data class Size(val w: Double, val h: Double)
data class Box(val x: Double, val y: Double, val w: Double, val h: Double)
data class Cell(val page: Int, val x: Double, val y: Double, val w: Double, val h: Double)
data class Sheet(val w: Double, val h: Double, val cells: List<Cell>, val clip: Box?)

data class LayoutOptions(
    val paperW: Double, val paperH: Double, val orientation: String, val marginPt: Double,
    val scaling: String, val scalePercent: Int, val pagesPerSheet: Int, val center: Boolean
)

/** Every sheet at once (tests); the app draws one sheet at a time with layoutSheet. */
fun layout(pages: List<Size>, o: LayoutOptions): List<Sheet> {
    if (pages.isEmpty()) return emptyList()
    val n = max(1, o.pagesPerSheet)
    val count = if (n == 1) pages.size else ceil(pages.size / n.toDouble()).toInt()
    return (0 until count).map { k -> layoutSheet(k, pages.size, { pages[it] }, o) }
}

/** One sheet (k from 0), asking sizeOf(i) only for the pages it needs. Same numbers as layout(). */
fun layoutSheet(k: Int, pageCount: Int, sizeOf: (Int) -> Size, o: LayoutOptions): Sheet {
    val shortSide = min(o.paperW, o.paperH)
    val longSide = max(o.paperW, o.paperH)
    val m = o.marginPt
    val n = max(1, o.pagesPerSheet)
    if (n == 1) {
        val p = sizeOf(k)
        val landscape = o.orientation == "LANDSCAPE" || (o.orientation == "AUTO" && p.w > p.h)
        val sw = if (landscape) longSide else shortSide
        val sh = if (landscape) shortSide else longSide
        val aw = sw - 2 * m
        val ah = sh - 2 * m
        val s = when (o.scaling) {
            "FILL" -> max(aw / p.w, ah / p.h)
            "ACTUAL" -> 1.0
            "CUSTOM" -> o.scalePercent / 100.0
            else -> min(aw / p.w, ah / p.h)
        }
        val w = p.w * s
        val h = p.h * s
        val x = if (o.center) (sw - w) / 2 else m
        val y = if (o.center) (sh - h) / 2 else sh - m - h
        return Sheet(sw, sh, listOf(Cell(k, x, y, w, h)), if (o.scaling == "FILL") Box(m, m, aw, ah) else null)
    }
    val grids = GRIDS[n] ?: throw IllegalArgumentException("Pages per sheet must be 1, 2, 4, 6, 9 or 16")
    val first = sizeOf(0)
    val orientations = if (o.orientation == "AUTO") listOf(false, true) else listOf(o.orientation == "LANDSCAPE")
    var best = -1.0
    var bigW = 0.0
    var bigH = 0.0
    var cw = 0.0
    var ch = 0.0
    var cols = 1
    for (land in orientations) {
        val ww = if (land) longSide else shortSide
        val hh = if (land) shortSide else longSide
        for ((gc, gr) in grids) {
            val cellW = (ww - 2 * m - (gc - 1) * GAP) / gc
            val cellH = (hh - 2 * m - (gr - 1) * GAP) / gr
            val sc = min(cellW / first.w, cellH / first.h)
            if (sc > best + 1e-9) { best = sc; bigW = ww; bigH = hh; cw = cellW; ch = cellH; cols = gc }
        }
    }
    val cells = mutableListOf<Cell>()
    var j = 0
    while (j < n && k * n + j < pageCount) {
        val q = sizeOf(k * n + j)
        val col = j % cols
        val row = j / cols
        val cellX = m + col * (cw + GAP)
        val cellY = bigH - m - row * (ch + GAP) - ch
        val sc2 = min(cw / q.w, ch / q.h)
        val w2 = q.w * sc2
        val h2 = q.h * sc2
        cells += Cell(k * n + j, cellX + (cw - w2) / 2, cellY + (ch - h2) / 2, w2, h2)
        j++
    }
    return Sheet(bigW, bigH, cells, null)
}

@Serializable
data class Paper(val id: String, val label: String, val widthMm: Double, val heightMm: Double)

fun layoutOptions(s: PrintSettings, paper: Paper, isPicture: Boolean) = LayoutOptions(
    paperW = paper.widthMm * MM, paperH = paper.heightMm * MM, orientation = s.orientation,
    marginPt = s.marginMm * MM, scaling = s.scaling, scalePercent = s.scalePercent,
    pagesPerSheet = if (isPicture) 1 else s.pagesPerSheet, center = if (isPicture) s.center else true
)

/** A picture's size on paper at 100 % (points), as it looks: after the camera's EXIF turn and the student's turn. */
fun pictureSize(image: ImageInfo, rotation: Int): Size {
    val exifQuarter = image.exifOrientation in 5..8
    val userQuarter = (rotation / 90) % 2 != 0
    val swap = exifQuarter != userQuarter
    val dpi = if (image.dpi > 0) image.dpi else 96.0
    val w = (if (swap) image.heightPx else image.widthPx) / dpi * 72
    val h = (if (swap) image.widthPx else image.heightPx) / dpi * 72
    return Size(w, h)
}

// ------------------------------------------------------------------ paper and price

data class Plan(val printPages: Int, val sides: Int, val sheets: Int)

fun plan(printPages: Int, s: PrintSettings): Plan {
    val perSheet = max(1, s.pagesPerSheet)
    val sides = ceil(printPages / perSheet.toDouble()).toInt()
    val sheets = if (s.twoSided) ceil(sides / 2.0).toInt() else sides
    return Plan(printPages, sides, sheets)
}

data class Price(val perSide: Int, val finishing: Int, val amount: Long)

/** Price of one document in paise; whole numbers, exactly as the server (Pricing.java). */
fun price(s: PrintSettings, pl: Plan, priceBwPaise: Int, priceColorPaise: Int, rules: PricingRules?): Price {
    val r = rules ?: PricingRules()
    val base = (if (s.color) priceColorPaise else priceBwPaise).toLong()
    val sizePct = (r.paperSizePercent[s.paperSize] ?: 100).toLong()
    val mediaPct = (if (s.mediaType == null) 100 else r.mediaTypePercent[s.mediaType] ?: 100).toLong()
    val perSide = floor((base * sizePct * mediaPct + 5000) / 10000.0).toInt()
    var finishing = 0
    if (s.staple != null) finishing += r.finishingPaise["STAPLE"] ?: 0
    if (s.punch != null) finishing += r.finishingPaise["PUNCH"] ?: 0
    if (s.bind != null) finishing += r.finishingPaise["BIND"] ?: 0
    return Price(perSide, finishing, s.copies.toLong() * (pl.sides.toLong() * perSide + finishing))
}

// ------------------------------------------------------------------ printers

/** What a document needs from a printer. color null = either will do (only when asking "does anyone offer this?"). */
data class Requirements(
    val color: Boolean?,
    val paperSize: String?,
    val duplex: String?,
    val finishing: List<String> = emptyList(),
    val mediaType: String? = null,
    val borderless: Boolean = false,
    val quality: String? = null
)

fun requirements(s: PrintSettings): Requirements {
    val finishing = mutableListOf<String>()
    s.staple?.let { finishing += "STAPLE_$it" }
    s.punch?.let { finishing += "PUNCH_$it" }
    s.bind?.let { finishing += "BIND_$it" }
    return Requirements(s.color, s.paperSize, s.duplex, finishing, s.mediaType, s.marginMm == 0, s.quality)
}

data class Features(
    val paperSizes: List<String> = listOf("A4"),
    val duplex: Boolean = false,
    val finishing: List<String> = emptyList(),
    val mediaTypes: List<String> = emptyList(),
    val borderless: Boolean = false,
    val highQuality: Boolean = false
)

/** features null = never looked at: A4, one-sided, nothing else. */
data class Printer(
    val id: String = "", val name: String = "", val online: Boolean = true,
    val color: Boolean, val bw: Boolean, val features: Features?
)

private val PLAIN = Features()

/** Same rule as the print queue: can this printer print a document that needs req? */
fun canDo(p: Printer, req: Requirements): Boolean {
    val f = p.features ?: PLAIN
    if (!(if (req.color == true) p.color else p.bw)) return false
    if (req.paperSize != null && req.paperSize !in f.paperSizes) return false
    if (req.duplex != null && req.duplex != "ONE_SIDED" && !f.duplex) return false
    if (req.finishing.any { it !in f.finishing }) return false
    if (req.mediaType != null && req.mediaType !in f.mediaTypes) return false
    if (req.borderless && !f.borderless) return false
    return req.quality == null || req.quality == "STANDARD" || f.highQuality
}

fun anyCanDo(printers: List<Printer>, req: Requirements) = printers.any { canDo(it, req) }

// ------------------------------------------------------------------ settings

fun defaults(): PrintSettings = PrintSettings()

data class Facts(val type: String, val pageCount: Int, val paperLabel: (String) -> String = { it })
data class Limits(val maxCopies: Int, val maxPages: Int)
data class Normalized(val settings: PrintSettings, val plan: Plan, val error: String?)

/**
 * Tidies a document's settings the way the server does (SettingsChecker):
 * pictures have no page list or pages per sheet, PDFs are always centred and
 * never turned, several per sheet are always fitted, one copy is collated.
 * error: words for the student, or null.
 */
fun normalize(raw: PrintSettings, facts: Facts, limits: Limits, printers: List<Printer>?): Normalized {
    val pdf = facts.type == "PDF"
    var s = raw
    var error: String? = null
    var printPages = 1
    if (pdf && s.pages == "") {
        error = "Choose at least one page to print."
        printPages = 0
    } else if (pdf) {
        try {
            val sel = parsePages(s.pages ?: "", facts.pageCount)
            s = s.copy(pages = sel.spec)
            printPages = sel.count
            if (printPages > limits.maxPages) {
                error = (if (sel.spec == null) "This file has " else "You chose ") + printPages + " pages. Up to " +
                    limits.maxPages + " pages can be printed from one document: choose the pages you need."
            }
        } catch (e: UserError) {
            error = e.message
            printPages = facts.pageCount
        }
    } else {
        s = s.copy(pages = null, pagesPerSheet = 1, rotation = ((s.rotation % 360) + 360) % 360)
    }
    if (pdf && s.scaling == "FILL") s = s.copy(scaling = "FIT")
    if (pdf) s = s.copy(rotation = 0, center = true)
    if (s.pagesPerSheet > 1) s = s.copy(scaling = "FIT", scalePercent = 100)
    if (s.scaling != "CUSTOM") s = s.copy(scalePercent = 100)
    if (error == null && (s.copies < 1 || s.copies > limits.maxCopies)) error = "Choose between 1 and ${limits.maxCopies} copies."
    if (s.copies == 1 || s.staple != null || s.bind != null) s = s.copy(collate = true)
    val pl = plan(printPages, s)
    if (error == null && (s.staple != null || s.bind != null) && pl.sheets < 2) {
        error = (if (s.staple != null) "Stapling" else "Binding") + " needs at least 2 sheets of paper; this document prints on 1 sheet."
    }
    if (error == null && printers != null && !anyCanDo(printers, requirements(s))) {
        error = whyNot(printers, requirements(s), facts.paperLabel)
    }
    return Normalized(s, pl, error)
}

/**
 * Why no printer can do it, in words a student can act on: a single choice
 * nobody can do first, else the combination. Same words as the server
 * (PrinterRules.whyNot).
 */
fun whyNot(printers: List<Printer>, req: Requirements, paperLabel: (String) -> String = { it }): String {
    if (printers.isEmpty()) return "No printer is taking orders right now. Please try again later."
    val plain = Requirements(color = false, paperSize = null, duplex = null)
    val choices = mutableListOf<String>()
    if (req.color == true) {
        if (!anyCanDo(printers, plain.copy(color = true))) return "Colour printing is not available. Choose black & white."
        choices += "colour"
    } else if (!anyCanDo(printers, plain)) {
        return "Black & white printing is not available. Choose colour."
    }
    if (req.paperSize != null) {
        if (!anyCanDo(printers, plain.copy(color = req.color, paperSize = req.paperSize)) &&
            !anyCanDo(printers, plain.copy(color = req.color != true, paperSize = req.paperSize))) {
            return "No printer takes ${paperLabel(req.paperSize)} paper. Choose another paper size."
        }
        if (req.paperSize != "A4") choices += paperLabel(req.paperSize)
    }
    fun has(fn: (Features) -> Boolean) = printers.any { fn(it.features ?: PLAIN) }
    if (req.duplex != null && req.duplex != "ONE_SIDED") {
        if (!has { it.duplex }) return "Two-sided printing is not available. Choose one-sided."
        choices += "two-sided"
    }
    for (fin in req.finishing) {
        if (!has { fin in it.finishing }) return "That finishing is not available."
        choices += fin.lowercase().replace('_', ' ')
    }
    if (req.mediaType != null) {
        if (!has { req.mediaType in it.mediaTypes }) return "That paper type is not available. Choose the usual paper."
        choices += "that paper type"
    }
    if (req.borderless) {
        if (!has { it.borderless }) return "Borderless printing is not available. Choose a margin."
        choices += "borderless"
    }
    if (req.quality == "HIGH") {
        if (!has { it.highQuality }) return "High quality is not available. Choose standard quality."
        choices += "high quality"
    }
    return "No single printer can do " + choices.joinToString(" + ") + " together. Change one of these choices."
}

// ------------------------------------------------------------------ what the student may choose

/** One setting the student can change, read and written the same way everywhere. */
enum class Dim(val read: (PrintSettings) -> Any?, val write: (PrintSettings, Any?) -> PrintSettings) {
    COLOR({ it.color }, { s, v -> s.copy(color = v as Boolean) }),
    PAPER_SIZE({ it.paperSize }, { s, v -> s.copy(paperSize = v as String) }),
    DUPLEX({ it.duplex }, { s, v -> s.copy(duplex = v as String) }),
    STAPLE({ it.staple }, { s, v -> s.copy(staple = v as String?) }),
    PUNCH({ it.punch }, { s, v -> s.copy(punch = v as String?) }),
    BIND({ it.bind }, { s, v -> s.copy(bind = v as String?) }),
    MEDIA_TYPE({ it.mediaType }, { s, v -> s.copy(mediaType = v as String?) }),
    QUALITY({ it.quality }, { s, v -> s.copy(quality = v as String) }),
    BORDERLESS({ it.marginMm == 0 }, { s, v ->
        s.copy(marginMm = if (v as Boolean) 0 else if (s.marginMm == 0) DEFAULT_MARGIN else s.marginMm)
    })
}

/** When a choice cannot be printed with the others: the settings after the change, and what changed (words). */
data class Fix(val settings: PrintSettings, val changes: List<String>)

/**
 * One value of a setting: can any printer do it at all (else it is not shown),
 * can it be done with the other current choices (else it is shown with the fix).
 * gone: the value chosen now, although no printer can do it any more.
 */
data class Choice(val value: Any?, val supported: Boolean, val available: Boolean, val fix: Fix?, val gone: Boolean = false)

private fun onlyThis(dim: Dim, v: Any?): Requirements {
    val r = requirements(dim.write(defaults(), v))
    return if (dim != Dim.COLOR) r.copy(color = null) else r
}

private fun canDoLoose(p: Printer, req: Requirements): Boolean =
    if (req.color == null) canDo(p.copy(color = true, bw = true), req) else canDo(p, req)

fun availability(dim: Dim, values: List<Any?>, s: PrintSettings, printers: List<Printer>): List<Choice> =
    values.map { v ->
        val supported = printers.any { canDoLoose(it, onlyThis(dim, v)) }
        val t = dim.write(s, v)
        val available = anyCanDo(printers, requirements(t))
        Choice(v, supported, available, if (supported && !available) fixFor(t, dim, printers) else null)
    }

/**
 * The values of one setting the student can see: those a printer supports
 * (with the fix when they conflict), plus the one chosen now even when no
 * printer can do it any more (marked gone), so it can be changed.
 */
fun choices(dim: Dim, values: List<Any?>, s: PrintSettings, printers: List<Printer>): List<Choice> {
    val cur = dim.read(s)
    val list = if (dim == Dim.BORDERLESS || cur in values) values else values + cur
    return availability(dim, list, s, printers)
        .filter { it.supported || it.value == cur }
        .map { if (it.supported) it else it.copy(gone = true) }
}

/** "→ black & white" under a choice that needs another change; "not available now" for a gone one. */
fun conflictWords(c: Choice?): String? = when {
    c == null -> null
    c.gone -> "not available now"
    !c.available && c.fix != null -> "→ " + c.fix.changes.joinToString(", ")
    else -> null
}

/** What to give up (least important first) so settings can be printed. */
private data class GiveUp(val dim: Dim, val value: Any?, val words: String)

private val GIVE_UP = listOf(
    GiveUp(Dim.QUALITY, "STANDARD", "standard quality"), GiveUp(Dim.MEDIA_TYPE, null, "the usual paper"),
    GiveUp(Dim.BORDERLESS, false, "a margin"), GiveUp(Dim.BIND, null, "no binding"), GiveUp(Dim.PUNCH, null, "no hole punch"),
    GiveUp(Dim.STAPLE, null, "no staples"), GiveUp(Dim.DUPLEX, "ONE_SIDED", "one-sided"), GiveUp(Dim.PAPER_SIZE, "A4", "A4 paper"),
    GiveUp(Dim.COLOR, false, "black & white"), GiveUp(Dim.COLOR, true, "colour")
)

/** t cannot be printed as it is: find what to give up so it can, keeping dim as chosen (dim null: anything may change). */
fun fixFor(t: PrintSettings, dim: Dim?, printers: List<Printer>): Fix? {
    val tries = GIVE_UP.filter { it.dim != dim }
    for (g in tries) {                                  // one change
        if (g.dim.read(t) == g.value) continue
        val u = g.dim.write(t, g.value)
        if (anyCanDo(printers, requirements(u))) return Fix(u, listOf(g.words))
    }
    var w = t                                           // several, in order
    val changes = mutableListOf<String>()
    for (g in tries) {
        if (g.dim.read(w) == g.value) continue
        w = g.dim.write(w, g.value)
        changes += g.words
        if (anyCanDo(printers, requirements(w))) return Fix(w, changes)
    }
    return null
}

/** Settings no printer can do any more (the printers changed): the smallest change that makes them printable. */
fun quickFix(s: PrintSettings, printers: List<Printer>): Fix? {
    if (printers.isEmpty() || anyCanDo(printers, requirements(s))) return null
    return fixFor(s, null, printers)
}

/** Everything any printer offers (the union), for building the choices. */
data class Offered(
    val color: Boolean = false, val bw: Boolean = false, val paperSizes: List<String> = emptyList(),
    val duplex: Boolean = false, val finishing: List<String> = emptyList(), val mediaTypes: List<String> = emptyList(),
    val borderless: Boolean = false, val highQuality: Boolean = false
)

fun offered(printers: List<Printer>): Offered {
    val sizes = LinkedHashSet<String>()
    val fin = LinkedHashSet<String>()
    val media = LinkedHashSet<String>()
    var o = Offered()
    for (p in printers) {
        val f = p.features ?: PLAIN
        sizes.addAll(f.paperSizes)
        fin.addAll(f.finishing)
        media.addAll(f.mediaTypes)
        o = o.copy(color = o.color || p.color, bw = o.bw || p.bw, duplex = o.duplex || f.duplex,
            borderless = o.borderless || f.borderless, highQuality = o.highQuality || f.highQuality)
    }
    return o.copy(paperSizes = sizes.toList(), finishing = fin.toList(), mediaTypes = media.toList())
}

/** "STAPLE" + ["STAPLE_TOP_LEFT", "PUNCH_LEFT"] -> ["TOP_LEFT"] */
fun finishingPositions(offeredFinishing: List<String>, group: String): List<String> =
    offeredFinishing.filter { it.startsWith(group + "_") }.map { it.substring(group.length + 1) }

// ------------------------------------------------------------------ words

fun rupees(paise: Number?): String {
    if (paise == null) return "–"
    val p = paise.toLong()
    return "₹" + if (p % 100 == 0L) "${p / 100}" else String.format(java.util.Locale.US, "%.2f", p / 100.0)
}

fun plural(n: Number, one: String, many: String) = "$n " + (if (n.toLong() == 1L) one else many)

fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1048576 -> "${(bytes / 1024.0).roundToInt()} KB"
    else -> String.format(java.util.Locale.US, if (bytes < 10485760) "%.1f MB" else "%.0f MB", bytes / 1048576.0)
}

/** "PHOTO_4X6" -> "4×6", "LEGAL" -> "Legal": short names for buttons. */
fun shortPaper(id: String): String = id.replace("PHOTO_", "").replace("X", "×").replace("LETTER", "Letter")
    .replace("LEGAL", "Legal").replace("FOLIO", "Folio").replace("TABLOID", "Tabloid")
    .replace("EXECUTIVE", "Exec.").replace("STATEMENT", "Stmt.")

/** "210 × 297 mm" */
fun dims(p: Paper): String {
    fun r(v: Double): String {
        val x = (v * 10).roundToInt() / 10.0
        return if (x == floor(x)) x.toInt().toString() else x.toString()
    }
    return r(p.widthMm) + " × " + r(p.heightMm) + " mm"
}
