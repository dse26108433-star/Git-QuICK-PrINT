package edu.campus.printapp

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** An order made on this phone. The key is what proves it is ours (no login). */
@Serializable
data class SavedOrder(
    val orderId: String,
    val key: String,
    val code: String,
    val fileName: String,
    val at: Long
)

/** Orders remembered on this phone only (private app storage, not backed up). */
class RecentOrders(context: Context) {

    private val prefs = context.getSharedPreferences("orders", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(SavedOrder.serializer())

    fun all(): List<SavedOrder> =
        runCatching { json.decodeFromString(listSerializer, prefs.getString("list", "[]") ?: "[]") }
            .getOrDefault(emptyList())

    fun add(order: SavedOrder) {
        save((listOf(order) + all().filter { it.orderId != order.orderId }).take(15))
    }

    fun remove(orderId: String) {
        save(all().filter { it.orderId != orderId })
    }

    /** The order whose payment screen is open, in case Android restarts the app meanwhile. */
    fun setPending(orderId: String?) {
        prefs.edit().putString("pending", orderId).apply()
    }

    fun pending(): SavedOrder? {
        val id = prefs.getString("pending", null) ?: return null
        return all().find { it.orderId == id }
    }

    private fun save(list: List<SavedOrder>) {
        prefs.edit().putString("list", json.encodeToString(listSerializer, list)).apply()
    }
}
