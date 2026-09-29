package edu.campus.printapp.support

import edu.campus.printapp.core.PrintSettings
import edu.campus.printapp.net.PrintApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One print job as the Xerox PC receives it (the backend's ClaimedJob). */
@Serializable
data class ClaimedJob(
    val jobId: String,
    val orderId: String,
    val printerId: String,
    val documentNumber: Int,
    val documentCount: Int,
    val fileName: String,
    val printPages: Int,
    val sides: Int,
    val sheets: Int,
    val settings: PrintSettings
)

/**
 * Takes print jobs the way Campus Print Station does, signed in as the demo
 * backend's PC (LocalDemo), to check what would really be printed.
 */
class StationForTests(base: String) {
    private val base = base.trimEnd('/')
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()

    private fun token(): String {
        val pc = http.newCall(Request.Builder().url("$base/dev-storage/_demo-pc").build()).execute().use {
            PrintApi.JSON.parseToJsonElement(it.body!!.string()).jsonObject
        }
        val auth = Credentials.basic(pc["agentId"]!!.jsonPrimitive.content, pc["agentSecret"]!!.jsonPrimitive.content)
        val req = Request.Builder().url("$base/agent/v1/token").header("Authorization", auth)
            .header("X-Agent-Version", "4.0.0").post(ByteArray(0).toRequestBody(null)).build()
        return http.newCall(req).execute().use {
            check(it.isSuccessful) { "demo PC sign-in: ${it.code}" }
            PrintApi.JSON.parseToJsonElement(it.body!!.string()).jsonObject["token"]!!.jsonPrimitive.content
        }
    }

    /** Every waiting job, printer by printer, until none is left. */
    fun claimAll(printerIds: List<String>): List<ClaimedJob> {
        val t = token()
        val out = mutableListOf<ClaimedJob>()
        for (id in printerIds) {
            while (true) {
                val body = "{\"printerId\":\"$id\"}".toRequestBody(json)
                val req = Request.Builder().url("$base/agent/v1/jobs/claim").header("Authorization", "Bearer $t").post(body).build()
                val job = http.newCall(req).execute().use { r ->
                    check(r.isSuccessful) { "claim: ${r.code} ${r.body?.string()}" }
                    if (r.code == 204) null else PrintApi.JSON.decodeFromString(ClaimedJob.serializer(), r.body!!.string())
                } ?: break
                out += job
            }
        }
        return out
    }
}
