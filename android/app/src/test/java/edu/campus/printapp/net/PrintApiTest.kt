package edu.campus.printapp.net

import edu.campus.printapp.core.PrintSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The messages the app sends and reads are exactly the server's (OrderDtos). */
class PrintApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PrintApi

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        api = PrintApi(server.url("/").toString())
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun reviewSendsEverySettingWithTheOrderKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"orderId":"o1","pickupCode":"K7M4X","status":"AWAITING_PAYMENT","documents":[]}"""))
        api.review("o1", "secret-key", ReviewRequest(listOf(DocumentChoice("d1", PrintSettings(pages = "3,7", copies = 2)))))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/orders/o1/review", r.path)
        assertEquals("secret-key", r.getHeader("X-Order-Key"))
        val s = Json.parseToJsonElement(r.body.readUtf8()).jsonObject["documents"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["settings"]!!.jsonObject }
        // every field is sent, nulls included, with the server's names
        assertEquals(setOf("copies", "color", "pages", "duplex", "paperSize", "orientation", "scaling", "scalePercent", "pagesPerSheet",
            "marginMm", "rotation", "center", "collate", "staple", "punch", "bind", "mediaType", "quality"), s.keys)
        assertEquals("3,7", s["pages"]!!.jsonPrimitive.content)
        assertEquals("null", s["staple"].toString())
    }

    @Test
    fun aRefusedReviewSaysWhichFilesAndWhy() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":"CHECK_SETTINGS","message":"2 files need a change before you can pay.","at":"x",
               "documents":[{"id":"d1","fileName":"A.pdf","message":"Two-sided printing is not available. Choose one-sided."},
                            {"id":"d2","fileName":"B.jpg","message":"Borderless printing is not available. Choose a margin."}]}"""))
        try {
            api.review("o1", "k", ReviewRequest(emptyList()))
            fail("should be refused")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
            assertEquals("CHECK_SETTINGS", e.code)
            assertEquals("2 files need a change before you can pay.", e.message)
            assertEquals(listOf("d1", "d2"), e.documents.map { it.id })
            assertEquals("Borderless printing is not available. Choose a margin.", e.documents[1].message)
        }
    }

    @Test
    fun theServersViewsAreReadEvenWithFieldsTheAppDoesNotKnow() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"orderId":"o1","pickupCode":"K7M4X","status":"PRINTING","stage":"Printing – 1 of 2 files done","message":null,
               "fileName":null,"copies":1,"color":false,"amountPaise":1400,"currency":"INR","collected":false,"somethingNew":42,
               "documents":[{"id":"d1","position":1,"fileName":"Notes.pdf","fileType":"PDF","status":"COMPLETED","stage":"Printed on Printer 1",
                 "pageCount":10,"image":null,"settings":{"copies":1,"color":false,"pages":"3,7","duplex":"ONE_SIDED","paperSize":"A3",
                 "orientation":"AUTO","scaling":"FIT","scalePercent":100,"pagesPerSheet":2,"marginMm":5,"rotation":0,"center":true,
                 "collate":true,"staple":null,"punch":null,"bind":null,"mediaType":null,"quality":"STANDARD"},"pagesText":"3, 7",
                 "printPages":2,"sides":1,"sheets":1,"amountPaise":400,"printerName":"Printer 1 (B/W)"}],
               "editable":false,"documentsDone":1,"totalSheets":2}"""))
        val v = api.order("o1", "k")
        assertEquals("Printing – 1 of 2 files done", v.stage)
        assertEquals("A3", v.documents[0].settings!!.paperSize)
        assertEquals(2, v.documents[0].settings!!.pagesPerSheet)
        assertEquals(1, v.documentsDone)
    }

    @Test
    fun cancellingAnUploadStopsItAtOnce() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS))
        val f = File.createTempFile("upload", ".pdf").apply { writeBytes(ByteArray(300_000)) }
        var progress = 0f
        val job = async { api.upload(server.url("/up").toString(), "application/pdf", f) { progress = it } }
        delay(500)
        val started = System.currentTimeMillis()
        job.cancel()
        try { job.await() } catch (e: Exception) { /* cancelled */ }
        assertTrue("stopped within a second", System.currentTimeMillis() - started < 1000)
        assertEquals(1f, progress)                               // the bytes went out; the answer never came
        assertEquals("PUT", server.takeRequest().method)
    }
}
