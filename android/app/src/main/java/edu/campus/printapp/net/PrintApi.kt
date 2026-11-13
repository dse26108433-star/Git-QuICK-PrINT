package edu.campus.printapp.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The student API (the same one the website uses). Plain Kotlin + OkHttp, so
 * the whole order flow can be tested on a computer against the real backend.
 *
 * The staff app uses the same calls with its sign-in (staffToken) sent along:
 * orders made that way belong to the staff ID and are free.
 */
class PrintApi(base: String, private val http: OkHttpClient = defaultClient(),
               /** The staff app: the sign-in token to send with every request (null: not signed in, or the student app). */
               private val staffToken: () -> String? = { null },
               /** Between two tries of a piece of an upload (tests make it short). */
               private val pieceRetryMs: Long = 1_500,
               /** The address the print service moved to, as the phone remembers it (null: it has not moved). */
               moved: String? = null) {

    /** The address the app was built with (see AppConfig). It knows where the print service is, if it ever moves. */
    val home: String = base.trimEnd('/')

    /**
     * Where the print service is now. It starts as the address the phone remembers (moved) or the one the app
     * was built with, and changes when the print service says it has moved ("movedTo" in its shop details):
     * for example from the cloud to the Xerox center's own PC. No new version of the app is needed for that.
     */
    @Volatile var base: String = (moved?.let { validBase(it, base.startsWith("http://")) } ?: base).trimEnd('/')
        private set

    /** Goes to the new address. False: it is not an address the app accepts (https only), or it is already there. */
    fun moveTo(url: String?): Boolean {
        val to = validBase(url, home.startsWith("http://")) ?: return false
        if (to == base) return false
        base = to
        return true
    }

    /**
     * The address in use does not answer. Has the print service moved again, or back? The address the app was
     * built with knows. True when the app now uses another address than before.
     */
    suspend fun askHome(): Boolean {
        if (base == home) return false
        return try {
            val text = call(Request.Builder().url("$home/api/v1/shop").get().build()) { res ->
                if (res.isSuccessful) res.body?.string().orEmpty() else null
            } ?: return false
            val shop = JSON.decodeFromString(ShopView.serializer(), text)
            if (shop.movedTo != null) moveTo(shop.movedTo) else { base = home; true }
        } catch (e: IOException) {
            false
        } catch (e: kotlinx.serialization.SerializationException) {
            false
        }
    }
    /** For the one call that may take long: the server's check of a big file. */
    private val patient: OkHttpClient = http.newBuilder().readTimeout(3, TimeUnit.MINUTES).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * The staff app: the server said this sign-in is no longer good (a new
     * password, the ID switched off, or it ran out). Called with the server's words.
     */
    @Volatile var onSignedOut: ((String) -> Unit)? = null

    // ---------------------------------------------------------------- staff: sign in, free pages, their prints

    suspend fun staffLogin(username: String, password: String): StaffLogin =
        post("/api/v1/staff/login", JSON.encodeToString(StaffLoginRequest.serializer(), StaffLoginRequest(username, password)),
            StaffLogin.serializer(), signIn = true)

    suspend fun staffMe(): StaffMe = get("/api/v1/staff/me", StaffMe.serializer())

    suspend fun staffOrders(): List<StaffOrder> =
        get("/api/v1/staff/orders", kotlinx.serialization.builtins.ListSerializer(StaffOrder.serializer()))

    /** "Print": free; the server takes the pages from the month and sends the order to the printers in one step. */
    suspend fun staffPrint(orderId: String): OrderView =
        post("/api/v1/orders/$orderId/staff-print", null, OrderView.serializer())

    // ---------------------------------------------------------------- shop

    suspend fun shop(): ShopView = get("/api/v1/shop", ShopView.serializer())

    // ---------------------------------------------------------------- building an order

    /** A new, empty order: files are added one by one. */
    suspend fun createOrder(): CreateOrderResponse = post("/api/v1/orders", "{}", CreateOrderResponse.serializer())

    suspend fun addDocument(orderId: String, key: String, req: AddDocumentRequest): UploadTicket =
        post("/api/v1/orders/$orderId/documents", JSON.encodeToString(AddDocumentRequest.serializer(), req),
            UploadTicket.serializer(), key)

    /** A fresh upload link for the same file (after a failed or cancelled upload). */
    suspend fun uploadUrl(orderId: String, key: String, docId: String): UploadTicket =
        post("/api/v1/orders/$orderId/documents/$docId/upload-url", null, UploadTicket.serializer(), key)

    /**
     * The upload finished: the server checks the file and counts its pages. A big file on a small server takes
     * a while, so this one call waits longer for its answer than the others.
     */
    suspend fun documentUploaded(orderId: String, key: String, docId: String): DocumentView =
        send("POST", "/api/v1/orders/$orderId/documents/$docId/uploaded", null, DocumentView.serializer(), key, client = patient)

    suspend fun removeDocument(orderId: String, key: String, docId: String): OrderView =
        send("DELETE", "/api/v1/orders/$orderId/documents/$docId", null, OrderView.serializer(), key)

    /** A short-lived link to the student's own file (to show a restored draft again). */
    suspend fun fileUrl(orderId: String, key: String, docId: String): FileUrl =
        get("/api/v1/orders/$orderId/documents/$docId/file-url", FileUrl.serializer(), key)

    /** Check every document's settings against the file and the printers, and fix the price. */
    suspend fun review(orderId: String, key: String, req: ReviewRequest): OrderView =
        post("/api/v1/orders/$orderId/review", JSON.encodeToString(ReviewRequest.serializer(), req),
            OrderView.serializer(), key)

    /** Back from the summary to change something (only before payment starts). */
    suspend fun edit(orderId: String, key: String): OrderView =
        post("/api/v1/orders/$orderId/edit", null, OrderView.serializer(), key)

    // ---------------------------------------------------------------- payment and status

    suspend fun startPayment(orderId: String, key: String): PaymentStart =
        post("/api/v1/orders/$orderId/payment", null, PaymentStart.serializer(), key)

    suspend fun confirmPayment(orderId: String, key: String, body: ConfirmPayment): OrderView =
        post("/api/v1/orders/$orderId/payment/confirm", JSON.encodeToString(ConfirmPayment.serializer(), body),
            OrderView.serializer(), key)

    /** XeoGo Pay: "I have paid". Paid once the bank's message or the counter confirms the money arrived. */
    suspend fun claimPayment(orderId: String, key: String, body: ClaimPayment): OrderView =
        post("/api/v1/orders/$orderId/payment/claim", JSON.encodeToString(ClaimPayment.serializer(), body),
            OrderView.serializer(), key)

    suspend fun demoPay(orderId: String, key: String): OrderView =
        post("/api/v1/orders/$orderId/payment/demo", null, OrderView.serializer(), key)

    suspend fun order(orderId: String, key: String): OrderView = get("/api/v1/orders/$orderId", OrderView.serializer(), key)

    suspend fun cancel(orderId: String, key: String): OrderView =
        post("/api/v1/orders/$orderId/cancel", null, OrderView.serializer(), key)

    /**
     * "I'm at the counter": the paid order shows on the staff's screen with
     * pictures of its files, and they hand the pages over. No pickup code.
     */
    suspend fun arrive(orderId: String, key: String, here: Boolean): OrderView =
        post("/api/v1/orders/$orderId/arrive", JSON.encodeToString(ArriveRequest.serializer(), ArriveRequest(here)),
            OrderView.serializer(), key)

    /** The Xerox PC's picture (JPEG) of a file's first printed sheet, saved to a file on the phone. */
    suspend fun preview(orderId: String, key: String, docId: String, to: File) {
        val request = signed(Request.Builder().url("$base/api/v1/orders/$orderId/documents/$docId/preview"), key).get().build()
        call(request) { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "The picture could not be loaded.")
            val bytes = res.body!!.bytes()
            val jpeg = bytes.size in 100..400_000 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            if (!jpeg) throw ApiException(res.code, "The picture could not be loaded.")
            to.parentFile?.mkdirs()
            to.writeBytes(bytes)
        }
    }

    // ---------------------------------------------------------------- files

    /**
     * Sends a file with a link from the server. Cancelling the coroutine stops it at once.
     *
     * pieceBytes > 0 (the server takes pieces): the file goes in pieces of that size. A connection that drops
     * loses one piece; what arrived stays, and sending carries on from there, now or at the next try. A piece
     * that fails is tried a few times with a short wait before the whole upload is given up (to be tried
     * again later). pieceBytes = 0 (cloud storage): the whole file in one request.
     */
    suspend fun upload(url: String, contentType: String, file: File, pieceBytes: Int = 0, onProgress: (Float) -> Unit) {
        if (pieceBytes <= 0) {
            val request = Request.Builder().url(url).put(ProgressBody(file, contentType.toMediaType(), 0, file.length(), onProgress)).build()
            call(request) { res ->
                if (!res.isSuccessful) throw ApiException(res.code, "The file could not be sent (${res.code}). Try again.")
            }
            return
        }
        val total = file.length()
        var at = received(url, total) ?: 0L
        var hiccups = 0
        onProgress(at.toFloat() / total.coerceAtLeast(1))
        while (at < total) {
            val start = at
            val end = minOf(start + pieceBytes, total)
            try {
                val body = ProgressBody(file, contentType.toMediaType(), start, end - start) { p ->
                    onProgress((start + p * (end - start)) / total.coerceAtLeast(1))
                }
                val request = Request.Builder().url(url).header("Content-Range", "bytes $start-${end - 1}/$total").put(body).build()
                val state = call(request) { res ->
                    val text = res.body?.string().orEmpty()
                    if (!res.isSuccessful) throw errorOf(res.code, text)
                    runCatching { JSON.decodeFromString(UploadState.serializer(), text) }.getOrNull()
                }
                at = when {
                    state == null -> end
                    state.complete -> total
                    else -> state.received.coerceIn(0, total)
                }
                hiccups = 0
            } catch (e: ApiException) {
                when {
                    e.status == 409 && e.received != null -> at = e.received.coerceIn(0, total)     // the server says where to carry on
                    e.temporary || e.status == 400 -> {
                        if (++hiccups > PIECE_TRIES) throw e
                        delay(if (e.status == 503) pieceRetryMs * 2 else pieceRetryMs * hiccups)
                        at = received(url, total) ?: at
                    }
                    else -> throw e                                 // refused (too big, the link ran out): trying again will not help
                }
            } catch (e: IOException) {
                if (++hiccups > PIECE_TRIES) throw e
                delay(pieceRetryMs * hiccups)
                at = received(url, total) ?: at
            }
            onProgress(at.toFloat() / total.coerceAtLeast(1))
        }
    }

    /** How much of the file the server has (null: it could not be asked just now). */
    private suspend fun received(url: String, total: Long): Long? = try {
        call(Request.Builder().url(url).get().build()) { res ->
            if (!res.isSuccessful) null
            else runCatching { JSON.decodeFromString(UploadState.serializer(), res.body?.string().orEmpty()) }.getOrNull()
                ?.let { if (it.complete) total else it.received.coerceIn(0, total) }
        }
    } catch (e: IOException) {
        null
    }

    /** Fetches a file from a short-lived link (a restored draft whose copy on the phone is gone). */
    suspend fun download(url: String, to: File) {
        call(Request.Builder().url(url).get().build()) { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "The file could not be loaded again.")
            res.body!!.byteStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun <T> get(path: String, s: KSerializer<T>, key: String? = null): T = send("GET", path, null, s, key)

    private suspend fun <T> post(path: String, body: String?, s: KSerializer<T>, key: String? = null,
                                 signIn: Boolean = false): T = send("POST", path, body, s, key, signIn)

    /** The order's key (students), and the staff sign-in (the staff app). */
    private fun signed(builder: Request.Builder, key: String?, signIn: Boolean = false): Request.Builder {
        if (!key.isNullOrEmpty()) builder.header("X-Order-Key", key)
        if (!signIn) staffToken()?.takeIf { it.isNotEmpty() }?.let { builder.header("X-Staff-Session", it) }
        return builder
    }

    private suspend fun <T> send(method: String, path: String, body: String?, s: KSerializer<T>, key: String?,
                                 signIn: Boolean = false, client: OkHttpClient = http): T {
        val requestBody = when {
            body != null -> body.toRequestBody(jsonType)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = signed(Request.Builder().url(base + path).method(method, requestBody), key, signIn)
        val text = call(builder.build(), client) { res ->
            val t = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val error = errorOf(res.code, t)
                if (!signIn && (error.code == "STAFF_SIGNED_OUT" || error.code == "STAFF_OFF")) onSignedOut?.invoke(error.message)
                throw error
            }
            t
        }
        return JSON.decodeFromString(s, text)
    }

    /**
     * Runs a call and handles its answer. Reading and closing an answer is
     * network work (closing reads what is left of the body), so all of it
     * happens off the main thread: Android refuses network on the main thread.
     */
    private suspend fun <R> call(request: Request, client: OkHttpClient = http, handle: (Response) -> R): R =
        withContext(Dispatchers.IO) { execute(request, client).use(handle) }

    /** Runs a call; cancelling the coroutine cancels the call. */
    private suspend fun execute(request: Request, client: OkHttpClient): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /** A part of a file (from start, length bytes) as a request body; onProgress: 0..1 of this part. */
    private class ProgressBody(
        private val file: File,
        private val type: MediaType,
        private val start: Long,
        private val length: Long,
        private val onProgress: (Float) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = type
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            val total = length.coerceAtLeast(1L)
            var done = 0L
            var shown = -1
            java.io.RandomAccessFile(file, "r").use { input ->
                input.seek(start)
                val buffer = ByteArray(64 * 1024)
                while (done < length) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - done).toInt())
                    if (n == -1) break
                    sink.write(buffer, 0, n)
                    done += n
                    val pct = (done * 100 / total).toInt()
                    if (pct != shown) { shown = pct; onProgress(done.toFloat() / total) }
                }
            }
        }
    }

    companion object {
        /** Sends every field, nulls included, so the server reads exactly what the app shows. */
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true; coerceInputValues = true }

        /** How often one piece of an upload is tried before the upload is given up for now. */
        const val PIECE_TRIES = 5

        /**
         * An address the app will go to: https only. http: an app that was itself built for an http address
         * (a developer's laptop, a test) may also go to one.
         */
        fun validBase(url: String?, http: Boolean = false): String? {
            val u = url?.trim()?.trimEnd('/') ?: return null
            val ok = Regex("https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?").matches(u) ||
                (http && Regex("http://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?").matches(u))
            return if (ok) u else null
        }

        /**
         * No call waits for ever. A phone that walks out of its signal in the middle of an upload must notice
         * within about a minute (not five), or the upload sits there holding its place while new files wait.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        internal fun errorOf(status: Int, text: String): ApiException {
            val e = runCatching { JSON.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            return ApiException(status, e?.message ?: "Something went wrong ($status).", e?.error, e?.documents ?: emptyList(),
                e?.document, e?.received)
        }
    }
}
