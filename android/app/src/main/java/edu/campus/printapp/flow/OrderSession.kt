package edu.campus.printapp.flow

import edu.campus.printapp.core.Choice
import edu.campus.printapp.core.Facts
import edu.campus.printapp.core.Fix
import edu.campus.printapp.core.Limits
import edu.campus.printapp.core.Normalized
import edu.campus.printapp.core.Offered
import edu.campus.printapp.core.Paper
import edu.campus.printapp.core.Price
import edu.campus.printapp.core.PrintSettings
import edu.campus.printapp.core.Printer
import edu.campus.printapp.core.UserError
import edu.campus.printapp.core.normalize
import edu.campus.printapp.core.offered
import edu.campus.printapp.core.plural
import edu.campus.printapp.core.price
import edu.campus.printapp.core.quickFix
import edu.campus.printapp.core.specFromPages
import edu.campus.printapp.net.AddDocumentRequest
import edu.campus.printapp.net.ApiException
import edu.campus.printapp.net.ClaimPayment
import edu.campus.printapp.net.ConfirmPayment
import edu.campus.printapp.net.DocumentChoice
import edu.campus.printapp.net.DocumentView
import edu.campus.printapp.net.OrderView
import edu.campus.printapp.net.PaymentStart
import edu.campus.printapp.net.PrintApi
import edu.campus.printapp.net.ReviewRequest
import edu.campus.printapp.net.ShopView
import edu.campus.printapp.net.UpiCheckout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class DocStatus { READING, QUEUED, UPLOADING, CHECKING, READY, ERROR, CANCELLED }

/** One file of the order being built, on this phone. */
data class Doc(
    val local: Long,
    val name: String,
    val size: Long,
    val file: File? = null,
    val type: String? = null,             // PDF, PNG, JPEG (from the first bytes)
    val status: DocStatus = DocStatus.READING,
    val progress: Float = 0f,
    val error: String? = null,
    val canRetry: Boolean = false,
    val id: String? = null,               // the server's document id
    val pageCount: Int? = null,
    val image: edu.campus.printapp.core.ImageInfo? = null,       // the server's measurements
    val localImage: edu.campus.printapp.core.ImageInfo? = null,  // the phone's, until the server answers
    val settings: PrintSettings? = null,  // the student's choices
    val serverError: String? = null       // why the server refused these settings
) {
    val busy: Boolean get() = status == DocStatus.READING || status == DocStatus.QUEUED ||
        status == DocStatus.UPLOADING || status == DocStatus.CHECKING
    val picture: Boolean get() = type != null && type != "PDF"
    val imageInfo get() = image ?: localImage
}

enum class Step { HOME, SETUP, REVIEW, PAY, STATUS }

data class OrderRef(val orderId: String, val key: String, val code: String)

enum class NoticeKind { OK, WARN, BAD }
data class Notice(val text: String, val kind: NoticeKind = NoticeKind.OK)

/** The bottom bar: total, and why "Review order" cannot be pressed yet. */
data class Checkout(val total: Long, val sheets: Int, val files: Int, val why: String?, val needs: Long?, val uploading: Boolean) {
    val canReview: Boolean get() = why == null
}

data class SessionState(
    val shop: ShopView? = null,
    val shopError: String? = null,
    val printers: List<Printer> = emptyList(),
    val offered: Offered = Offered(),
    val step: Step = Step.HOME,
    val docs: List<Doc> = emptyList(),
    val selected: Long? = null,
    val draft: OrderRef? = null,
    val reviewing: Boolean = false,
    val order: OrderView? = null,         // on REVIEW: as priced by the server; on STATUS: as it prints
    val current: SavedOrder? = null,      // the order on REVIEW / STATUS
    val paying: Boolean = false,
    val paymentStarted: Boolean = false,  // the order cannot be changed any more
    val payError: String? = null,
    val recent: List<Pair<SavedOrder, OrderView?>> = emptyList(),
    val restoring: Boolean = false,
    // CampusPay (direct UPI), on PAY:
    val upi: UpiCheckout? = null,
    val upiRef: String = "",              // the UPI reference number field
    val upiError: String? = null,
    val claiming: Boolean = false,
    val upiConfirming: Boolean = false,   // back from the UPI app: waiting for the bank's message
    val upiHelpOffered: Boolean = false,  // "Paid, but nothing happens?" (after a while)
    val upiHelp: Boolean = false          // the reference number form is open
) {
    fun doc(local: Long?): Doc? = docs.find { it.local == local }
    val selectedDoc: Doc? get() = doc(selected)
}

private val FINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "EXPIRED")

/**
 * One student's order, from picking files to collecting the pages: the same
 * steps as the website, in plain Kotlin (no Android), so it runs in tests
 * against the real backend.
 *
 * The server decides: files are checked by it, the settings are checked and
 * priced by it on review, and from then on the app shows and keeps the
 * server's settings. What the student reviews is what prints.
 */
class OrderSession(
    private val api: PrintApi,
    private val scope: CoroutineScope,
    private val reader: FileReader,
    private val drafts: DraftStore,
    private val memory: OrderMemory,
    parallelUploads: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where copies of restored files are kept. */
    private val workDir: File? = null,
    /** Unexpected problems, for the phone's log (the student sees a short message). */
    private val log: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    /** Razorpay's screen should open (the Activity does that). */
    private val _checkout = MutableSharedFlow<PaymentStart>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val checkout: SharedFlow<PaymentStart> = _checkout.asSharedFlow()

    /** A file needs the student's attention: open it (and show the problem). */
    private val _problems = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val problems: SharedFlow<Long> = _problems.asSharedFlow()

    private val nextLocal = AtomicLong(1)
    private val uploads = Semaphore(parallelUploads)
    private val reads = Semaphore(2)
    private val orderLock = Mutex()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val incoming = ConcurrentHashMap<Long, IncomingFile>()
    private val removed = ConcurrentHashMap.newKeySet<Long>()
    private val knownPaper = ConcurrentHashMap<String, Paper>()
    private var pollJob: Job? = null
    private var saveJob: Job? = null

    private fun notice(text: String, kind: NoticeKind = NoticeKind.OK) {
        _notices.tryEmit(Notice(text, kind))
    }

    // ================================================================== the shop and its printers

    suspend fun loadShop(): ShopView? = try {
        val shop = api.shop()
        if (shop.printing == null) {
            _state.update { it.copy(shopError = "The print service is being updated. Please try again in a few minutes.") }
            null
        } else {
            shop.printing.paperSizes.forEach { knownPaper[it.id] = it }
            val before = _state.value.shop
            val printers = shop.printers()
            _state.update { it.copy(shop = shop, shopError = null, printers = printers, offered = offered(printers)) }
            if (before != null && featuresOf(before) != featuresOf(shop) && _state.value.step == Step.SETUP) {
                notice("The printers changed: the options shown are up to date.", NoticeKind.WARN)
            }
            shop
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(shopError = friendly(e)) }
        null
    }

    /** What the printers can do (not whether they are online right now): a change means new options. */
    private fun featuresOf(s: ShopView) = s.printing?.copy(printers = s.printing.printers.map { it.copy(online = false) })

    fun paper(id: String): Paper =
        _state.value.shop?.printing?.paperSizes?.find { it.id == id } ?: knownPaper[id] ?: Paper(id, id, 210.0, 297.0)

    fun finishingLabel(id: String): String = _state.value.shop?.printing?.finishing?.get(id) ?: id
    fun mediaLabel(id: String): String = _state.value.shop?.printing?.mediaTypes?.get(id) ?: id

    private fun limits(): Limits {
        val s = _state.value.shop
        return Limits(s?.maxCopies ?: 50, s?.maxPages ?: 300)
    }

    /** The settings as they will print (tidied), with the plan and any problem. Null while the file is being read. */
    fun norm(d: Doc): Normalized? {
        val s = d.settings ?: return null
        val pages = d.pageCount ?: return null
        val type = d.type ?: return null
        return normalize(s, Facts(type, pages) { paper(it).label }, limits(), _state.value.printers)
    }

    fun price(d: Doc, n: Normalized? = norm(d)): Price? {
        val shop = _state.value.shop ?: return null
        if (n == null) return null
        return price(n.settings, n.plan, shop.priceBwPaise, shop.priceColorPaise, shop.printing?.pricing)
    }

    fun checkout(s: SessionState = _state.value): Checkout {
        val docs = s.docs
        var total = 0L
        var sheets = 0
        val busy = docs.filter { it.busy }
        val bad = docs.filter { it.status == DocStatus.ERROR || it.status == DocStatus.CANCELLED }
        val invalid = mutableListOf<Doc>()
        for (d in docs) {
            val n = if (d.status == DocStatus.READY || d.busy) norm(d) else null
            if (n == null) continue
            if (n.error != null || d.serverError != null) { invalid += d; continue }
            total += price(d, n)?.amount ?: 0
            sheets += n.plan.sheets * n.settings.copies
        }
        val why = when {
            docs.isEmpty() -> "Add a file to print."
            busy.isNotEmpty() -> "Wait until every file is uploaded."
            bad.isNotEmpty() -> (if (bad.size == 1) "One file needs" else "${bad.size} files need") + " attention: try again or remove."
            invalid.isNotEmpty() -> (if (invalid.size == 1) "One file needs" else "${invalid.size} files need") + " a change."
            else -> null
        }
        return Checkout(total, sheets, docs.size, why, (bad.firstOrNull() ?: invalid.firstOrNull())?.local, busy.isNotEmpty())
    }

    // ================================================================== adding files

    /**
     * Adds files to the order, all at once. Each shows up straight away; the
     * slow parts (copying, reading, uploading, the server's check) run in the
     * background, a few at a time, each with its own progress.
     */
    fun addFiles(files: List<IncomingFile>) {
        if (files.isEmpty()) return
        scope.launch {
            val shop = _state.value.shop ?: loadShop() ?: run {
                notice(_state.value.shopError ?: "Cannot reach the print service.", NoticeKind.BAD)
                return@launch
            }
            when (_state.value.step) {
                Step.REVIEW -> {
                    notice("Tap “Change something” first to add more files.", NoticeKind.WARN)
                    return@launch
                }
                Step.STATUS -> resetToStart()
                else -> {}
            }
            val room = shop.maxDocuments - _state.value.docs.size
            if (room <= 0) {
                notice("One order can have up to ${shop.maxDocuments} files. Start a second order for the rest.", NoticeKind.WARN)
                return@launch
            }
            var skipped = 0
            var over = 0
            val added = mutableListOf<Doc>()
            for (f in files) {
                if (_state.value.docs.any { it.name == f.name && it.size == f.size && f.size > 0 } ||
                    added.any { it.name == f.name && it.size == f.size && f.size > 0 }) { skipped++; continue }
                if (added.size >= room) { over++; continue }
                val d = Doc(nextLocal.getAndIncrement(), f.name, f.size)
                incoming[d.local] = f
                added += d
            }
            if (skipped > 0) notice(plural(skipped, "file was", "files were") + " already in your list.", NoticeKind.WARN)
            if (over > 0) notice(plural(over, "file was", "files were") + " not added: one order can have up to ${shop.maxDocuments} files.", NoticeKind.WARN)
            if (added.isEmpty()) return@launch
            _state.update { s ->
                s.copy(docs = s.docs + added, step = Step.SETUP,
                    selected = s.selected?.takeIf { sel -> s.docs.any { it.local == sel } } ?: added.first().local)
            }
            added.forEach { d -> jobs[d.local] = scope.launch { prepare(d.local) } }
        }
    }

    /** On the phone first: is it a file we can print, and how many pages? Then upload it. */
    private suspend fun prepare(local: Long) {
        try {
            reads.withPermit {
                val shop = _state.value.shop ?: throw UserError("Cannot reach the print service.")
                var d = doc(local) ?: return
                val file = d.file ?: (incoming[local] ?: throw UserError("This file cannot be opened.")).fetch()
                if (local in removed) { file.delete(); return }
                set(local) { it.copy(file = file, size = file.length()) }
                d = doc(local) ?: return
                if (d.size == 0L) throw UserError("This file is empty.")
                val type = withContext(Dispatchers.IO) { FileTypes.detect(file) }
                    ?: throw UserError("Only PDF, JPG and PNG files can be printed.")
                if (d.size > shop.maxFileSizeBytes) {
                    throw UserError("Too big: files can be up to ${shop.maxFileSizeBytes / 1048576} MB.")
                }
                val facts = reader.read(file, type)
                val maxFilePages = if (shop.maxFilePages > 0) shop.maxFilePages else shop.maxPages
                if (facts.pageCount > maxFilePages) {
                    throw UserError("This PDF has ${facts.pageCount} pages. Files can have up to $maxFilePages pages.")
                }
                set(local) {
                    it.copy(type = type, pageCount = facts.pageCount, localImage = facts.image,
                        settings = it.settings ?: startingSettings(), status = DocStatus.QUEUED, error = null)
                }
            }
            send(local)
        } catch (e: CancellationException) {
            if (local !in removed) set(local) { if (it.status == DocStatus.READING) it.copy(status = DocStatus.CANCELLED) else it }
            throw e
        } catch (e: UserError) {
            fail(local, e.message ?: "This file cannot be opened.", false)
        } catch (e: Exception) {
            log("Reading a file failed", e)
            fail(local, "This file cannot be opened.", false)
        }
    }

    /** Settings a new file starts with: the usual, adjusted to what the printers offer. */
    private fun startingSettings(): PrintSettings {
        val o = _state.value.offered
        var s = PrintSettings()
        if (!o.bw && o.color) s = s.copy(color = true)
        if ("A4" !in o.paperSizes && o.paperSizes.isNotEmpty()) s = s.copy(paperSize = o.paperSizes[0])
        return s
    }

    // ================================================================== uploading

    /** One order for all the files: made when the first file is sent. */
    private suspend fun ensureOrder(): OrderRef = orderLock.withLock {
        _state.value.draft ?: run {
            val c = api.createOrder()
            val ref = OrderRef(c.orderId, c.accessKey, c.pickupCode)
            _state.update { it.copy(draft = ref) }
            saveDraft()
            ref
        }
    }

    /** Upload, then the server's check. Any step can fail on a bad connection: "Try again" picks up from where it stopped. */
    private suspend fun send(local: Long) {
        try {
            uploads.withPermit {
                val o = ensureOrder()
                var d = doc(local) ?: return
                if (local in removed) return
                val file = d.file ?: throw UserError("This file is no longer on the phone. Remove it and add it again.")
                val ticket = if (d.id == null) {
                    api.addDocument(o.orderId, o.key, AddDocumentRequest(d.name, d.type!!, file.length())).also { t ->
                        set(local) { it.copy(id = t.document.id) }
                        saveDraft()
                    }
                } else {
                    api.uploadUrl(o.orderId, o.key, d.id!!)
                }
                if (local in removed) { dropOnServer(ticket.document.id); return }
                set(local) { it.copy(status = DocStatus.UPLOADING, progress = 0f) }
                api.upload(ticket.uploadUrl, ticket.uploadContentType, file) { p -> set(local) { it.copy(progress = p) } }
                set(local) { it.copy(status = DocStatus.CHECKING, progress = 1f) }
                d = doc(local) ?: return
                takeServerView(local, api.documentUploaded(o.orderId, o.key, d.id!!))
            }
        } catch (e: CancellationException) {
            if (local !in removed) set(local) { if (it.busy) it.copy(status = DocStatus.CANCELLED, error = null) else it }
            throw e
        } catch (e: ApiException) {
            if (local in removed) return
            fail(local, e.message, e.code != "PRICED" && e.code != "BAD_STATE" && e.status != 404)
        } catch (e: UserError) {
            fail(local, e.message ?: "The upload failed.", false)
        } catch (e: Exception) {
            if (local in removed) return
            log("Sending a file failed", e)
            fail(local, if (e is IOException) "The upload stopped. Check your internet and try again." else "The upload failed.", true)
        } finally {
            jobs.remove(local)
        }
    }

    private fun takeServerView(local: Long, v: DocumentView) {
        if (v.status == "REJECTED") {
            fail(local, v.problem ?: "This file cannot be printed.", false)
            return
        }
        set(local) {
            it.copy(status = DocStatus.READY, error = null, pageCount = v.pageCount ?: it.pageCount,
                image = v.image ?: it.image, type = v.fileType.ifEmpty { it.type })
        }
        saveDraft()
    }

    private fun fail(local: Long, message: String, canRetry: Boolean) {
        set(local) { it.copy(status = DocStatus.ERROR, error = message, canRetry = canRetry) }
    }

    fun cancelUpload(local: Long) {
        jobs.remove(local)?.cancel()
        set(local) { if (it.busy) it.copy(status = DocStatus.CANCELLED, error = null) else it }
    }

    fun cancelAll() = _state.value.docs.filter { it.busy }.forEach { cancelUpload(it.local) }

    fun retry(local: Long) {
        val d = doc(local) ?: return
        set(local) { it.copy(error = null, serverError = null, canRetry = false) }
        if (d.type == null || d.pageCount == null || d.settings == null) {
            set(local) { it.copy(status = DocStatus.READING) }
            jobs[local] = scope.launch { prepare(local) }
        } else {
            set(local) { it.copy(status = DocStatus.QUEUED, progress = 0f) }
            jobs[local] = scope.launch { send(local) }
        }
    }

    fun removeDoc(local: Long) {
        val d = doc(local) ?: return
        removed += local
        jobs.remove(local)?.cancel()
        incoming.remove(local)
        d.id?.let { dropOnServer(it) }
        _state.update { s ->
            val i = s.docs.indexOfFirst { it.local == local }
            val docs = s.docs.filter { it.local != local }
            s.copy(docs = docs, selected = if (s.selected == local) docs.getOrNull(minOf(i, docs.size - 1))?.local else s.selected)
        }
        d.file?.delete()
        saveDraft()
    }

    private fun dropOnServer(docId: String) {
        val o = _state.value.draft ?: return
        scope.launch { runCatching { api.removeDocument(o.orderId, o.key, docId) } }   // else the server expires it
    }

    /** Moves a file earlier (-1) or later (+1): the order of the list is the order the files print in. */
    fun move(local: Long, delta: Int) {
        _state.update { s ->
            val i = s.docs.indexOfFirst { it.local == local }
            val j = i + delta
            if (i < 0 || j < 0 || j >= s.docs.size) return@update s
            val docs = s.docs.toMutableList()
            docs.add(j, docs.removeAt(i))
            s.copy(docs = docs)
        }
        saveDraft()
    }

    // ================================================================== choices for one file

    fun select(local: Long?) = _state.update { it.copy(selected = local) }

    /** Changes one file's choices. */
    fun change(local: Long, transform: (PrintSettings) -> PrintSettings) {
        set(local) { d -> d.settings?.let { d.copy(settings = transform(it), serverError = null) } ?: d }
        saveDraftSoon()
    }

    /** Applies one choice. When the printers cannot do it with the other choices, applies the fix and says so. */
    fun pick(local: Long, choice: Choice?, apply: (PrintSettings) -> PrintSettings) {
        if (choice != null && !choice.available) {
            val fix = choice.fix
            if (fix == null) { notice("That is not possible with the printers here.", NoticeKind.WARN); return }
            change(local) { fix.settings }
            notice("Also changed to " + fix.changes.joinToString(" and ") + ", so it can be printed.", NoticeKind.WARN)
        } else {
            change(local, apply)
        }
    }

    /** The printers changed under these settings: the nearest thing they can do, or null. */
    fun quickFixFor(d: Doc): Fix? = norm(d)?.let { quickFix(it.settings, _state.value.printers) }

    fun applyQuickFix(local: Long) {
        val d = doc(local) ?: return
        val fix = quickFixFor(d) ?: return
        change(local) { fix.settings }
        notice("Changed to " + fix.changes.joinToString(" and ") + ". It can be printed now.")
    }

    /** Chosen pages from the page pictures ("" = none yet, null = all). */
    fun setPages(local: Long, pages: Set<Int>) {
        val d = doc(local) ?: return
        change(local) { it.copy(pages = specFromPages(pages, d.pageCount ?: 0)) }
    }

    /** Typed pages, e.g. "3, 7, 10-12" (blank = all). */
    fun setPagesText(local: Long, text: String) = change(local) { it.copy(pages = text.trim().ifEmpty { null }) }

    /** Copies this file's choices (not its pages or turn) to every other file. */
    fun applyToAll(from: Long) {
        val d = doc(from) ?: return
        val s = norm(d)?.settings ?: return
        var n = 0
        _state.update { st ->
            st.copy(docs = st.docs.map { o ->
                val os = o.settings
                if (o.local == from || os == null) return@map o
                n++
                var t = os.copy(copies = s.copies, color = s.color, duplex = s.duplex, paperSize = s.paperSize,
                    orientation = s.orientation, marginMm = s.marginMm, collate = s.collate, staple = s.staple,
                    punch = s.punch, bind = s.bind, mediaType = s.mediaType, quality = s.quality)
                if (!o.picture) t = t.copy(pagesPerSheet = s.pagesPerSheet)
                if (s.scaling != "FILL" || o.picture) t = t.copy(scaling = s.scaling, scalePercent = s.scalePercent)
                o.copy(settings = t, serverError = null)
            })
        }
        notice("Settings copied to " + plural(n, "other file", "other files") + ".")
        saveDraftSoon()
    }

    // ================================================================== review: the server checks and prices everything

    fun review() {
        val st = _state.value
        val o = st.draft ?: return
        if (!checkout(st).canReview || st.reviewing) return
        _state.update { it.copy(reviewing = true) }
        scope.launch {
            try {
                val body = ReviewRequest(_state.value.docs.map { DocumentChoice(it.id!!, norm(it)!!.settings) })
                val v = api.review(o.orderId, o.key, body)
                takeServerSettings(v)
                val saved = SavedOrder(o.orderId, o.key, o.code, orderName(v), clock())
                memory.add(saved)
                if (v.status != "AWAITING_PAYMENT") {                  // nothing to pay (free shop)
                    clearDraft(deleteFiles = true)
                    openStatus(saved, v, afterPayment = true)
                } else {
                    _state.update { it.copy(step = Step.REVIEW, order = v, current = saved, payError = null, paymentStarted = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                when (e.code) {
                    "CHECK_SETTINGS" -> {
                        var first: Long? = null
                        for (p in e.documents) {
                            val d = _state.value.docs.find { it.id == p.id } ?: continue
                            set(d.local) { it.copy(serverError = p.message) }
                            if (first == null) first = d.local
                        }
                        first?.let { _state.update { s -> s.copy(selected = it) }; _problems.tryEmit(it) }
                        notice(e.message, NoticeKind.BAD)
                        loadShop()                     // the printers may have changed since the order was started
                    }
                    "PRICED" -> {
                        runCatching { api.edit(o.orderId, o.key) }
                        notice("Please press Review order once more.", NoticeKind.WARN)
                    }
                    else -> notice(e.message, NoticeKind.BAD)
                }
            } catch (e: Exception) {
                notice(friendly(e), NoticeKind.BAD)
            } finally {
                _state.update { it.copy(reviewing = false) }
            }
        }
    }

    /** The server's tidy settings are the truth from now on. */
    private fun takeServerSettings(v: OrderView) {
        _state.update { s ->
            s.copy(docs = s.docs.map { d ->
                val sd = v.documents.find { it.id == d.id }
                if (sd?.settings != null) d.copy(settings = sd.settings, serverError = null) else d
            })
        }
        saveDraft()
    }

    /** Back from "Review and pay" to change something. */
    fun edit() {
        val o = _state.value.draft ?: _state.value.current?.let { OrderRef(it.orderId, it.key, it.code) } ?: return
        scope.launch {
            try {
                api.edit(o.orderId, o.key)
                if (_state.value.docs.isEmpty()) restoreDraft()
                _state.update { it.copy(step = Step.SETUP, draft = o, payError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(friendly(e), NoticeKind.BAD)
            }
        }
    }

    // ================================================================== payment

    fun pay() {
        val ref = _state.value.current ?: return
        if (_state.value.paying) return
        _state.update { it.copy(paying = true, payError = null) }
        scope.launch {
            try {
                val c = api.startPayment(ref.orderId, ref.key)
                _state.update { it.copy(paymentStarted = true) }       // the order is fixed now
                if (c.provider == "demo") {
                    val v = api.demoPay(ref.orderId, ref.key)
                    clearDraft(deleteFiles = true)
                    openStatus(ref, v, afterPayment = true)
                } else if (c.provider == "upi" && c.upi != null) {
                    openUpi(ref, c.upi)
                } else {
                    memory.setPending(ref.orderId)
                    _checkout.tryEmit(c)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(payError = friendly(e)) }
            } finally {
                _state.update { it.copy(paying = false) }
            }
        }
    }

    /** Razorpay said "paid". The server checks it before anything prints. */
    fun onPaymentSuccess(paymentId: String?, signature: String?) {
        val ref = _state.value.current ?: memory.pending() ?: return
        memory.setPending(null)
        clearDraft(deleteFiles = true)
        scope.launch {
            try {
                if (paymentId.isNullOrBlank() || signature.isNullOrBlank()) {
                    throw UserError("Payment details were incomplete. It is checked again automatically.")
                }
                openStatus(ref, api.confirmPayment(ref.orderId, ref.key, ConfirmPayment(paymentId, signature)), afterPayment = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Money may have been taken: never show "Pay" again. The server re-checks by itself.
                openStatus(ref, null, afterPayment = true)
                notice(friendly(e), NoticeKind.BAD)
            }
        }
    }

    // ================================================================== CampusPay: pay the Xerox center with any UPI app

    /**
     * The UPI payment screen: the amount (the price plus a few paise that mark
     * this payment), the UPI ID, and the UPI apps on this phone. The order is
     * paid when the bank's message or the counter confirms the money arrived;
     * this screen watches for that and moves on by itself.
     */
    private fun openUpi(ref: SavedOrder, c: UpiCheckout) {
        memory.setPending(ref.orderId)
        _state.update {
            it.copy(step = Step.PAY, current = ref, upi = c, upiRef = "", upiError = null, claiming = false,
                upiConfirming = false, upiHelpOffered = false, upiHelp = !c.autoConfirm)
        }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _state.value.step == Step.PAY && _state.value.current?.orderId == ref.orderId) {
                delay(if (_state.value.upiConfirming) 2000 else 4000)
                checkUpi(ref)
            }
        }
        offerHelpLater(180_000)
    }

    private var helpJob: Job? = null

    /** If the bank's message has not come after a while, offer to look the payment up by its reference number. */
    private fun offerHelpLater(ms: Long) {
        helpJob?.cancel()
        helpJob = scope.launch {
            delay(ms)
            _state.update { if (it.step == Step.PAY) it.copy(upiHelpOffered = true) else it }
        }
    }

    fun showUpiHelp() = _state.update { it.copy(upiHelp = true) }

    /** Is it paid yet? (Also at once when the student comes back from the UPI app.) */
    fun checkUpi(ref: SavedOrder? = _state.value.current) {
        if (ref == null || _state.value.step != Step.PAY) return
        scope.launch {
            try {
                val v = api.order(ref.orderId, ref.key)
                if (_state.value.step != Step.PAY) return@launch
                if (v.paidAt != null || v.status != "AWAITING_PAYMENT" || v.payment?.claimedAt != null) {
                    if (v.paidAt != null) notice("Payment received. Thank you!")
                    memory.setPending(null)
                    clearDraft(deleteFiles = true)
                    openStatus(ref, v, afterPayment = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // offline for a moment: the next check tries again
            }
        }
    }

    fun setUpiRef(text: String) = _state.update { it.copy(upiRef = text.take(16), upiError = null) }

    /**
     * The UPI app closed and said how it went. FAILURE: no money went, try
     * again. Otherwise the bank's message confirms the payment by itself: this
     * screen shows "Confirming your payment" until it does. (Where the shop has
     * no automatic confirmation, SUCCESS tells the counter at once instead.)
     */
    fun onUpiAnswer(response: String?) {
        val st = _state.value
        if (st.step != Step.PAY) return
        val a = UpiAnswer.parse(response)
        if (a.reference != null) _state.update { it.copy(upiRef = UpiRef.group(a.reference)) }
        when (a.status) {
            UpiAnswer.Status.FAILURE -> _state.update {
                it.copy(upiConfirming = false,
                    upiError = "The UPI app says the payment did not go through, so no money was taken. Try again, or choose another app.")
            }
            UpiAnswer.Status.SUCCESS, UpiAnswer.Status.SUBMITTED -> {
                _state.update { it.copy(upiConfirming = true, upiError = null) }
                if (st.upi?.autoConfirm == false) claimUpi() else { checkUpi(); offerHelpLater(60_000) }
            }
            UpiAnswer.Status.UNKNOWN -> {
                _state.update { it.copy(upiConfirming = true) }
                checkUpi()
                offerHelpLater(90_000)
            }
        }
    }

    /** "I have paid": the server checks it against the bank's messages; staff check it at the counter otherwise. */
    fun claimUpi() {
        val st = _state.value
        val ref = st.current ?: memory.pending() ?: return
        if (st.claiming) return
        if (!UpiRef.ok(st.upiRef)) {
            _state.update { it.copy(upiError = "A UPI reference number has 12 digits. Check it on your UPI app's receipt, or leave it empty.") }
            return
        }
        val typed = UpiRef.clean(st.upiRef).ifEmpty { null }
        _state.update { it.copy(claiming = true, upiError = null) }
        scope.launch {
            try {
                val v = api.claimPayment(ref.orderId, ref.key, ClaimPayment(typed))
                memory.setPending(null)
                clearDraft(deleteFiles = true)
                openStatus(ref, v, afterPayment = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(upiError = friendly(e)) }
            } finally {
                _state.update { it.copy(claiming = false) }
            }
        }
    }

    /** From the pickup-code screen: the UPI details again (send the reference again, or pay). */
    fun resumeUpi(ref: SavedOrder? = _state.value.current) {
        if (ref == null) return
        scope.launch {
            try {
                val c = api.startPayment(ref.orderId, ref.key)
                if (c.provider == "upi" && c.upi != null) openUpi(ref, c.upi)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(friendly(e), NoticeKind.BAD)
            }
        }
    }

    /** "Back to the summary" from the UPI screen: the price is fixed now, so no editing. */
    fun backFromPay() {
        pollJob?.cancel()
        helpJob?.cancel()
        _state.update { it.copy(step = Step.REVIEW, paymentStarted = true, upiConfirming = false) }
    }

    fun onPaymentError(message: String) {
        memory.setPending(null)
        _state.update { it.copy(payError = message, paying = false) }
    }

    fun cancelOrder() {
        val ref = _state.value.current ?: _state.value.draft?.let { SavedOrder(it.orderId, it.key, it.code, "", 0) } ?: return
        scope.launch {
            runCatching { api.cancel(ref.orderId, ref.key) }
            memory.remove(ref.orderId)
            clearDraft(deleteFiles = true)
            resetToStart()
            notice("The order was cancelled.")
        }
    }

    // ================================================================== status and pickup code

    fun openStatus(ref: SavedOrder, first: OrderView? = null, afterPayment: Boolean = false) {
        pollJob?.cancel()
        _state.update { it.copy(step = Step.STATUS, current = ref, order = first ?: it.order?.takeIf { o -> o.orderId == ref.orderId }) }
        pollJob = scope.launch {
            var firstTick = true
            while (isActive && _state.value.step == Step.STATUS && _state.value.current?.orderId == ref.orderId) {
                try {
                    val v = api.order(ref.orderId, ref.key)
                    val upiWaiting = v.upiOpen && (v.payment?.claimedAt != null || v.payment?.note != null)
                    if (firstTick && !afterPayment && v.status == "AWAITING_PAYMENT" && !upiWaiting) {
                        if (v.upiOpen) {                       // the UPI payment screen was open: show it again
                            _state.update { it.copy(order = v) }
                            resumeUpi(ref)
                            return@launch
                        }
                        // opened from the list without having paid: show "Review and pay"
                        _state.update { it.copy(step = Step.REVIEW, order = v, current = ref, paymentStarted = !v.editable) }
                        return@launch
                    }
                    if (firstTick && v.status == "AWAITING_UPLOAD" && _state.value.draft?.orderId == ref.orderId) {
                        _state.update { it.copy(step = Step.SETUP) }
                        return@launch
                    }
                    firstTick = false
                    _state.update { it.copy(order = v) }
                    if (v.status in FINAL && (v.status != "COMPLETED" || v.collected)) return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.status == 404) {
                        memory.remove(ref.orderId)
                        notice("This order no longer exists.", NoticeKind.BAD)
                        return@launch
                    }
                } catch (e: Exception) {
                    // offline for a moment: try again
                }
                delay(3000)
            }
        }
    }

    fun refreshRecent() {
        scope.launch {
            val list = memory.all().take(10)
            _state.update { s -> s.copy(recent = list.map { it to s.recent.find { r -> r.first.orderId == it.orderId }?.second }) }
            val withStatus = list.map { o ->
                async {
                    try {
                        o to api.order(o.orderId, o.key)
                    } catch (e: ApiException) {
                        if (e.status == 404) memory.remove(o.orderId)
                        o to null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        o to null
                    }
                }
            }.awaitAll()
            val keep = memory.all().map { it.orderId }.toSet()
            _state.update { s -> s.copy(recent = withStatus.filter { it.first.orderId in keep }) }
        }
    }

    // ================================================================== navigation

    /** Back to the start. The unfinished order (if any) is kept: "Continue your order" opens it again. */
    fun toHome() {
        pollJob?.cancel()
        _state.update { it.copy(step = Step.HOME, order = if (it.step == Step.REVIEW) it.order else null) }
        refreshRecent()
    }

    /** "Continue your order": back to where the student left it (setting up, or "Review and pay"). */
    fun continueOrder() {
        val s = _state.value
        val reviewed = s.order?.status == "AWAITING_PAYMENT" && s.current != null && s.order.orderId == s.draft?.orderId
        if (reviewed) _state.update { it.copy(step = Step.REVIEW) }
        else if (s.docs.isNotEmpty() || s.draft != null) _state.update { it.copy(step = Step.SETUP) }
    }

    /** Forget the order on screen and start empty (after paying, or "Print more files"). */
    fun resetToStart() {
        pollJob?.cancel()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _state.update {
            it.copy(step = Step.HOME, docs = emptyList(), selected = null, draft = null, order = null, current = null,
                paying = false, payError = null, paymentStarted = false, reviewing = false, upi = null, upiRef = "",
                upiError = null, claiming = false)
        }
        refreshRecent()
    }

    // ================================================================== the unfinished order survives the app closing

    fun saveDraft() {
        val s = _state.value
        val d = s.draft
        if (d == null && s.docs.isEmpty()) { drafts.save(null); return }
        drafts.save(Draft(d?.orderId, d?.key, d?.code, s.docs.filter { it.status != DocStatus.ERROR || it.id != null }.map {
            SavedDoc(it.local, it.id, it.name, it.size, it.file?.path, it.type, it.pageCount, it.image, it.localImage, it.settings,
                cancelled = it.status == DocStatus.CANCELLED)
        }))
    }

    private fun saveDraftSoon() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(400); saveDraft() }
    }

    /** The order is paid or cancelled: its files and settings on the phone are no longer needed. */
    private fun clearDraft(deleteFiles: Boolean) {
        saveJob?.cancel()                               // a save still waiting must not bring the order back
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        if (deleteFiles) _state.value.docs.forEach { it.file?.delete() }
        _state.update { it.copy(docs = emptyList(), draft = null, selected = null) }
        drafts.save(null)
    }

    /**
     * Opens the unfinished order again after the app was closed: files and
     * settings as they were; files that were still being sent are sent again.
     * Returns false when there is nothing to open.
     */
    suspend fun restoreDraft(): Boolean {
        val draft = drafts.load() ?: return false
        _state.update { it.copy(restoring = true) }
        try {
            if (_state.value.shop == null) loadShop()
            val ref = if (draft.orderId != null && draft.key != null && draft.code != null)
                OrderRef(draft.orderId, draft.key, draft.code) else null
            val v = if (ref != null) {
                try {
                    api.order(ref.orderId, ref.key)
                } catch (e: ApiException) {
                    if (e.status == 404) { drafts.save(null); return false }
                    throw e
                }
            } else null
            if (v != null && v.status != "AWAITING_UPLOAD" && v.status != "AWAITING_PAYMENT") {
                drafts.save(null)
                if (v.paidAt != null) {
                    val saved = memory.all().find { it.orderId == v.orderId } ?: SavedOrder(v.orderId, ref!!.key, v.pickupCode, orderName(v), clock())
                    memory.add(saved)
                    openStatus(saved, v, afterPayment = true)
                    return true
                }
                return false
            }
            val docs = draft.docs.mapNotNull { sd ->
                val server = v?.documents?.find { it.id == sd.id }
                if (sd.id != null && server == null && v != null) return@mapNotNull null   // removed or expired on the server
                val file = sd.path?.let { File(it) }?.takeIf { it.exists() }
                val ready = server?.status == "READY" || server?.status == "QUEUED"
                val status = when {
                    server?.status == "REJECTED" -> DocStatus.ERROR
                    ready -> DocStatus.READY
                    sd.cancelled && file != null -> DocStatus.CANCELLED
                    file != null && sd.type != null && sd.pageCount != null -> DocStatus.QUEUED
                    else -> DocStatus.ERROR
                }
                Doc(sd.local, sd.name, sd.size, file, server?.fileType?.ifEmpty { null } ?: sd.type, status,
                    id = sd.id, pageCount = server?.pageCount ?: sd.pageCount, image = server?.image ?: sd.image,
                    localImage = sd.localImage, settings = sd.settings ?: PrintSettings(),
                    error = when (status) {
                        DocStatus.ERROR -> server?.problem ?: "This file could not be restored. Remove it and add it again."
                        else -> null
                    })
            }
            nextLocal.set((docs.maxOfOrNull { it.local } ?: 0) + 1)
            val reviewed = v?.status == "AWAITING_PAYMENT"
            val saved = if (ref != null && reviewed) memory.all().find { it.orderId == ref.orderId }
                ?: SavedOrder(ref.orderId, ref.key, ref.code, orderName(v!!), clock()) else null
            _state.update {
                it.copy(docs = docs, draft = ref, selected = docs.firstOrNull()?.local,
                    step = if (reviewed) Step.REVIEW else Step.SETUP, order = if (reviewed) v else null,
                    current = saved ?: it.current, paymentStarted = reviewed && v?.editable == false)
            }
            for (d in docs) {
                if (d.status == DocStatus.QUEUED) jobs[d.local] = scope.launch { send(d.local) }
                else if (d.status == DocStatus.READY && d.file == null && ref != null) fetchBack(ref, d)
            }
            return docs.isNotEmpty() || reviewed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice("Your unfinished order could not be opened: " + friendly(e), NoticeKind.WARN)
            return false
        } finally {
            _state.update { it.copy(restoring = false) }
        }
    }

    /** A restored file whose copy on the phone is gone: fetch the student's own file back (for the preview). */
    private fun fetchBack(ref: OrderRef, d: Doc) {
        val dir = workDir ?: return
        scope.launch {
            runCatching {
                val url = api.fileUrl(ref.orderId, ref.key, d.id!!).url
                val to = File(dir, "restored-${d.local}-${clock()}.bin")
                api.download(url, to)
                set(d.local) { it.copy(file = to) }
                saveDraft()
            }
        }
    }

    // ================================================================== helpers

    fun doc(local: Long): Doc? = _state.value.docs.find { it.local == local }

    private fun set(local: Long, f: (Doc) -> Doc) {
        _state.update { s -> s.copy(docs = s.docs.map { if (it.local == local) f(it) else it }) }
    }

    companion object {
        fun orderName(v: OrderView): String {
            val docs = v.live
            if (docs.isEmpty()) return v.fileName ?: "Order"
            return docs[0].fileName + if (docs.size > 1) " + ${docs.size - 1} more" else ""
        }

        fun friendly(e: Exception): String = when (e) {
            is UserError -> e.message ?: "Please try another file."
            is ApiException -> e.message
            is IOException -> "Cannot reach the print service. Check your internet connection and try again."
            else -> e.message ?: "Something went wrong. Please try again."
        }
    }
}
