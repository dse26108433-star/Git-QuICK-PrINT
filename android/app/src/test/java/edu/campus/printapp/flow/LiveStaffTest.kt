package edu.campus.printapp.flow

import edu.campus.printapp.net.PrintApi
import edu.campus.printapp.support.StationForTests
import edu.campus.printapp.support.TestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The staff app against the REAL print service (the backend's LocalDemo):
 *
 *   gradlew :app:testStudentDebugUnitTest -Dcampusprint.api=http://localhost:8080
 *
 * The Xerox center makes a staff ID (the counter's own call), the app signs
 * in, prints for free, the month's pages go down, the limit holds, and a new
 * password signs the app out. Skipped when no backend address is given.
 */
class LiveStaffTest {

    private val base = System.getProperty("campusprint.api")
    private lateinit var scope: CoroutineScope
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()

    @Before
    fun start() {
        assumeTrue("set -Dcampusprint.api=http://localhost:8080 to run against a real backend", !base.isNullOrBlank())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun stop() {
        if (::scope.isInitialized) scope.cancel()
    }

    /** What the Station does on its Staff screen (the demo backend's counter password). */
    private fun counter(method: String, path: String, body: String?): kotlinx.serialization.json.JsonObject {
        val req = Request.Builder().url(base!!.trimEnd('/') + "/api/v1/counter/" + path)
            .header("X-Counter-Password", "demo-counter-password")
            .method(method, body?.toRequestBody(json) ?: if (method == "GET") null else ByteArray(0).toRequestBody(null)).build()
        return http.newCall(req).execute().use { r ->
            val text = r.body!!.string()
            check(r.isSuccessful) { "$method $path: ${r.code} $text" }
            PrintApi.JSON.parseToJsonElement(text).jsonObject
        }
    }

    private fun waitFor(session: OrderSession, what: String, ms: Long = 60_000, test: (SessionState) -> Boolean): SessionState = runBlocking {
        try {
            withTimeout(ms) { session.state.first(test) }
        } catch (e: Exception) {
            val s = session.state.value
            throw AssertionError("Waited for: $what; step=${s.step} staff=${s.staff} loginError=${s.loginError} payError=${s.payError} " +
                "docs=" + s.docs.map { "${it.name}=${it.status} ${it.error ?: it.serverError ?: ""}" })
        }
    }

    @Test
    fun aStaffMemberSignsInPrintsForFreeAndTheLimitHolds() {
        val made = counter("POST", "staff", "{\"name\":\"App Tester " + System.nanoTime() % 100000 + "\"}")
        val account = made["account"]!!.jsonObject
        val id = account["id"]!!.jsonPrimitive.content
        val username = account["username"]!!.jsonPrimitive.content
        val password = made["password"]!!.jsonPrimitive.content
        counter("PUT", "staff/settings", "{\"monthlyPages\":1000,\"colorAllowed\":false}")
        counter("PUT", "staff/$id", "{\"monthlyPages\":12}")

        val phone = MemoryStore()
        val session = OrderSession(PrintApi(base!!, staffToken = { phone.token() }), scope, TestFiles.reader, phone, phone,
            workDir = TestFiles.dir, staffStore = phone)
        assertEquals(Step.LOGIN, session.state.value.step)
        session.signIn(username, "not-the-password")
        var st = waitFor(session, "the wrong password is refused") { it.loginError != null && !it.signingIn }
        assertTrue(st.loginError!!.contains("Wrong username or password"))
        session.signIn(username.uppercase(), password.uppercase())       // capitals do not matter
        st = waitFor(session, "signed in") { it.step == Step.HOME && it.staff != null }
        assertEquals(12, st.staff!!.monthlyPages)
        assertEquals(12, st.staff!!.leftPages)
        assertTrue(!st.staff!!.colorAllowed)
        runBlocking { session.loadShop() }
        assertTrue("colour is not offered where it is not free", !session.state.value.offered.color)

        // 10 pages, free
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Staff-notes.pdf", 10))))
        waitFor(session, "the file is ready") { s -> s.docs.size == 1 && s.docs.all { it.status == DocStatus.READY } }
        session.review()
        st = waitFor(session, "reviewed") { it.step == Step.REVIEW }
        assertEquals(0, st.order!!.amountPaise)
        assertEquals(10, st.order!!.freePages)
        assertEquals("Ready to print", st.order!!.stage)
        session.pay()
        st = waitFor(session, "sent to print") { it.step == Step.STATUS && it.order?.paidAt != null }
        assertEquals("staff", st.order!!.payment?.provider)
        val orderId = st.order!!.orderId
        st = waitFor(session, "the pages left are fresh") { it.staff?.usedPages == 10 }
        assertEquals(2, st.staff!!.leftPages)

        // the Xerox PC gets it like any other job
        val shop = runBlocking { session.loadShop() }!!
        val jobs = StationForTests(base).claimAll(shop.printing!!.printers.map { it.id })
        assertTrue(jobs.any { it.orderId == orderId && it.fileName == "Staff-notes.pdf" && it.printPages == 10 })

        // "your prints", from the server
        session.toHome()
        st = waitFor(session, "listed") { s -> s.recent.any { it.first.orderId == orderId } }
        assertEquals(10, st.recent.first { it.first.orderId == orderId }.second!!.freePages)

        // 5 more pages do not fit in the 2 that are left: the app says so, and the server refuses whatever the app does
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Too-much.pdf", 5))))
        st = waitFor(session, "the file is ready") { s -> s.docs.size == 1 && s.docs.all { it.status == DocStatus.READY } }
        assertEquals("Only 2 free pages are left this month.", session.checkout(st).why)
        counter("PUT", "staff/$id", "{\"monthlyPages\":20}")              // the Xerox center gives more...
        runBlocking { session.refreshStaff() }
        assertNull(session.checkout(session.state.value).why)
        session.review()
        waitFor(session, "reviewed") { it.step == Step.REVIEW && it.order?.freePages == 5 }
        counter("PUT", "staff/$id", "{\"monthlyPages\":12}")              // ...and takes it back before "Print" is pressed
        session.pay()
        st = waitFor(session, "refused") { it.payError != null && !it.paying }
        assertTrue(st.payError, st.payError!!.contains("only 2 of your 12 free pages"))
        assertNull(st.order!!.paidAt)

        // a new password signs the phone out; the old one is nothing now
        val fresh = counter("POST", "staff/$id/password", null)["password"]!!.jsonPrimitive.content
        runBlocking { session.refreshStaff() }
        st = waitFor(session, "signed out") { it.step == Step.LOGIN }
        assertNull(phone.token())
        assertNotNull(st.loginError)
        session.signIn(username, password)
        waitFor(session, "the old password is refused") { it.loginError?.contains("Wrong username") == true && !it.signingIn }
        session.signIn(username, fresh)
        waitFor(session, "signed in again") { it.step == Step.HOME && it.staff != null }
        counter("DELETE", "staff/$id", null)
    }
}
