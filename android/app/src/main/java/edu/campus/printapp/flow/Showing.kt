package edu.campus.printapp.flow

import edu.campus.printapp.core.plural
import edu.campus.printapp.net.DocumentView
import edu.campus.printapp.net.OrderView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Words for the "your prints" screen: the times (paid, ready at about,
 * collected) and one line about each file. Plain Kotlin, so they are tested
 * on a computer; the same words as the website.
 */

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

/** "10:42 am", with the day when that is not today. nowMs: this phone's clock, set right by the server's. */
fun clockText(iso: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val at = t.atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (if (at.toLocalDate() == today) TIME else DAY_TIME).format(at).lowercase(Locale.ENGLISH)
}

/** "Paid 10:42 am  ·  Ready at about 10:47 am" */
fun whenLine(v: OrderView, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (v.paidAt == null) return ""
    val out = mutableListOf((if (v.isFree) "Sent " else "Paid ") + clockText(v.paidAt, nowMs, zone))
    when {
        v.isCollected -> out += "Collected" + (v.collectedAt?.let { " " + clockText(it, nowMs, zone) } ?: "")
        v.status == "COMPLETED" -> out += "Ready since " + clockText(v.completedAt, nowMs, zone)
        v.status == "QUEUED" || v.status == "PRINTING" -> {
            val about = runCatching { Instant.parse(v.estimatedReadyAt) }.getOrNull()
            if (about != null) {
                // an estimate: shown to the next full minute
                val minute = Instant.ofEpochSecond((about.epochSecond + 59) / 60 * 60)
                out += "Ready at about " + clockText(minute.toString(), nowMs, zone)
            } else if (v.serverTime != null) {
                out += "Prints as soon as a printer is back online"
            }
        }
    }
    return out.joinToString("  ·  ")
}

/** "pages 3, 7 · 2 copies · Colour · A4 · 4 sheets" */
fun docSummary(sd: DocumentView): String {
    val s = sd.settings ?: return if (sd.fileType == "PDF") (sd.pageCount?.let { plural(it, "page", "pages") } ?: "") else "Picture"
    val out = mutableListOf<String>()
    out += if (sd.fileType != "PDF") "Picture" else if (s.pages != null) "pages " + (sd.pagesText ?: s.pages) else (sd.pagesText ?: "All pages")
    if (s.copies > 1) out += "${s.copies} copies"
    out += if (s.color) "Colour" else "B/W"
    if (s.duplex != "ONE_SIDED") out += "two-sided"
    out += s.paperSize.replace("PHOTO_", "").replace("X", "×")
    if (s.pagesPerSheet > 1) out += "${s.pagesPerSheet} per sheet"
    out += plural((sd.sheets ?: 0) * s.copies, "sheet", "sheets")
    return out.joinToString(" · ")
}
