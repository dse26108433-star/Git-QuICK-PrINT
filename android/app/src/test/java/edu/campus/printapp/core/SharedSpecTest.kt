package edu.campus.printapp.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The shared cases in spec/cases (the .json files), the same ones the server
 * (SharedRulesTest, DatabaseTest), the Xerox PC (LayoutTest) and the website
 * (spec/web-core.test.js) are checked with. If this passes, the app shows the
 * same pages, prices, printer rules and layout as everything else.
 */
class SharedSpecTest {

    private fun cases(name: String): JsonObject {
        val dir = System.getProperty("campusprint.spec") ?: "../../spec/cases"
        return Json.parseToJsonElement(File(dir, name).readText()).jsonObject
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /** Settings from a case: missing = defaults. */
    private fun settings(o: JsonObject?): PrintSettings {
        var s = PrintSettings()
        if (o == null) return s
        o["copies"]?.jsonPrimitive?.intOrNull?.let { s = s.copy(copies = it) }
        o["color"]?.jsonPrimitive?.booleanOrNull?.let { s = s.copy(color = it) }
        str(o["duplex"])?.let { s = s.copy(duplex = it) }
        str(o["paperSize"])?.let { s = s.copy(paperSize = it) }
        str(o["orientation"])?.let { s = s.copy(orientation = it) }
        str(o["scaling"])?.let { s = s.copy(scaling = it) }
        o["scalePercent"]?.jsonPrimitive?.intOrNull?.let { s = s.copy(scalePercent = it) }
        o["pagesPerSheet"]?.jsonPrimitive?.intOrNull?.let { s = s.copy(pagesPerSheet = it) }
        o["marginMm"]?.jsonPrimitive?.intOrNull?.let { s = s.copy(marginMm = it) }
        o["center"]?.jsonPrimitive?.booleanOrNull?.let { s = s.copy(center = it) }
        str(o["staple"])?.let { s = s.copy(staple = it) }
        str(o["punch"])?.let { s = s.copy(punch = it) }
        str(o["bind"])?.let { s = s.copy(bind = it) }
        str(o["mediaType"])?.let { s = s.copy(mediaType = it) }
        str(o["quality"])?.let { s = s.copy(quality = it) }
        return s
    }

    @Test
    fun pageRanges() {
        val all = cases("page-ranges.json")["cases"]!!.jsonArray
        for (c in all.map { it.jsonObject }) {
            val text = str(c["text"])
            val total = c["total"]!!.jsonPrimitive.int
            if (c["error"]?.jsonPrimitive?.boolean == true) {
                try {
                    parsePages(text, total)
                    fail("pages \"$text\" should be refused")
                } catch (e: UserError) {
                    // expected: a message for the student
                }
                continue
            }
            val r = parsePages(text, total)
            assertEquals("pages \"$text\"", str(c["spec"]), r.spec)
            assertEquals("pages \"$text\"", c["count"]!!.jsonPrimitive.int, r.count)
            // tapping the same pages on the page pictures gives the same tidy form
            assertEquals("pages \"$text\" from pictures", str(c["spec"]), specFromPages(pagesFromSpec(r.spec, total), total))
        }
        assertTrue(all.size >= 19)
    }

    @Test
    fun pricing() {
        val p = cases("pricing.json")
        val rulesJson = p["rules"]!!.jsonObject
        fun pct(name: String) = rulesJson[name]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
        val rules = PricingRules(pct("paperSizePercent"), pct("mediaTypePercent"), pct("finishingPaise"))
        val all = p["cases"]!!.jsonArray.map { it.jsonObject }
        for (c in all) {
            val name = str(c["name"])
            val s = settings(c["settings"]?.jsonObject)
            val pl = plan(c["printPages"]!!.jsonPrimitive.int, s)
            val bw = c["priceBwPaise"]?.jsonPrimitive?.intOrNull ?: p["priceBwPaise"]!!.jsonPrimitive.int
            val q = price(s, pl, bw, p["priceColorPaise"]!!.jsonPrimitive.int, rules)
            assertEquals("$name: sides", c["sides"]!!.jsonPrimitive.int, pl.sides)
            assertEquals("$name: sheets", c["sheets"]!!.jsonPrimitive.int, pl.sheets)
            assertEquals("$name: per side", c["perSide"]!!.jsonPrimitive.int, q.perSide)
            assertEquals("$name: amount", c["amount"]!!.jsonPrimitive.int.toLong(), q.amount)
        }
        assertTrue(all.size >= 12)
    }

    @Test
    fun printerRules() {
        val p = cases("printer-rules.json")
        val printers = p["printers"]!!.jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            val f = o["features"]
            Printer(color = o["color"]!!.jsonPrimitive.boolean, bw = o["bw"]!!.jsonPrimitive.boolean,
                features = if (f == null || f is JsonNull) null else f.jsonObject.let { fo ->
                    fun list(k: String) = (fo[k] as? JsonArray)?.map { it.jsonPrimitive.content }
                    Features(list("paperSizes") ?: listOf("A4"), fo["duplex"]?.jsonPrimitive?.boolean ?: false,
                        list("finishing") ?: emptyList(), list("mediaTypes") ?: emptyList(),
                        fo["borderless"]?.jsonPrimitive?.boolean ?: false, fo["highQuality"]?.jsonPrimitive?.boolean ?: false)
                })
        }
        val all = p["cases"]!!.jsonArray.map { it.jsonObject }
        for (c in all) {
            val r = c["req"]!!.jsonObject
            val req = Requirements(
                color = r["color"]?.jsonPrimitive?.booleanOrNull, paperSize = str(r["paperSize"]), duplex = str(r["duplex"]),
                finishing = (r["finishing"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                mediaType = str(r["mediaType"]), borderless = r["borderless"]?.jsonPrimitive?.booleanOrNull ?: false,
                quality = str(r["quality"])
            )
            val printer = str(c["printer"])!!
            assertEquals("printer $printer $r", c["can"]!!.jsonPrimitive.boolean, canDo(printers.getValue(printer), req))
        }
        assertTrue(all.size >= 18)
    }

    @Test
    fun layout() {
        val all = cases("layout.json")["cases"]!!.jsonArray.map { it.jsonObject }
        for (c in all) {
            val name = str(c["name"])
            val s = c["settings"]!!.jsonObject
            val paperMm = c["paperMm"]!!.jsonArray.map { it.jsonPrimitive.double }
            val o = LayoutOptions(
                paperW = paperMm[0] * MM, paperH = paperMm[1] * MM, orientation = str(s["orientation"]) ?: "AUTO",
                marginPt = (s["marginMm"]?.jsonPrimitive?.double ?: 5.0) * MM, scaling = str(s["scaling"]) ?: "FIT",
                scalePercent = s["scalePercent"]?.jsonPrimitive?.intOrNull ?: 100,
                pagesPerSheet = s["pagesPerSheet"]?.jsonPrimitive?.intOrNull ?: 1,
                center = s["center"]?.jsonPrimitive?.booleanOrNull ?: true
            )
            val pages = c["pages"]!!.jsonArray.map { it.jsonArray.let { a -> Size(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double) } }
            val got = layout(pages, o)
            val want = c["sheets"]!!.jsonArray.map { it.jsonObject }
            assertEquals("$name: sheets", want.size, got.size)
            got.forEachIndexed { i, g ->
                val w = want[i]
                close("$name sheet w", w["w"]!!.jsonPrimitive.double, g.w)
                close("$name sheet h", w["h"]!!.jsonPrimitive.double, g.h)
                val clip = w["clip"]
                assertEquals("$name clip", clip != null && clip !is JsonNull, g.clip != null)
                g.clip?.let { gc ->
                    val wc = clip!!.jsonArray.map { it.jsonPrimitive.double }
                    close("$name clip x", wc[0], gc.x)
                    close("$name clip w", wc[2], gc.w)
                }
                val cells = w["cells"]!!.jsonArray.map { it.jsonObject }
                assertEquals("$name cells", cells.size, g.cells.size)
                g.cells.forEachIndexed { k, gc ->
                    val wc = cells[k]
                    assertEquals("$name page", wc["page"]!!.jsonPrimitive.int, gc.page)
                    close("$name x", wc["x"]!!.jsonPrimitive.double, gc.x)
                    close("$name y", wc["y"]!!.jsonPrimitive.double, gc.y)
                    close("$name w", wc["w"]!!.jsonPrimitive.double, gc.w)
                    close("$name h", wc["h"]!!.jsonPrimitive.double, gc.h)
                }
                // one sheet at a time (what the preview draws) gives the same answer
                assertEquals("$name one sheet", g, layoutSheet(i, pages.size, { pages[it] }, o))
            }
        }
        assertTrue(all.size >= 18)
    }

    private fun close(what: String, want: Double, got: Double) = assertTrue("$what: $got vs $want", abs(want - got) < 0.001)
}
