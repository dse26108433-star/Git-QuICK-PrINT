package edu.campus.printapp.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- messages
// Same shapes as the backend's OrderDtos. No login anywhere: each order has
// a private accessKey that only this phone knows.

@Serializable
data class ShopView(
    val centerName: String,
    val priceBwPaise: Int,
    val priceColorPaise: Int,
    val currency: String = "INR",
    val maxFileSizeBytes: Long,
    val maxPages: Int,              // most pages printed per copy
    val maxFilePages: Int = 0,      // most pages a PDF may have (0 = older server: same as maxPages)
    val maxCopies: Int,
    val paymentMode: String,
    val bwAvailable: Boolean,
    val colorAvailable: Boolean,
    val bwOnline: Boolean,
    val colorOnline: Boolean,
    val ordersWaiting: Long = 0
)

@Serializable
data class CreateOrderRequest(
    val fileName: String,
    val fileType: String,
    val fileSizeBytes: Long,
    val color: Boolean,
    val copies: Int,
    val pages: String? = null       // e.g. "333-390"; null = every page
)

@Serializable
data class CreateOrderResponse(
    val orderId: String,
    val accessKey: String,
    val pickupCode: String,
    val uploadUrl: String,
    val uploadContentType: String
)

@Serializable
data class OrderView(
    val orderId: String,
    val pickupCode: String,
    val status: String,
    val stage: String,
    val message: String? = null,
    val fileName: String,
    val fileType: String,
    val pageCount: Int? = null,     // pages in the file
    val pages: String? = null,      // chosen pages, e.g. "333-390"; null = all
    val printPages: Int? = null,    // pages printed per copy
    val copies: Int,
    val color: Boolean,
    val amountPaise: Int? = null,
    val currency: String = "INR",
    val printerName: String? = null,
    val ordersAhead: Int? = null,
    val collected: Boolean = false,
    val createdAt: String? = null,
    val paidAt: String? = null,
    val completedAt: String? = null
)

/** What the payment screen needs. provider = "razorpay" or "demo". */
@Serializable
data class PaymentStart(
    val provider: String,
    val keyId: String = "",
    val gatewayOrderId: String = "",
    val amountPaise: Int,
    val currency: String = "INR",
    val description: String = ""
)

@Serializable
data class ConfirmPayment(val paymentId: String, val signature: String)

@Serializable
private data class ApiError(val error: String? = null, val message: String? = null)

class ApiException(val status: Int, override val message: String) : Exception(message)

// ---------------------------------------------------------------- client

class PrintApi(base: String) {

    private val base = base.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun shop(): ShopView =
        json.decodeFromString(ShopView.serializer(), call("GET", "/api/v1/shop"))

    suspend fun create(req: CreateOrderRequest): CreateOrderResponse =
        json.decodeFromString(
            CreateOrderResponse.serializer(),
            call("POST", "/api/v1/orders", json.encodeToString(CreateOrderRequest.serializer(), req))
        )

    suspend fun uploaded(id: String, key: String): OrderView =
        view(call("POST", "/api/v1/orders/$id/uploaded", key = key))

    suspend fun startPayment(id: String, key: String): PaymentStart =
        json.decodeFromString(PaymentStart.serializer(), call("POST", "/api/v1/orders/$id/payment", key = key))

    suspend fun confirmPayment(id: String, key: String, body: ConfirmPayment): OrderView =
        view(call("POST", "/api/v1/orders/$id/payment/confirm",
            json.encodeToString(ConfirmPayment.serializer(), body), key))

    suspend fun demoPay(id: String, key: String): OrderView =
        view(call("POST", "/api/v1/orders/$id/payment/demo", key = key))

    suspend fun order(id: String, key: String): OrderView =
        view(call("GET", "/api/v1/orders/$id", key = key))

    suspend fun cancel(id: String, key: String): OrderView =
        view(call("POST", "/api/v1/orders/$id/cancel", key = key))

    /** Sends the file straight to storage using the one-time link from create(). */
    suspend fun upload(url: String, contentType: String, file: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .put(ProgressBody(file, contentType.toMediaType(), onProgress))
                .build()
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    throw ApiException(res.code, "The file could not be sent (${res.code}). Please try again.")
                }
            }
        }
    }

    private fun view(text: String): OrderView = json.decodeFromString(OrderView.serializer(), text)

    private suspend fun call(method: String, path: String, body: String? = null, key: String? = null): String =
        withContext(Dispatchers.IO) {
            val requestBody = when {
                body != null -> body.toRequestBody(jsonType)
                method == "POST" -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            val builder = Request.Builder().url(base + path).method(method, requestBody)
            if (key != null) builder.header("X-Order-Key", key)
            http.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val message = runCatching { json.decodeFromString(ApiError.serializer(), text).message }.getOrNull()
                    throw ApiException(res.code, message ?: "Something went wrong (${res.code}).")
                }
                text
            }
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
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    sink.write(buffer, 0, n)
                    done += n
                    onProgress(done.toFloat() / total)
                }
            }
        }
    }
}
