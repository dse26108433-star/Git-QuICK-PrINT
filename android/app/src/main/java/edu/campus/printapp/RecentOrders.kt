package edu.campus.printapp

import android.content.Context
import edu.campus.printapp.flow.Draft
import edu.campus.printapp.flow.DraftStore
import edu.campus.printapp.flow.OrderMemory
import edu.campus.printapp.flow.SavedOrder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Orders made on this phone, and the unfinished order, in the app's private
 * storage (not backed up: the order keys are like passwords).
 */
class PhoneStores(context: Context) : OrderMemory, DraftStore {

    private val prefs = context.getSharedPreferences("orders", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(SavedOrder.serializer())

    override fun all(): List<SavedOrder> =
        runCatching { json.decodeFromString(listSerializer, prefs.getString("list", "[]") ?: "[]") }.getOrDefault(emptyList())

    override fun add(order: SavedOrder) = saveList((listOf(order) + all().filter { it.orderId != order.orderId }).take(15))

    override fun remove(orderId: String) = saveList(all().filter { it.orderId != orderId })

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
