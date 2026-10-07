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
    val printing: Printing? = null, // null = a server older than version 4
    val wordFiles: Boolean = false  // Word files (.docx) can be added right now: a Xerox PC with Word is online
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
    val printerName: String? = null,
    val printedAt: String? = null,        // when this file finished printing
    val hasPreview: Boolean = false,      // the Xerox PC's picture of its first printed sheet can be fetched
    val sourceType: String? = null,       // "DOCX": a Word file; what prints is the PDF the Xerox PC made from it
    val ahead: Int? = null                // a Word file being turned into pages (CONVERTING): how many are in line before it
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
    val payment: PaymentInfo? = null,
    // Collecting by showing the files at the counter (no pickup code; pickupCode is just the order's number):
    val collectedAt: String? = null,       // handed over at the counter
    val arrivedAt: String? = null,         // this phone said "I'm at the counter" a moment ago
    val estimatedReadyAt: String? = null,  // about when everything will be printed
    val serverTime: String? = null,        // the server's clock (a phone with a wrong clock still shows times right)
    // A college staff member's order: nothing is paid; the printed sides it takes from their free pages this month.
    val freePages: Int? = null,
    // How soon to ask the server about this order again, in seconds (0: it does not say). Soon while
    // something is about to change, seldom while nothing can, less often for everybody when it is busy.
    val pollSeconds: Int = 0
) {
    /** The files that will be (or were) printed. */
    val live: List<DocumentView> get() = documents.filter { it.status != "CANCELLED" }

    /** Handed over at the counter. */
    val isCollected: Boolean get() = collected || collectedAt != null

    /** Paid and not handed over yet: the student can show it at the counter. */
    val canCollect: Boolean get() = paidAt != null && !isCollected && status in setOf("QUEUED", "PRINTING", "COMPLETED", "FAILED")

    /** XeoGo Pay: a UPI payment was started and is not confirmed yet. */
    val upiOpen: Boolean get() = status == "AWAITING_PAYMENT" && payment?.provider == "upi"

    /** A staff order: free, counted against the staff member's pages for the month. */
    val isFree: Boolean get() = freePages != null
}

// ---------------------------------------------------------------- the staff app (college staff print for free)

/** Who is signed in, and their free pages this month. resetsOn: the day they start again ("2026-11-01"). */
@Serializable
data class StaffView(
    val username: String,
    val name: String,
    val monthlyPages: Int = 0,
    val usedPages: Int = 0,
    val leftPages: Int = 0,
    val month: String = "",
    val resetsOn: String = "",
    val colorAllowed: Boolean = false,
    val centerName: String = ""
)

@Serializable
data class StaffLoginRequest(val username: String, val password: String)

/** token: the sign-in the phone keeps and sends from now on (never the password). */
@Serializable
data class StaffLogin(val token: String, val staff: StaffView)

/** token: only when the phone should replace the one it has. */
@Serializable
data class StaffMe(val staff: StaffView, val token: String? = null)

/** One line of "your prints": the same on every device signed in with the staff ID. */
@Serializable
data class StaffOrder(
    val orderId: String,
    val pickupCode: String,
    val status: String,
    val stage: String = "",
    val name: String = "Order",
    val documents: Int = 1,
    val pages: Int? = null,
    val createdAt: String? = null,
    val paidAt: String? = null,
    val completedAt: String? = null,
    val collectedAt: String? = null
)

/**
 * How the order is (being) paid. XeoGo Pay ("upi"): tagPaise are the paise
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

/** What the payment screen needs. provider = "upi" (XeoGo Pay), "razorpay" or "demo". */
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
 * XeoGo Pay: pay this amount to the Xerox center's UPI ID with any UPI app.
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

/** "I'm at the counter" (here = true), or "not any more". */
@Serializable
data class ArriveRequest(val here: Boolean)

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
