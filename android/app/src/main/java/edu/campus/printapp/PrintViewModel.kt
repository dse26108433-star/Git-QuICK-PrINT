package edu.campus.printapp

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.campus.printapp.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

enum class Step { CHOOSE, SETUP, PAY, STATUS }

data class UiState(
    val step: Step = Step.CHOOSE,
    val shop: ShopView? = null,
    val shopError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    // the chosen file
    val fileName: String = "",
    val fileType: String = "",
    val filePages: Int = 0,                    // pages in the file
    val pages: Int = 0,                        // pages that will print (per copy)
    val chooseSome: Boolean = false,           // "Choose pages" instead of "All pages"
    val pagesText: String = "",                // what the student typed, e.g. "333-390"
    val pageSpec: String = "",                 // sent to the server; "" = every page
    val pageRanges: List<IntRange> = emptyList(),
    val pageError: String? = null,             // why the page choice cannot be used
    val previews: List<Bitmap> = emptyList(),
    val previewPages: List<Int> = emptyList(), // which page each preview picture shows
    val color: Boolean = false,
    val copies: Int = 1,
    // sending + paying
    val uploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val payView: OrderView? = null,
    // status
    val current: SavedOrder? = null,
    val status: OrderView? = null,
    val recent: List<Pair<SavedOrder, OrderView?>> = emptyList()
) {
    val pricePerPage: Int get() = shop?.let { if (color) it.priceColorPaise else it.priceBwPaise } ?: 0

    val canContinue: Boolean get() = pageError == null && pages > 0

    /** Same sum as the backend; the backend's number is the one that is charged. */
    val estimatePaise: Int get() {
        val total = pages * copies * pricePerPage
        return if (shop?.paymentMode == "razorpay" && total in 1..99) 100 else total
    }
}

private val FINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "EXPIRED")
private const val PREVIEW_PAGES = 8        // enough to check the file; keeps the phone fast

class PrintViewModel(app: Application) : AndroidViewModel(app) {

    private val api = PrintApi(AppConfig.API_BASE)
    private val saved = RecentOrders(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Set when the Razorpay screen should open; the Activity opens it and clears this. */
    private val _checkout = MutableStateFlow<PaymentStart?>(null)
    val checkout: StateFlow<PaymentStart?> = _checkout.asStateFlow()

    private var workFile: File? = null
    private var pickJob: Job? = null
    private var pollJob: Job? = null
    private var previewJob: Job? = null

    init {
        loadShop()
        refreshRecent()
    }

    // ---------------------------------------------------------------- shop

    fun loadShop() {
        viewModelScope.launch {
            try {
                val shop = api.shop()
                _state.update { it.copy(shop = shop, shopError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(shopError = friendly(e)) }
            }
        }
    }

    fun refreshRecent() {
        viewModelScope.launch {
            val list = saved.all().take(10)
            _state.update { s -> s.copy(recent = list.map { it to null }) }
            val withStatus = list.map { o ->
                async {
                    try {
                        o to api.order(o.orderId, o.key)
                    } catch (e: ApiException) {
                        if (e.status == 404) saved.remove(o.orderId)
                        o to null
                    } catch (e: Exception) {
                        o to null
                    }
                }
            }.awaitAll()
            _state.update { s -> s.copy(recent = withStatus.filter { it.first.orderId in saved.all().map { o -> o.orderId } }) }
        }
    }

    // ---------------------------------------------------------------- 1. choose

    fun pickFile(uri: Uri) {
        pickJob?.cancel()
        pollJob?.cancel()
        pickJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val shop = _state.value.shop ?: api.shop().also { s -> _state.update { it.copy(shop = s) } }
                val name = displayName(uri)
                val file = withContext(Dispatchers.IO) { copyToCache(uri, shop.maxFileSizeBytes) }
                val type = withContext(Dispatchers.IO) { FilePreview.detectType(file) }
                    ?: throw UserError("Only PDF, PNG and JPG files can be printed.")
                val (pages, previews) = withContext(Dispatchers.IO) {
                    if (type == "PDF") {
                        FilePreview.renderPdf(file, PREVIEW_PAGES, 900)
                    } else {
                        val bmp = FilePreview.decodeImage(file, 1400)
                            ?: throw UserError("This picture cannot be opened. It may be damaged.")
                        1 to listOf(bmp)
                    }
                }
                val maxFilePages = if (shop.maxFilePages > 0) shop.maxFilePages else shop.maxPages
                if (pages > maxFilePages) {
                    throw UserError("This file has $pages pages. Files can have up to $maxFilePages pages.")
                }
                workFile?.takeIf { it != file }?.delete()
                workFile = file
                previewJob?.cancel()
                _state.update {
                    applyPages(it.copy(
                        step = Step.SETUP, busy = false, error = null,
                        fileName = name, fileType = type, filePages = pages, pages = pages,
                        // too many pages to print them all: the student must choose
                        chooseSome = type == "PDF" && pages > shop.maxPages, pagesText = "", pageSpec = "",
                        pageRanges = listOf(1..pages), pageError = null,
                        previews = previews, previewPages = (1..previews.size).toList(),
                        color = !shop.bwAvailable && shop.colorAvailable, copies = 1
                    ))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = Step.CHOOSE, busy = false, error = friendly(e)) }
            }
        }
    }

    // ---------------------------------------------------------------- 2. options

    fun setColor(color: Boolean) = _state.update { it.copy(color = color) }

    fun changeCopies(delta: Int) = _state.update { s ->
        val max = s.shop?.maxCopies ?: 50
        s.copies.plus(delta).coerceIn(1, max).let { s.copy(copies = it) }
    }

    fun setPagesMode(some: Boolean) {
        _state.update { applyPages(it.copy(chooseSome = some)) }
        redrawPreview()
    }

    fun setPagesText(text: String) {
        _state.update { applyPages(it.copy(pagesText = text.take(200))) }
        redrawPreview()
    }

    /** Works out the pages to print from the student's choice, with a message if it cannot be used. */
    private fun applyPages(s: UiState): UiState {
        val shop = s.shop ?: return s
        val total = s.filePages
        var ranges = listOf(1..total)
        var spec = ""
        var count = total
        var error: String? = null
        if (s.chooseSome) {
            if (s.pagesText.isBlank()) {
                error = "Type the pages you need, for example 102 or 333-390."
            } else {
                try {
                    val r = PageChoice.parse(s.pagesText, total)
                    ranges = r.ranges; spec = r.spec; count = r.count
                } catch (e: UserError) {
                    error = e.message
                }
            }
        }
        if (error == null && count > shop.maxPages) {
            error = (if (s.chooseSome) "You chose $count pages. " else "This file has $count pages. ") +
                "Up to ${shop.maxPages} pages can be printed per order" +
                (if (s.chooseSome) "." else ": choose the pages you need.")
        }
        // A bad choice keeps the last good one for the preview; nothing can be ordered until it is fixed.
        return if (error != null) s.copy(pageError = error)
        else s.copy(pageError = null, pageRanges = ranges, pageSpec = spec, pages = count)
    }

    /** Shows the first pages of the choice, after a short pause in typing. */
    private fun redrawPreview() {
        val s = _state.value
        val file = workFile ?: return
        if (s.fileType != "PDF") return
        val wanted = s.pageRanges.asSequence().flatMap { it.asSequence() }.take(PREVIEW_PAGES).toList()
        if (wanted == s.previewPages) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(400)
            try {
                val pictures = withContext(Dispatchers.IO) { FilePreview.renderPdfPages(file, wanted, 900) }
                _state.update { it.copy(previews = pictures, previewPages = wanted) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the old preview; the page choice itself is still checked by the server.
            }
        }
    }

    // ---------------------------------------------------------------- 3. send + pay

    fun continueToPayment() {
        val s = _state.value
        val file = workFile ?: return
        if (!s.canContinue) return
        viewModelScope.launch {
            _state.update { it.copy(step = Step.PAY, uploading = true, uploadProgress = 0f, payView = null, error = null) }
            var order: SavedOrder? = null
            try {
                val created = api.create(CreateOrderRequest(s.fileName, s.fileType, file.length(), s.color, s.copies,
                    s.pageSpec.ifEmpty { null }))
                val newOrder = SavedOrder(created.orderId, created.accessKey, created.pickupCode, s.fileName,
                    System.currentTimeMillis())
                order = newOrder
                saved.add(newOrder)
                api.upload(created.uploadUrl, created.uploadContentType, file) { p ->
                    _state.update { it.copy(uploadProgress = p) }
                }
                val view = api.uploaded(created.orderId, created.accessKey)
                if (view.status != "AWAITING_PAYMENT") {
                    openStatus(newOrder, view, afterPayment = true)
                } else {
                    _state.update { it.copy(uploading = false, payView = view, current = newOrder) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                order?.let { saved.remove(it.orderId) }
                _state.update { it.copy(step = Step.SETUP, uploading = false, error = friendly(e)) }
            }
        }
    }

    fun pay() {
        val order = _state.value.current ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val start = api.startPayment(order.orderId, order.key)
                if (start.provider == "demo") {
                    openStatus(order, api.demoPay(order.orderId, order.key), afterPayment = true)
                } else {
                    saved.setPending(order.orderId)
                    _checkout.value = start        // MainActivity opens Razorpay
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = friendly(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun checkoutShown() {
        _checkout.value = null
    }

    /** Razorpay said "paid". The backend checks it before anything prints. */
    fun onPaymentSuccess(paymentId: String?, signature: String?) {
        val order = _state.value.current ?: saved.pending() ?: return
        saved.setPending(null)
        viewModelScope.launch {
            try {
                if (paymentId.isNullOrBlank() || signature.isNullOrBlank()) {
                    throw UserError("Payment details were incomplete. It is checked again automatically.")
                }
                openStatus(order, api.confirmPayment(order.orderId, order.key, ConfirmPayment(paymentId, signature)),
                    afterPayment = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Money may have been taken: never show "Pay" again. The backend re-checks by itself.
                openStatus(order, null, afterPayment = true)
                _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    fun onPaymentError(message: String) {
        saved.setPending(null)
        _state.update { it.copy(error = message, busy = false) }
    }

    fun cancelOrder() {
        val order = _state.value.current ?: return
        viewModelScope.launch {
            runCatching { api.cancel(order.orderId, order.key) }
            saved.remove(order.orderId)
            backToStart()
        }
    }

    // ---------------------------------------------------------------- 4. status

    fun openStatus(order: SavedOrder, first: OrderView? = null, afterPayment: Boolean = false) {
        pollJob?.cancel()
        _state.update { it.copy(step = Step.STATUS, current = order, status = first, error = null, uploading = false) }
        pollJob = viewModelScope.launch {
            var firstTick = true
            while (isActive) {
                try {
                    val v = api.order(order.orderId, order.key)
                    if (v.status == "AWAITING_PAYMENT" && firstTick && !afterPayment) {
                        // Opened from the list without having paid: show the pay screen.
                        _state.update { it.copy(step = Step.PAY, uploading = false, payView = v, current = order) }
                        return@launch
                    }
                    firstTick = false
                    _state.update { it.copy(status = v) }
                    if (v.status in FINAL) return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.status == 404) {
                        saved.remove(order.orderId)
                        _state.update { it.copy(error = "This order no longer exists.") }
                        return@launch
                    }
                } catch (e: Exception) {
                    // offline for a moment: try again
                }
                delay(3000)
            }
        }
    }

    // ---------------------------------------------------------------- navigation

    fun backToStart() {
        pollJob?.cancel()
        pickJob?.cancel()
        previewJob?.cancel()
        _state.update {
            it.copy(step = Step.CHOOSE, busy = false, error = null, previews = emptyList(), payView = null,
                status = null, current = null, uploading = false)
        }
        refreshRecent()
        loadShop()
    }

    /** The phone's back button. Returns false when there is nowhere to go back to. */
    fun back(): Boolean = when (_state.value.step) {
        Step.CHOOSE -> false
        Step.SETUP, Step.STATUS -> { backToStart(); true }
        Step.PAY -> {
            if (_state.value.uploading) true       // stay while sending
            else { _state.update { it.copy(step = Step.SETUP, error = null) }; true }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        workFile?.delete()
    }

    // ---------------------------------------------------------------- helpers

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Copies the chosen file into the app's own cache, so preview and upload are reliable. */
    private fun copyToCache(uri: Uri, maxBytes: Long): File {
        val resolver = getApplication<Application>().contentResolver
        val out = File(getApplication<Application>().cacheDir, "upload-${System.currentTimeMillis()}.bin")
        val input = resolver.openInputStream(uri) ?: throw UserError("This file cannot be opened.")
        input.use { src ->
            out.outputStream().use { dst ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buffer)
                    if (n == -1) break
                    total += n
                    if (total > maxBytes) {
                        dst.close()
                        out.delete()
                        throw UserError("This file is too big. The limit is ${maxBytes / 1048576} MB.")
                    }
                    dst.write(buffer, 0, n)
                }
            }
        }
        if (out.length() == 0L) {
            out.delete()
            throw UserError("This file is empty.")
        }
        return out
    }

    private fun friendly(e: Exception): String = when (e) {
        is UserError -> e.message ?: "Please try another file."
        is ApiException -> e.message
        is IOException -> "Cannot reach the print service. Check your internet connection and try again."
        else -> e.message ?: "Something went wrong. Please try again."
    }
}
