package edu.campus.printapp.net

import kotlinx.coroutines.Dispatchers
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
               private val staffToken: () -> String? = { null }) {

    private val base = base.trimEnd('/')
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

    /** The upload finished: the server checks the file and counts its pages. */
    suspend fun documentUploaded(orderId: String, key: String, docId: String): DocumentView =
        post("/api/v1/orders/$orderId/documents/$docId/uploaded", null, DocumentView.serializer(), key)

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
     * Sends a file straight to storage with a link from the server. Cancelling
     * the coroutine stops the upload at once.
     */
    suspend fun upload(url: String, contentType: String, file: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(url).put(ProgressBody(file, contentType.toMediaType(), onProgress)).build()
        call(request) { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "The file could not be sent (${res.code}). Try again.")
        }
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
                                 signIn: Boolean = false): T {
        val requestBody = when {
            body != null -> body.toRequestBody(jsonType)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = signed(Request.Builder().url(base + path).method(method, requestBody), key, signIn)
        val text = call(builder.build()) { res ->
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
    private suspend fun <R> call(request: Request, handle: (Response) -> R): R =
        withContext(Dispatchers.IO) { execute(request).use(handle) }

    /** Runs a call; cancelling the coroutine cancels the call. */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
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

    private class ProgressBody(
        private val file: File,
        private val type: MediaType,
        private val onProgress: (Float) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = type
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1L)
            var done = 0L
            var shown = -1
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
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

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()

        internal fun errorOf(status: Int, text: String): ApiException {
            val e = runCatching { JSON.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            return ApiException(status, e?.message ?: "Something went wrong ($status).", e?.error, e?.documents ?: emptyList())
        }
    }
}
