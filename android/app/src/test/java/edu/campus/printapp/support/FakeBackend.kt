package edu.campus.printapp.support

import edu.campus.printapp.core.Facts
import edu.campus.printapp.core.ImageInfo
import edu.campus.printapp.core.Limits
import edu.campus.printapp.core.Paper
import edu.campus.printapp.core.PricingRules
import edu.campus.printapp.core.PrintSettings
import edu.campus.printapp.core.displaySpec
import edu.campus.printapp.core.normalize
import edu.campus.printapp.core.price
import edu.campus.printapp.flow.FileTypes
import edu.campus.printapp.net.AddDocumentRequest
import edu.campus.printapp.net.CreateOrderResponse
import edu.campus.printapp.net.DocumentView
import edu.campus.printapp.net.FileUrl
import edu.campus.printapp.net.OrderView
import edu.campus.printapp.net.PaymentStart
import edu.campus.printapp.net.PrintApi
import edu.campus.printapp.net.PrinterOption
import edu.campus.printapp.net.Printing
import edu.campus.printapp.net.ReviewRequest
import edu.campus.printapp.net.ShopView
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A pretend print service with the version 4 API, for testing the app's
 * order flow step by step (the real backend is used by LiveBackendTest).
 * Knobs make it refuse files, slow uploads down, or lock the price.
 */
class FakeBackend : Dispatcher() {

    val server = MockWebServer().apply { dispatcher = this@FakeBackend; start() }
    val base: String get() = server.url("/").toString().trimEnd('/')
    val requests = CopyOnWriteArrayList<String>()           // "POST /api/v1/orders/.../review"
    var lastReview: ReviewRequest? = null

    // ---- knobs
    var printers = listOf(
        PrinterOption("p1", "Printer 1 (B/W)", true, color = false, bw = true, paperSizes = listOf("A4", "A3"), duplex = true,
            finishing = listOf("STAPLE_TOP_LEFT")),
        PrinterOption("p2", "Printer 2 (Colour)", true, color = true, bw = true, paperSizes = listOf("A4", "PHOTO_4X6"),
            borderless = true, highQuality = true)
    )
    var maxDocuments = 25
    var uploadDelayMs = 0L
    /** file name -> why the server refuses its settings on review */
    val refuse = mutableMapOf<String, String>()
    /** the server tidies pages differently from what was sent (should never happen; proves the app keeps the server's answer) */
    var serverPagesOverride: String? = null

    private val papers = listOf(Paper("A4", "A4", 210.0, 297.0), Paper("A3", "A3", 297.0, 420.0), Paper("PHOTO_4X6", "Photo 4 × 6 in", 101.6, 152.4))
    private val rules = PricingRules(paperSizePercent = mapOf("A3" to 200))

    fun shop() = ShopView("Main Xerox Center", 200, 1000, "INR", 50L shl 20, 300, 2000, 50, maxDocuments, "demo",
        bwAvailable = true, colorAvailable = true, bwOnline = true, colorOnline = true, ordersWaiting = 0,
        printing = Printing(printers, papers, mapOf("STAPLE_TOP_LEFT" to "Staple: top left"), emptyMap(), rules, PrintSettings()))

    // ---- state
    class Doc(val id: String, val name: String, val type: String, val size: Long) {
        var status = "UPLOADING"
        var bytes: ByteArray? = null
        var pageCount: Int? = null
        var image: ImageInfo? = null
        var settings: PrintSettings? = null
        var amount: Int? = null
        var printPages: Int? = null
        var sides: Int? = null
        var sheets: Int? = null
        var position = 0
        var problem: String? = null
    }

    class Order(val id: String, val key: String, val code: String) {
        var status = "AWAITING_UPLOAD"
        val docs = mutableListOf<Doc>()
        var amount: Int? = null
        var paymentStarted = false
        var paidAt: String? = null
        var ticks = 0
    }

    val orders = mutableMapOf<String, Order>()

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
        val path = request.requestUrl!!.encodedPath
        requests += request.method + " " + path
        val p = path.split('/').filter { it.isNotEmpty() }
        try {
            when {
                request.method == "GET" && path == "/api/v1/shop" -> json(ShopView.serializer(), shop())
                request.method == "PUT" && p[0] == "upload" -> upload(p[1], request)
                request.method == "GET" && p[0] == "files" -> MockResponse().setBody(okio.Buffer().write(doc(p[1]).bytes!!))
                request.method == "POST" && path == "/api/v1/orders" -> {
                    val o = Order(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "K7M4X")
                    orders[o.id] = o
                    json(CreateOrderResponse.serializer(), CreateOrderResponse(o.id, o.key, o.code))
                }
                else -> order(request, p)
            }
        } catch (e: Refused) {
            MockResponse().setResponseCode(e.status).setBody(e.body)
        }
    }

    private class Refused(val status: Int, val body: String) : Exception()

    private fun refuse(status: Int, code: String, message: String): Nothing =
        throw Refused(status, buildJsonObject { put("error", code); put("message", message) }.toString())

    private fun doc(id: String) = orders.values.flatMap { it.docs }.first { it.id == id }

    private fun order(request: RecordedRequest, p: List<String>): MockResponse {
        val o = orders[p[3]] ?: refuse(404, "NOT_FOUND", "That order was not found.")
        if (request.getHeader("X-Order-Key") != o.key) refuse(404, "NOT_FOUND", "That order was not found.")
        val rest = p.drop(4)
        val m = request.method
        return when {
            m == "GET" && rest.isEmpty() -> {
                if (o.paidAt != null && o.status != "COMPLETED") {
                    o.ticks++
                    if (o.ticks >= 2) { o.status = "PRINTING"; o.docs.forEach { it.status = "SUBMITTED" } }
                    if (o.ticks >= 3) { o.status = "COMPLETED"; o.docs.forEach { it.status = "COMPLETED" } }
                }
                view(o)
            }
            m == "POST" && rest == listOf("documents") -> {
                if (o.status != "AWAITING_UPLOAD") refuse(409, "BAD_STATE", "This order can no longer be changed.")
                if (o.docs.size >= maxDocuments) refuse(400, "TOO_MANY_DOCUMENTS", "One order can have up to $maxDocuments files.")
                val req = PrintApi.JSON.decodeFromString(AddDocumentRequest.serializer(), request.body.readUtf8())
                val d = Doc(UUID.randomUUID().toString(), req.fileName, req.fileType, req.fileSizeBytes)
                o.docs += d
                ticket(d)
            }
            m == "POST" && rest.size == 3 && rest[2] == "upload-url" -> ticket(o.docs.first { it.id == rest[1] })
            m == "POST" && rest.size == 3 && rest[2] == "uploaded" -> {
                val d = o.docs.first { it.id == rest[1] }
                val file = File.createTempFile("fake", ".bin").apply { writeBytes(d.bytes ?: refuse(409, "NOT_UPLOADED", "The file has not arrived yet.")) }
                when (FileTypes.detect(file)) {
                    "PDF" -> { d.status = "READY"; d.pageCount = Regex("/Type /Page\\b(?!s)").findAll(file.readText(Charsets.ISO_8859_1)).count() }
                    "PNG", "JPEG" -> {
                        val (w, h) = TestFiles.pngSize(file)!!
                        d.status = "READY"; d.pageCount = 1; d.image = ImageInfo(w, h, 72.0, 1)
                    }
                    else -> { d.status = "REJECTED"; d.problem = "This is not a PDF, JPG or PNG file." }
                }
                json(DocumentView.serializer(), dv(d))
            }
            m == "DELETE" && rest.size == 2 -> { o.docs.removeAll { it.id == rest[1] }; view(o) }
            m == "GET" && rest.size == 3 && rest[2] == "file-url" -> json(FileUrl.serializer(), FileUrl(server.url("/files/" + rest[1]).toString()))
            m == "POST" && rest == listOf("review") -> review(o, request)
            m == "POST" && rest == listOf("edit") -> {
                if (o.paymentStarted) refuse(409, "CANNOT_EDIT", "Payment was already started for this order.")
                o.status = "AWAITING_UPLOAD"
                view(o)
            }
            m == "POST" && rest == listOf("payment") -> {
                o.paymentStarted = true
                json(PaymentStart.serializer(), PaymentStart("demo", amountPaise = o.amount ?: 0))
            }
            m == "POST" && rest == listOf("payment", "demo") -> {
                o.status = "QUEUED"; o.paidAt = "2026-09-29T10:00:00Z"
                o.docs.forEach { it.status = "QUEUED" }
                view(o)
            }
            m == "POST" && rest == listOf("cancel") -> { o.status = "CANCELLED"; view(o) }
            else -> refuse(404, "HTTP_404", "Request not accepted (404).")
        }
    }

    private fun upload(docId: String, request: RecordedRequest): MockResponse {
        doc(docId).bytes = request.body.readByteArray()
        return MockResponse().setResponseCode(200).apply { if (uploadDelayMs > 0) setHeadersDelay(uploadDelayMs, TimeUnit.MILLISECONDS) }
    }

    private fun review(o: Order, request: RecordedRequest): MockResponse {
        if (o.status != "AWAITING_UPLOAD" && !(o.status == "AWAITING_PAYMENT" && !o.paymentStarted)) {
            refuse(409, "PRICED", "This order was already priced. Go back to editing to change files.")
        }
        val req = PrintApi.JSON.decodeFromString(ReviewRequest.serializer(), request.body.readUtf8())
        lastReview = req
        val problems = req.documents.mapNotNull { c ->
            val d = o.docs.first { it.id == c.id }
            refuse[d.name]?.let { Triple(d.id, d.name, it) }
        }
        if (problems.isNotEmpty()) {
            throw Refused(400, buildJsonObject {
                put("error", "CHECK_SETTINGS")
                put("message", if (problems.size == 1) "${problems[0].second}: ${problems[0].third}" else "${problems.size} files need a change before you can pay.")
                put("documents", buildJsonArray {
                    problems.forEach { (id, name, msg) -> add(buildJsonObject { put("id", id); put("fileName", name); put("message", msg) }) }
                })
            }.toString())
        }
        var total = 0L
        req.documents.forEachIndexed { i, c ->
            val d = o.docs.first { it.id == c.id }
            val n = normalize(c.settings, Facts(d.type, d.pageCount!!), Limits(50, 300), shop().printers())
            val s = if (serverPagesOverride != null && d.type == "PDF") n.settings.copy(pages = serverPagesOverride) else n.settings
            val q = price(s, n.plan, 200, 1000, rules)
            d.settings = s; d.position = i + 1; d.amount = q.amount.toInt()
            d.printPages = n.plan.printPages; d.sides = n.plan.sides; d.sheets = n.plan.sheets
            total += q.amount
        }
        o.amount = total.toInt()
        o.status = "AWAITING_PAYMENT"
        return view(o)
    }

    private fun ticket(d: Doc): MockResponse {
        val body = buildJsonObject {
            put("document", PrintApi.JSON.encodeToJsonElement(DocumentView.serializer(), dv(d)))
            put("uploadUrl", server.url("/upload/" + d.id).toString())
            put("uploadContentType", if (d.type == "PDF") "application/pdf" else "image/" + d.type.lowercase())
        }
        return MockResponse().setBody(body.toString()).setHeader("Content-Type", "application/json")
    }

    private fun dv(d: Doc) = DocumentView(d.id, d.position, d.name, d.type, d.size, d.status, d.status, d.problem, d.pageCount, d.image,
        d.settings, d.settings?.let { s -> if (s.pages == null) "All ${d.pageCount} pages" else displaySpec(s.pages) },
        d.printPages, d.sides, d.sheets, d.amount, if (d.status == "SUBMITTED") "Printer 1 (B/W)" else null)

    private fun view(o: Order): MockResponse = json(OrderView.serializer(), OrderView(
        o.id, o.code, o.status, stage = o.status.lowercase(), fileName = o.docs.firstOrNull()?.name, amountPaise = o.amount,
        paidAt = o.paidAt, documents = o.docs.sortedBy { it.position }.map { dv(it) },
        editable = o.status == "AWAITING_UPLOAD" || (o.status == "AWAITING_PAYMENT" && !o.paymentStarted),
        documentsDone = o.docs.count { it.status == "COMPLETED" }, totalSheets = o.docs.sumOf { (it.sheets ?: 0) * (it.settings?.copies ?: 1) }
    ))

    private fun <T> json(s: KSerializer<T>, v: T) =
        MockResponse().setBody(PrintApi.JSON.encodeToString(s, v)).setHeader("Content-Type", "application/json")

    fun close() = server.shutdown()
}
