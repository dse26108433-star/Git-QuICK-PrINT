package edu.campus.printapp.flow

import edu.campus.printapp.core.ImageInfo
import edu.campus.printapp.core.Normalized
import edu.campus.printapp.core.Paper
import edu.campus.printapp.core.PrintSettings
import kotlinx.serialization.Serializable
import java.io.File

/** An order made on this phone. The key is what proves it is ours (no login). */
@Serializable
data class SavedOrder(
    val orderId: String,
    val key: String,
    val code: String,
    val fileName: String,
    val at: Long
)

/** Orders remembered on this phone. */
interface OrderMemory {
    fun all(): List<SavedOrder>
    fun add(order: SavedOrder)
    fun remove(orderId: String)
    /** The order whose payment screen is open, in case Android restarts the app meanwhile. */
    fun setPending(orderId: String?)
    fun pending(): SavedOrder?

    /** The order as the server last described it (JSON), to show it without internet at the counter. */
    fun saveView(orderId: String, json: String?) {}
    fun view(orderId: String): String? = null
}

/**
 * Where the pictures of an order's files are kept on the phone. After paying,
 * the files themselves are deleted from the phone; a picture of each one's
 * first sheet stays, and that is what the student shows at the counter.
 */
interface PictureStore {
    /** The place for one file's picture (it may not exist yet). */
    fun file(orderId: String, docId: String): File

    /** Forgets the pictures of every other order. */
    fun keepOnly(orderIds: Set<String>)
}

/** Draws the first sheet of a file as it will print into a JPEG. False when it could not. */
fun interface PictureMaker {
    suspend fun draw(d: Doc, n: Normalized, paper: Paper, to: File): Boolean
}

/** The staff app: the sign-in token the server gave this phone (never the password). */
interface StaffStore {
    fun token(): String?
    fun saveToken(token: String?)
}

/** One file of an unfinished order, enough to show it again after the app was closed. */
@Serializable
data class SavedDoc(
    val local: Long,
    val id: String? = null,
    val name: String,
    val size: Long,
    val path: String? = null,
    val type: String? = null,
    val pageCount: Int? = null,
    val image: ImageInfo? = null,
    val localImage: ImageInfo? = null,
    val settings: PrintSettings? = null,
    val cancelled: Boolean = false        // the student stopped its upload: not sent again by itself
)

/** The unfinished order: files and settings. */
@Serializable
data class Draft(val orderId: String? = null, val key: String? = null, val code: String? = null, val docs: List<SavedDoc> = emptyList())

interface DraftStore {
    fun load(): Draft?
    fun save(draft: Draft?)
}

/** For tests and for phones where saving fails: remembers nothing between runs. */
class MemoryStore : DraftStore, OrderMemory, StaffStore {
    @Volatile private var staffToken: String? = null
    override fun token() = staffToken
    override fun saveToken(token: String?) { staffToken = token }
    private var draft: Draft? = null
    private val orders = mutableListOf<SavedOrder>()
    private var pendingId: String? = null
    override fun load() = draft
    override fun save(draft: Draft?) { this.draft = draft }
    override fun all() = orders.toList()
    override fun add(order: SavedOrder) { orders.removeAll { it.orderId == order.orderId }; orders.add(0, order) }
    override fun remove(orderId: String) { orders.removeAll { it.orderId == orderId } }
    override fun setPending(orderId: String?) { pendingId = orderId }
    override fun pending() = orders.find { it.orderId == pendingId }
    private val views = mutableMapOf<String, String>()
    override fun saveView(orderId: String, json: String?) { if (json == null) views.remove(orderId) else views[orderId] = json }
    override fun view(orderId: String) = views[orderId]
}
