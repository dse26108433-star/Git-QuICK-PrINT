package edu.campus.printapp.flow

import edu.campus.printapp.core.PrintSettings
import edu.campus.printapp.core.normalize
import edu.campus.printapp.core.requirements
import edu.campus.printapp.core.whyNot
import edu.campus.printapp.net.ApiException
import edu.campus.printapp.net.DocumentChoice
import edu.campus.printapp.net.PrintApi
import edu.campus.printapp.net.ReviewRequest
import edu.campus.printapp.support.StationForTests
import edu.campus.printapp.support.TestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's order flow against the REAL print service (the backend's
 * LocalDemo, same code as production, PostgreSQL 17 like Supabase):
 *
 *   gradlew :app:testDebugUnitTest -Dcampusprint.api=http://localhost:8080
 *
 * It proves that what the app shows is what the server stores and prints:
 * for every file, the settings the app sends (tidied by its own rules) come
 * back unchanged from the server's SettingsChecker, and the app's price is
 * the server's price to the paisa. Skipped when no backend address is given.
 */
class LiveBackendTest {

    private val base = System.getProperty("campusprint.api")
    private lateinit var scope: CoroutineScope
    private lateinit var session: OrderSession
    private val store = MemoryStore()

    @Before
    fun start() {
        assumeTrue("set -Dcampusprint.api=http://localhost:8080 to run against a real backend", !base.isNullOrBlank())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        session = OrderSession(PrintApi(base!!), scope, TestFiles.reader, store, store, workDir = TestFiles.dir)
    }

    @After
    fun stop() {
        if (::scope.isInitialized) scope.cancel()
    }

    private fun waitFor(what: String, ms: Long = 60_000, test: (SessionState) -> Boolean): SessionState = runBlocking {
        try {
            withTimeout(ms) { session.state.first(test) }
        } catch (e: Exception) {
            throw AssertionError("Waited for: $what; docs " + session.state.value.docs.map { "${it.name}=${it.status} ${it.error ?: it.serverError ?: ""}" })
        }
    }

    @Test
    fun whatTheAppShowsIsWhatTheServerPrints() {
        val shop = runBlocking { session.loadShop() }!!
        assertTrue("the backend must be version 4", shop.printing != null && shop.maxDocuments > 1)
        val notes = TestFiles.pdf("Notes.pdf", 10)
        val assignment = TestFiles.pdf("Assignment.pdf", 8, landscape = setOf(7))
        val photo = TestFiles.png("Photo.png", 1200, 800)
        session.addFiles(listOf(notes, assignment, photo).map { TestFiles.incoming(it) })
        var st = waitFor("3 files ready") { s -> s.docs.size == 3 && s.docs.all { it.status == DocStatus.READY } }
        assertEquals(listOf(10, 8, 1), st.docs.map { it.pageCount })            // counted by the server

        val (n, a, p) = st.docs
        val offered = st.offered
        // Notes: two per sheet, two-sided, stapled when a printer can staple; Assignment: pages 3 and 7, 2 copies, colour;
        // Photo: colour, fill the page, turned a quarter, 10 mm margins
        session.change(n.local) {
            it.copy(pagesPerSheet = 2, duplex = if (offered.duplex) "LONG_EDGE" else "ONE_SIDED",
                staple = if ("STAPLE_TOP_LEFT" in offered.finishing) "TOP_LEFT" else null)
        }
        session.setPagesText(a.local, "7, 3")
        session.change(a.local) { it.copy(copies = 2, color = offered.color, collate = false) }
        session.change(p.local) { it.copy(color = offered.color, scaling = "FILL", rotation = 90, marginMm = 10) }
        st = session.state.value
        val mine = st.docs.map { session.norm(it)!! }
        mine.forEach { assertEquals(null, it.error) }
        val myPrices = st.docs.map { session.price(it)!!.amount.toInt() }
        assertEquals(null, session.checkout(st).why)

        session.review()
        st = waitFor("review") { it.step == Step.REVIEW }
        val server = st.order!!.live
        assertEquals(st.docs.map { it.id }, server.map { it.id })
        server.forEachIndexed { i, sd ->
            assertEquals("${sd.fileName}: the server prints exactly what the app showed", mine[i].settings, sd.settings)
            assertEquals("${sd.fileName}: same price", myPrices[i], sd.amountPaise)
            assertEquals("${sd.fileName}: same sheets", mine[i].plan.sheets, sd.sheets)
            assertEquals("${sd.fileName}: same pages", mine[i].plan.printPages, sd.printPages)
        }
        assertEquals("3,7", server[1].settings!!.pages)
        assertTrue(st.order!!.amountPaise!! >= myPrices.sum())                 // (online payments have a ₹1 minimum)

        // change something, add a file, review again
        session.edit()
        waitFor("editing") { it.step == Step.SETUP }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Late.pdf", 3))))
        waitFor("4 files ready") { s -> s.docs.size == 4 && s.docs.all { it.status == DocStatus.READY } }
        session.review()
        st = waitFor("review 2") { it.step == Step.REVIEW && it.order!!.live.size == 4 }

        // pay (demo mode) and watch
        session.pay()
        st = waitFor("paid") { it.step == Step.STATUS && it.order?.paidAt != null }
        assertTrue(st.order!!.status in setOf("QUEUED", "PRINTING", "COMPLETED"))
        assertEquals(4, st.order!!.live.size)
    }

    /**
     * The example order, from the app to the Xerox PC: Notes.pdf all 10 pages B/W two-sided (long edge),
     * Assignment.pdf pages 3 and 7 × 2 in colour, Photo.jpg (a phone photo turned by its camera) in colour
     * filling A4. The print jobs the PC receives must be exactly what the student reviewed.
     */
    @Test
    fun theExampleOrderPrintsExactlyWhatWasReviewed() {
        val shop = runBlocking { session.loadShop() }!!
        val files = listOf("Notes.pdf", "Assignment.pdf", "Photo.jpg").map { TestFiles.fixture(it) }
        session.addFiles(files.map { TestFiles.incoming(it) })
        var st = waitFor("3 files ready") { s -> s.docs.size == 3 && s.docs.all { it.status == DocStatus.READY } }
        assertEquals(listOf(10, 8, 1), st.docs.map { it.pageCount })              // counted by the server
        assertEquals(6, st.docs[2].image!!.exifOrientation)                        // the server read the camera's turn
        assertEquals(1, st.docs.map { st.draft!!.orderId }.distinct().size)        // one order for all three

        val (n, a, p) = st.docs
        session.change(n.local) { it.copy(duplex = "LONG_EDGE") }
        session.setPagesText(a.local, "7, 3")
        session.change(a.local) { it.copy(copies = 2, color = true) }
        session.change(p.local) { it.copy(color = true, scaling = "FILL") }
        st = session.state.value
        val mine = st.docs.map { session.norm(it)!! }
        mine.forEach { assertEquals(null, it.error) }
        val myPrices = st.docs.map { session.price(it)!!.amount.toInt() }
        assertEquals(listOf(2000, 4000, 1000), myPrices)                           // ₹20 + ₹40 + ₹10, as on the website

        session.review()
        st = waitFor("review") { it.step == Step.REVIEW }
        val reviewed = st.order!!.live
        reviewed.forEachIndexed { i, sd ->
            assertEquals("${sd.fileName}: the server keeps exactly what the app showed", mine[i].settings, sd.settings)
            assertEquals("${sd.fileName}: same price", myPrices[i], sd.amountPaise)
        }
        assertEquals(7000, st.order!!.amountPaise)

        session.pay()
        st = waitFor("paid") { it.step == Step.STATUS && it.order?.paidAt != null }
        val orderId = st.order!!.orderId

        // the Xerox PC takes the jobs: each must be exactly what was reviewed, on a printer that can do it
        val printing = shop.printing!!
        val jobs = StationForTests(base!!).claimAll(printing.printers.map { it.id }).filter { it.orderId == orderId }
        assertEquals(3, jobs.size)
        val colourPrinters = printing.printers.filter { it.color }.map { it.id }.toSet()
        val duplexPrinters = printing.printers.filter { it.duplex }.map { it.id }.toSet()
        for (sd in reviewed) {
            val job = jobs.single { it.jobId == sd.id }
            assertEquals("${sd.fileName}: the print job is what was reviewed", sd.settings, job.settings)
            assertEquals("${sd.fileName}: pages", sd.printPages, job.printPages)
            assertEquals("${sd.fileName}: sheets", sd.sheets, job.sheets)
            assertEquals(sd.fileName, job.fileName)
            if (job.settings.color) assertTrue("${sd.fileName} goes to a colour printer", job.printerId in colourPrinters)
            if (job.settings.twoSided) assertTrue("${sd.fileName} goes to a two-sided printer", job.printerId in duplexPrinters)
        }
        val assignmentJob = jobs.single { it.fileName == "Assignment.pdf" }
        assertEquals("3,7", assignmentJob.settings.pages)
        assertEquals(2, assignmentJob.printPages)
        assertEquals(2, assignmentJob.settings.copies)
        assertEquals("LONG_EDGE", jobs.single { it.fileName == "Notes.pdf" }.settings.duplex)
        assertEquals("FILL", jobs.single { it.fileName == "Photo.jpg" }.settings.scaling)
        assertEquals(listOf(1, 2, 3), jobs.sortedBy { it.documentNumber }.map { it.documentNumber })
    }

    @Test
    fun theServerRefusesWithTheSameWordsTheAppUses() {
        val shop = runBlocking { session.loadShop() }!!
        val printers = shop.printers()
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Refused.pdf", 4))))
        val st = waitFor("ready") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.READY }
        val d = st.docs[0]
        // something no printer here can do (asked straight, as an out-of-date app would)
        val impossible = PrintSettings(color = true, duplex = "LONG_EDGE", paperSize = "A3")
        val mine = normalize(impossible, edu.campus.printapp.core.Facts("PDF", 4) { session.paper(it).label },
            edu.campus.printapp.core.Limits(shop.maxCopies, shop.maxPages), printers)
        assumeTrue("these printers can do colour A3 two-sided", mine.error != null)
        val api = PrintApi(base!!)
        val e = try {
            runBlocking { api.review(st.draft!!.orderId, st.draft!!.key, ReviewRequest(listOf(DocumentChoice(d.id!!, impossible)))) }
            null
        } catch (x: ApiException) {
            x
        }
        assertEquals("CHECK_SETTINGS", e!!.code)
        assertEquals(d.id, e.documents.single().id)
        assertEquals(mine.error, e.documents.single().message)
        assertEquals(whyNot(printers, requirements(mine.settings)) { session.paper(it).label }, e.documents.single().message)
    }
}
