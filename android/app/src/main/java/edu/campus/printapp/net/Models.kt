package edu.campus.printapp.net

import edu.campus.printapp.core.Features
import edu.campus.printapp.core.ImageInfo
import edu.campus.printapp.core.Paper
import edu.campus.printapp.core.PrintSettings
import edu.campus.printapp.core.Printer
import edu.campus.printapp.core.PricingRules
import kotlinx.serialization.Serializable

// Same shapes as the backend's OrderDtos. No login anywhere: each order has a
// private accessKey that only this phone knows.

@Serializable
data class ShopView(
    val centerName: String,
    val priceBwPaise: Int,
    val priceColorPaise: Int,
    val currency: String = "INR",
    val maxFileSizeBytes: Long,
    val maxPages: Int,              // most pages printed per copy of one document
    val maxFilePages: Int = 0,      // most pages a PDF may have
    val maxCopies: Int,
    val maxDocuments: Int = 1,      // most files in one order
    val paymentMode: String,
    val bwAvailable: Boolean,
    val colorAvailable: Boolean,
    val bwOnline: Boolean,
    val colorOnline: Boolean,
    val ordersWaiting: Long = 0,
    val printing: Printing? = null  // null = a server older than version 4
) {
    /** The printers students can use, in the shape the rules expect. */
    fun printers(): List<Printer> = (printing?.printers ?: emptyList()).map {
        Printer(it.id, it.name, it.online, it.color, it.bw,
            Features(it.paperSizes ?: listOf("A4"), it.duplex, it.finishing, it.mediaTypes, it.borderless, it.highQuality))
    }
}

/** The printers (switched on), what each can do, and the words and prices for every option. */
@Serializable
data class Printing(
    val printers: List<PrinterOption> = emptyList(),
    val paperSizes: List<Paper> = emptyList(),
    val finishing: Map<String, String> = emptyMap(),
    val mediaTypes: Map<String, String> = emptyMap(),
    val pricing: PricingRules? = null,
    val defaults: PrintSettings? = null
)

@Serializable
data class PrinterOption(
    val id: String,
    val name: String,
    val online: Boolean = false,
    val color: Boolean = false,
    val bw: Boolean = true,
    val paperSizes: List<String>? = null,
    val duplex: Boolean = false,
    val finishing: List<String> = emptyList(),
    val mediaTypes: List<String> = emptyList(),
    val borderless: Boolean = false,
    val highQuality: Boolean = false
)

@Serializable
data class CreateOrderResponse(
    val orderId: String,
    val accessKey: String,
    val pickupCode: String,
    val uploadUrl: String? = null,
    val uploadContentType: String? = null,
    val documentId: String? = null
)

@Serializable
data class AddDocumentRequest(val fileName: String, val fileType: String, val fileSizeBytes: Long)

/** Where to send the file (PUT, with this Content-Type), and the document so far. */
@Serializable
data class UploadTicket(val document: DocumentView, val uploadUrl: String, val uploadContentType: String)

/** One document of an order, as the server sees it. */
@Serializable
data class DocumentView(
    val id: String,
    val position: Int = 0,
    val fileName: String = "",
    val fileType: String = "",
    val sizeBytes: Long? = null,
    val status: String = "",
    val stage: String? = null,
    val problem: String? = null,
    val pageCount: Int? = null,
    val image: ImageInfo? = null,
    val settings: PrintSettings? = null,  // as the server will print it (after review)
    val pagesText: String? = null,        // "3, 7, 10–12" or "All 24 pages"
    val printPages: Int? = null,
    val sides: Int? = null,
    val sheets: Int? = null,
    val amountPaise: Int? = null,
    val printerName: String? = null
)

@Serializable
data class DocumentChoice(val id: String, val settings: PrintSettings)

@Serializable
data class ReviewRequest(val documents: List<DocumentChoice>)

@Serializable
data class OrderView(
    val orderId: String,
    val pickupCode: String,
    val status: String,
    val stage: String = "",
    val message: String? = null,
    val fileName: String? = null,
    val fileType: String? = null,
    val pageCount: Int? = null,
    val pages: String? = null,
    val printPages: Int? = null,
    val copies: Int = 1,
    val color: Boolean = false,
    val amountPaise: Int? = null,
    val currency: String = "INR",
    val printerName: String? = null,
    val ordersAhead: Int? = null,
    val collected: Boolean = false,
    val createdAt: String? = null,
    val paidAt: String? = null,
    val completedAt: String? = null,
    val documents: List<DocumentView> = emptyList(),
    val editable: Boolean = false,
    val documentsDone: Int = 0,
    val totalSheets: Int? = null,
    val refundDuePaise: Int? = null,
    val payment: PaymentInfo? = null
) {
    /** The files that will be (or were) printed. */
    val live: List<DocumentView> get() = documents.filter { it.status != "CANCELLED" }

    /** CampusPay: a UPI payment was started and is not confirmed yet. */
    val upiOpen: Boolean get() = status == "AWAITING_PAYMENT" && payment?.provider == "upi"
}

/**
 * How the order is (being) paid. CampusPay ("upi"): tagPaise are the paise
 * added so the bank's message points to this order; claimRef is the UPI
 * reference the student typed; note says why the counter could not find it.
 */
@Serializable
data class PaymentInfo(
    val provider: String,
    val paid: Boolean = false,
    val tagPaise: Int? = null,
    val reference: String? = null,
    val claimRef: String? = null,
    val claimedAt: String? = null,
    val note: String? = null,
    val verifiedBy: String? = null
)

/** What the payment screen needs. provider = "upi" (CampusPay), "razorpay" or "demo". */
@Serializable
data class PaymentStart(
    val provider: String,
    val keyId: String = "",
    val gatewayOrderId: String = "",
    val amountPaise: Int,
    val currency: String = "INR",
    val description: String = "",
    val upi: UpiCheckout? = null
)

/**
 * CampusPay: pay this amount to the Xerox center's UPI ID with any UPI app.
 * uri is the upi://pay link that opens the UPI app with everything filled in.
 */
@Serializable
data class UpiCheckout(
    val uri: String,
    val payeeVpa: String,
    val payeeName: String,
    val note: String = "",
    val reference: String = "",
    val amountPaise: Int,
    val amountText: String,
    val tagPaise: Int = 0,
    val merchant: Boolean = false,
    val autoConfirm: Boolean = false,
    val startedAt: String? = null
)

/** "I have paid", with the 12-digit UPI reference number (UTR) if known. */
@Serializable
data class ClaimPayment(val reference: String?)

@Serializable
data class ConfirmPayment(val paymentId: String, val signature: String)

@Serializable
data class FileUrl(val url: String)

/** A file the server refused while reviewing, and why. */
@Serializable
data class DocumentProblem(val id: String, val fileName: String? = null, val message: String)

@Serializable
internal data class ApiError(
    val error: String? = null,
    val message: String? = null,
    val documents: List<DocumentProblem> = emptyList()
)

/** The server said no. code: e.g. CHECK_SETTINGS, PRICED; documents: which files, for CHECK_SETTINGS. */
class ApiException(
    val status: Int,
    override val message: String,
    val code: String? = null,
    val documents: List<DocumentProblem> = emptyList()
) : Exception(message)
