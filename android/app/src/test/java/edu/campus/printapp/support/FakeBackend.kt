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
import edu.campus.printapp.net.ClaimPayment
import edu.campus.printapp.net.PaymentInfo
import edu.campus.printapp.net.UpiCheckout
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
import edu.campus.printapp.net.StaffLogin
import edu.campus.printapp.net.StaffLoginRequest
import edu.campus.printapp.net.StaffMe
import edu.campus.printapp.net.StaffOrder
import edu.campus.printapp.net.StaffView
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
    /** "demo", or "upi" (XeoGo Pay: the student pays in a UPI app, the bank's message confirms it) */
    var paymentMode = "demo"
    /** XeoGo Pay: the Xerox center's Verifier phone is on, so payments confirm by themselves */
    var autoConfirm = true
    var uploadDelayMs = 0L
    /** file name -> why the server refuses its settings on review */
    val refuse = mutableMapOf<String, String>()
    /** the server tidies pages differently from what was sent (should never happen; proves the app keeps the server's answer) */
    var serverPagesOverride: String? = null
    /** the server cannot be reached (the phone is in a dead spot) */
    @Volatile var down = false
    /** the service is waking up: its host answers "not up yet" (503) this many more times before the shop is there */
    @Volatile var waking = 0
    /** a Xerox PC with Microsoft Word is online: Word files are taken and wait to be turned into pages */
    @Volatile var word = false
    /** a server from before "I'm at the counter" existed */
    var oldServer = false
    /** the Xerox PC's pictures of the first printed sheets, by document id */
    val previews = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    // ---- college staff (the staff app): IDs made at the Xerox center, and the month's free pages
    /** username -> password */
    val staffIds = mutableMapOf("asha.kulkarni" to "kmtpx2vw9d")
    val staffNames = mutableMapOf("asha.kulkarni" to "Prof. Asha Kulkarni")
    var staffLimit = 1000
    var staffColor = false
    /** sign-in tokens that are good -> username */
    val staffTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The Xerox center made a new password: every device is signed out. */
    fun newStaffPassword(username: String, password: String) = synchronized(this) {
        staffIds[username] = password
        staffTokens.entries.removeIf { it.value == username }
    }

    fun staffUsed(username: String): Int = synchronized(this) {
        orders.values.filter { it.staff == username && it.paidAt != null }.sumOf { o ->
            o.docs.filter { it.status != "CANCELLED" }.sumOf { (it.sides ?: 0) * (it.settings?.copies ?: 1) }
        }
    }

    private fun staffView(username: String): StaffView {
        val used = staffUsed(username)
        return StaffView(username, staffNames[username] ?: username, staffLimit, used, (staffLimit - used).coerceAtLeast(0),
            "October 2026", "2026-11-01", staffColor, "Main Xerox Center")
    }

    /** Who a request is signed in as: null without a sign-in; a sign-in that is not good is refused. */
    private fun staffOf(request: RecordedRequest): String? {
        val token = request.getHeader("X-Staff-Session") ?: return null
        return staffTokens[token] ?: refuse(401, "STAFF_SIGNED_OUT", "Please sign in again with your staff ID.")
    }

    private val papers = listOf(Paper("A4", "A4", 210.0, 297.0), Paper("A3", "A3", 297.0, 420.0), Paper("PHOTO_4X6", "Photo 4 × 6 in", 101.6, 152.4))
    private val rules = PricingRules(paperSizePercent = mapOf("A3" to 200))

    fun shop() = ShopView("Main Xerox Center", 200, 1000, "INR", 50L shl 20, 300, 2000, 50, maxDocuments, paymentMode,
        bwAvailable = true, colorAvailable = true, bwOnline = true, colorOnline = true, ordersWaiting = 0,
        printing = Printing(printers, papers, mapOf("STAPLE_TOP_LEFT" to "Staple: top left"), emptyMap(), rules, PrintSettings()),
        wordFiles = word)

    /** The Xerox PC finished a Word file: it is a PDF of this many pages now. */
    fun wordReady(docId: String, pages: Int) = synchronized(this) {
        val d = doc(docId)
        d.bytes = TestFiles.pdf("pages-of-$docId.pdf", pages).readBytes()
        d.type = "PDF"
        d.source = "DOCX"
        d.pageCount = pages
        d.status = "READY"
    }

    /** The Xerox PC's Word could not open it. */
    fun wordRefused(docId: String, why: String) = synchronized(this) {
        val d = doc(docId)
        d.status = "REJECTED"
        d.problem = why
    }

    /** The Word files waiting for the Xerox PC, oldest first. */
    fun wordWaiting(): List<String> = synchronized(this) {
        orders.values.flatMap { it.docs }.filter { it.status == "CONVERTING" }.map { it.id }
    }

    // ---- state
    class Doc(val id: String, val name: String, var type: String, val size: Long) {
        var source: String? = null            // "DOCX": a Word file that was turned into a PDF
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
        var upi = false
        var claimRef: String? = null
        var claimedAt: String? = null
        var ticks = 0
        var arrivedAt: String? = null
        var collectedAt: String? = null
        /** a staff order: the staff ID it belongs to */
        var staff: String? = null
    }

    val orders = mutableMapOf<String, Order>()

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
        if (down) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        val path = request.requestUrl!!.encodedPath
        requests += request.method + " " + path
        val p = path.split('/').filter { it.isNotEmpty() }
        try {
            when {
                request.method == "GET" && path == "/api/v1/shop" && waking > 0 -> {
                    waking--
                    MockResponse().setResponseCode(503).setBody("Service Unavailable")
                }
                request.method == "GET" && path == "/api/v1/shop" -> json(ShopView.serializer(), shop())
                request.method == "PUT" && p[0] == "upload" -> upload(p[1], request)
                request.method == "GET" && p[0] == "files" -> MockResponse().setBody(okio.Buffer().write(doc(p[1]).bytes!!))
                request.method == "POST" && path == "/api/v1/staff/login" -> {
                    val req = PrintApi.JSON.decodeFromString(StaffLoginRequest.serializer(), request.body.readUtf8())
                    val typed = req.password.replace("-", "").replace(" ", "").lowercase()
                    if (staffIds[req.username.lowercase()] != typed) {
                        refuse(401, "BAD_LOGIN", "Wrong username or password.")
                    }
                    val token = "tok-" + UUID.randomUUID()
                    staffTokens[token] = req.username.lowercase()
                    json(StaffLogin.serializer(), StaffLogin(token, staffView(req.username.lowercase())))
                }
                request.method == "GET" && path == "/api/v1/staff/me" -> {
                    val who = staffOf(request) ?: refuse(401, "STAFF_SIGNED_OUT", "Please sign in again with your staff ID.")
                    json(StaffMe.serializer(), StaffMe(staffView(who)))
                }
                request.method == "GET" && path == "/api/v1/staff/orders" -> {
                    val who = staffOf(request) ?: refuse(401, "STAFF_SIGNED_OUT", "Please sign in again with your staff ID.")
                    json(kotlinx.serialization.builtins.ListSerializer(StaffOrder.serializer()),
                        orders.values.filter { it.staff == who && it.paidAt != null }.reversed().map { o ->
                            StaffOrder(o.id, o.code, o.status, if (o.collectedAt != null) "Collected" else o.status.lowercase(),
                                o.docs.first().name + (if (o.docs.size > 1) " + ${o.docs.size - 1} more" else ""), o.docs.size,
                                o.docs.sumOf { (it.sides ?: 0) * (it.settings?.copies ?: 1) }, paidAt = o.paidAt,
                                collectedAt = o.collectedAt)
                        })
                }
                request.method == "POST" && path == "/api/v1/orders" -> {
                    val o = Order(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "K7M4X")
                    o.staff = staffOf(request)
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

    private val WORD_OFF = "Word files are turned into pages by the Xerox center's computer, and it is not online right now. " +
        "Save the file as PDF and add that, or try again when the center is open."

    private fun refuse(status: Int, code: String, message: String): Nothing =
        throw Refused(status, buildJsonObject { put("error", code); put("message", message) }.toString())

    private fun doc(id: String) = orders.values.flatMap { it.docs }.first { it.id == id }

    private fun order(request: RecordedRequest, p: List<String>): MockResponse {
        val who = staffOf(request)
        val o = orders[p[3]] ?: refuse(404, "NOT_FOUND", "That order was not found.")
        // a staff order answers to its staff ID (any device signed in with it); any other order to its key
        if (o.staff != null) { if (o.staff != who) refuse(404, "NOT_FOUND", "That order was not found.") }
        else if (request.getHeader("X-Order-Key") != o.key) refuse(404, "NOT_FOUND", "That order was not found.")
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
                if (req.fileType == "DOCX" && !word) refuse(409, "WORD_NOT_AVAILABLE", WORD_OFF)
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
                    "DOCX" -> {
                        // the real server looks inside the file first; here a file that says "macro" stands for an unsafe one
                        if (file.readText(Charsets.ISO_8859_1).contains("vbaProject")) {
                            d.status = "REJECTED"
                            d.problem = "This Word file has something in it that cannot be prepared automatically."
                        } else if (!word) {
                            d.status = "REJECTED"
                            d.problem = WORD_OFF
                        } else {
                            d.type = "DOCX"
                            d.status = "CONVERTING"
                        }
                    }
                    else -> { d.status = "REJECTED"; d.problem = "This is not a PDF, JPG or PNG file." }
                }
                json(DocumentView.serializer(), dv(d))
            }
            m == "DELETE" && rest.size == 2 -> { o.docs.removeAll { it.id == rest[1] }; view(o) }
            m == "GET" && rest.size == 3 && rest[2] == "file-url" -> json(FileUrl.serializer(), FileUrl(server.url("/files/" + rest[1]).toString()))
            m == "GET" && rest.size == 3 && rest[2] == "preview" -> {
                val jpeg = previews[rest[1]] ?: refuse(404, "NOT_FOUND", "That picture was not found.")
                MockResponse().setBody(okio.Buffer().write(jpeg)).setHeader("Content-Type", "image/jpeg")
            }
            m == "POST" && rest == listOf("arrive") -> {
                if (oldServer) refuse(404, "HTTP_404", "Request not accepted (404).")
                val here = !request.body.readUtf8().contains("\"here\":false")
                if (o.collectedAt == null) {
                    if (o.paidAt == null && here) refuse(409, "NOT_PAID", "Pay for this order first: then it prints and you can collect it.")
                    o.arrivedAt = if (here) "2026-09-29T10:05:00Z" else null
                }
                view(o)
            }
            m == "POST" && rest == listOf("review") -> review(o, request)
            m == "POST" && rest == listOf("edit") -> {
                if (o.paymentStarted) refuse(409, "CANNOT_EDIT", "Payment was already started for this order.")
                o.status = "AWAITING_UPLOAD"
                view(o)
            }
            m == "POST" && rest == listOf("staff-print") -> {
                if (o.staff == null) refuse(409, "NOT_STAFF_ORDER", "This order was not made with a staff ID: it has to be paid for.")
                if (o.paidAt == null) {
                    if (o.status != "AWAITING_PAYMENT") refuse(409, "NOT_READY", "Review this order first.")
                    val pages = o.docs.sumOf { (it.sides ?: 0) * (it.settings?.copies ?: 1) }
                    val left = staffLimit - staffUsed(o.staff!!)
                    if (pages > left) refuse(409, "OVER_LIMIT", "This order takes $pages pages, but only $left of your $staffLimit free pages are left for October 2026.")
                    o.status = "QUEUED"; o.paidAt = "2026-09-29T10:00:00Z"
                    o.docs.forEach { it.status = "QUEUED" }
                }
                view(o)
            }
            m == "POST" && rest.firstOrNull() == "payment" && o.staff != null ->
                refuse(409, "STAFF_ORDER", "This is a free staff order: there is nothing to pay. Press Print.")
            m == "POST" && rest == listOf("payment") -> {
                if (paymentMode == "upi") {
                    if (!o.upi) { o.upi = true; o.amount = (o.amount ?: 0) + 1 }      // + 1 paisa: this payment's own amount
                    o.paymentStarted = true
                    val text = "%d.%02d".format(o.amount!! / 100, o.amount!! % 100)
                    json(PaymentStart.serializer(), PaymentStart("upi", gatewayOrderId = "CP" + o.code, amountPaise = o.amount!!,
                        upi = UpiCheckout("upi://pay?pa=xeroxshop@okaxis&pn=Main%20Xerox%20Center&am=$text&cu=INR", "xeroxshop@okaxis",
                            "Main Xerox Center", amountPaise = o.amount!!, amountText = text, tagPaise = 1, autoConfirm = autoConfirm)))
                } else {
                    o.paymentStarted = true
                    json(PaymentStart.serializer(), PaymentStart("demo", amountPaise = o.amount ?: 0))
                }
            }
            m == "POST" && rest == listOf("payment", "claim") -> {
                val req = PrintApi.JSON.decodeFromString(ClaimPayment.serializer(), request.body.readUtf8())
                o.claimRef = req.reference
                o.claimedAt = "2026-09-29T10:00:00Z"
                view(o)
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
            d.settings = s; d.position = i + 1; d.amount = if (o.staff != null) 0 else q.amount.toInt()
            d.printPages = n.plan.printPages; d.sides = n.plan.sides; d.sheets = n.plan.sheets
            total += if (o.staff != null) 0 else q.amount
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
        d.printPages, d.sides, d.sheets, d.amount, if (d.status == "SUBMITTED") "Printer 1 (B/W)" else null,
        printedAt = if (d.status == "COMPLETED") "2026-09-29T10:02:00Z" else null, hasPreview = previews.containsKey(d.id),
        sourceType = d.source, ahead = if (d.status == "CONVERTING") wordWaiting().indexOf(d.id).coerceAtLeast(0) else null)

    private fun view(o: Order): MockResponse = json(OrderView.serializer(), OrderView(
        o.id, o.code, o.status, stage = o.status.lowercase(), fileName = o.docs.firstOrNull()?.name, amountPaise = o.amount,
        paidAt = o.paidAt, documents = o.docs.sortedBy { it.position }.map { dv(it) },
        editable = o.status == "AWAITING_UPLOAD" || (o.status == "AWAITING_PAYMENT" && !o.paymentStarted),
        documentsDone = o.docs.count { it.status == "COMPLETED" }, totalSheets = o.docs.sumOf { (it.sheets ?: 0) * (it.settings?.copies ?: 1) },
        payment = if (o.upi) PaymentInfo("upi", o.paidAt != null, 1, "CP" + o.code, o.claimRef, o.claimedAt,
            verifiedBy = if (o.paidAt != null) "bank-alert" else null) else null,
        collected = o.collectedAt != null, completedAt = if (o.status == "COMPLETED") "2026-09-29T10:02:00Z" else null,
        collectedAt = o.collectedAt, arrivedAt = o.arrivedAt.takeIf { o.collectedAt == null },
        estimatedReadyAt = if (o.paidAt != null && o.status != "COMPLETED") "2026-09-29T10:03:10Z" else null,
        serverTime = java.time.Instant.now().toString(),
        freePages = if (o.staff != null) o.docs.sumOf { (it.sides ?: 0) * (it.settings?.copies ?: 1) } else null
    ))

    /** The staff press "Handed over" at the counter. */
    fun handOver(orderId: String) = synchronized(this) {
        orders.getValue(orderId).collectedAt = "2026-09-29T10:06:00Z"
    }

    /** XeoGo Pay: the bank's message arrived (the Verifier phone passed it on): the order is paid. */
    fun bankConfirms(orderId: String) = synchronized(this) {
        val o = orders.getValue(orderId)
        o.status = "QUEUED"; o.paidAt = "2026-09-29T10:00:05Z"
        o.docs.forEach { it.status = "QUEUED" }
    }

    private fun <T> json(s: KSerializer<T>, v: T) =
        MockResponse().setBody(PrintApi.JSON.encodeToString(s, v)).setHeader("Content-Type", "application/json")

    fun close() = server.shutdown()
}
