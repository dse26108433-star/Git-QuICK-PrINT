package edu.campus.printapp

import android.content.Context
import edu.campus.printapp.flow.Draft
import edu.campus.printapp.flow.DraftStore
import edu.campus.printapp.flow.OrderMemory
import edu.campus.printapp.flow.PictureStore
import edu.campus.printapp.flow.SavedOrder
import edu.campus.printapp.flow.StaffStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Orders made on this phone, and the unfinished order, in the app's private
 * storage (not backed up: the order keys are like passwords).
 */
class PhoneStores(context: Context) : OrderMemory, DraftStore, StaffStore {

    private val prefs = context.getSharedPreferences("orders", Context.MODE_PRIVATE)

    // The staff app: the sign-in token (the password is never kept).
    override fun token(): String? = prefs.getString("staff.token", null)

    override fun saveToken(token: String?) {
        prefs.edit().apply { if (token == null) remove("staff.token") else putString("staff.token", token) }.apply()
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(SavedOrder.serializer())

    override fun all(): List<SavedOrder> =
        runCatching { json.decodeFromString(listSerializer, prefs.getString("list", "[]") ?: "[]") }.getOrDefault(emptyList())

    override fun add(order: SavedOrder) {
        val list = listOf(order) + all().filter { it.orderId != order.orderId }
        list.drop(15).forEach { saveView(it.orderId, null) }
        saveList(list.take(15))
    }

    override fun remove(orderId: String) {
        saveList(all().filter { it.orderId != orderId })
        saveView(orderId, null)
    }

    override fun saveView(orderId: String, json: String?) {
        prefs.edit().apply { if (json == null) remove("view.$orderId") else putString("view.$orderId", json) }.apply()
    }

    override fun view(orderId: String): String? = prefs.getString("view.$orderId", null)

    override fun setPending(orderId: String?) {
        prefs.edit().putString("pending", orderId).apply()
    }

    override fun pending(): SavedOrder? {
        val id = prefs.getString("pending", null) ?: return null
        return all().find { it.orderId == id }
    }

    override fun load(): Draft? =
        prefs.getString("draft", null)?.let { runCatching { json.decodeFromString(Draft.serializer(), it) }.getOrNull() }

    override fun save(draft: Draft?) {
        prefs.edit().apply {
            if (draft == null) remove("draft") else putString("draft", json.encodeToString(Draft.serializer(), draft))
        }.apply()
    }

    private fun saveList(list: List<SavedOrder>) {
        prefs.edit().putString("list", json.encodeToString(listSerializer, list)).apply()
    }
}

/**
 * The pictures of each paid order's files, in the app's private storage:
 * files/prints/<order>/<file>.jpg. They are what the student shows at the
 * counter; the uploaded files themselves are not kept on the phone.
 */
class PhonePictures(context: Context) : PictureStore {

    private val root = File(context.filesDir, "prints")

    override fun file(orderId: String, docId: String): File {
        val dir = File(root, safe(orderId)).apply { mkdirs() }
        return File(dir, safe(docId) + ".jpg")
    }

    override fun keepOnly(orderIds: Set<String>) {
        val keep = orderIds.map { safe(it) }.toSet()
        root.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
    }

    /** Ids are UUIDs; anything else never becomes part of a path. */
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "x" }
}
