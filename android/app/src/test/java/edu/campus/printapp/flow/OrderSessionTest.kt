package edu.campus.printapp.flow

import edu.campus.printapp.core.PrintSettings
import edu.campus.printapp.net.PrintApi
import edu.campus.printapp.support.FakeBackend
import edu.campus.printapp.support.TestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's order flow, step by step, against a pretend print service with
 * the version 4 API: many files, each set up on its own, checked and priced
 * by the server, and the server's answer kept.
 */
class OrderSessionTest {

    private lateinit var backend: FakeBackend
    private lateinit var scope: CoroutineScope
    private lateinit var store: MemoryStore
    private lateinit var session: OrderSession
    private val notices = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        backend = FakeBackend()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = MemoryStore()
        session = newSession()
    }

    private fun newSession() = OrderSession(PrintApi(backend.base), scope, TestFiles.reader, store, store, workDir = TestFiles.dir).also { s ->
        scope.launch { s.notices.collect { notices += it.text } }
    }

    @After
    fun stop() {
        scope.cancel()
        backend.close()
    }

    private fun waitFor(what: String, ms: Long = 15_000, test: (SessionState) -> Boolean): SessionState = runBlocking {
        try {
            withTimeout(ms) { session.state.first(test) }
        } catch (e: Exception) {
            throw AssertionError("Waited for: $what. State: " + session.state.value.docs.map { "${it.name}=${it.status} ${it.error ?: ""}" } +
                " step=" + session.state.value.step + " notices=" + notices)
        }
    }

    /** For what is not the session's state (notices, requests the server got): look until it is true. */
    private fun eventually(what: String, ms: Long = 15_000, test: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (test()) return
            Thread.sleep(50)
        }
        throw AssertionError("Waited for: $what. Notices: $notices; server got: ${backend.requests}")
    }

    private fun waitNotice(what: String, test: (String) -> Boolean) = eventually("notice: $what") { notices.toList().any(test) }

    private fun allReady(n: Int) = waitFor("$n files ready") { s -> s.docs.size == n && s.docs.all { it.status == DocStatus.READY } }

    private fun addAndWait(vararg files: java.io.File): SessionState {
        runBlocking { session.loadShop() }
        session.addFiles(files.map { TestFiles.incoming(it) })
        return allReady(files.size)
    }

    @Test
    fun aMultiFileOrderIsCheckedPricedPaidAndWatched() {
        val notes = TestFiles.pdf("Notes.pdf", 10)
        val assignment = TestFiles.pdf("Assignment.pdf", 8, landscape = setOf(7))
        val photo = TestFiles.png("Photo.png")
        var st = addAndWait(notes, assignment, photo)
        assertEquals(Step.SETUP, st.step)
        assertEquals(listOf(10, 8, 1), st.docs.map { it.pageCount })
        assertEquals(1, backend.orders.size)                      // one order for all the files
        assertNotNull(st.docs[2].image)                           // the server's measurements of the picture

        // the example order: Notes all pages B/W two-sided, Assignment pages 3 and 7 × 2 colour, Photo colour A4
        val (n, a, p) = st.docs
        session.change(n.local) { it.copy(duplex = "LONG_EDGE") }
        session.setPagesText(a.local, "7, 3")
        session.change(a.local) { it.copy(copies = 2, color = true) }
        session.change(p.local) { it.copy(color = true) }
        st = session.state.value
        val c = session.checkout(st)
        assertNull(c.why)
        assertEquals(2000L + 4000L + 1000L, c.total)             // ₹20 + ₹40 + ₹10, as the website shows
        assertEquals(5 + 4 + 1, c.sheets)

        session.review()
        st = waitFor("review") { it.step == Step.REVIEW }
        val sent = backend.lastReview!!.documents
        assertEquals(st.docs.map { it.id }, sent.map { it.id })   // in the student's order
        assertEquals("3,7", sent[1].settings.pages)                 // tidy, as the server expects
        assertEquals("LONG_EDGE", sent[0].settings.duplex)
        assertEquals(7000, st.order!!.amountPaise)
        assertEquals(listOf(2000, 4000, 1000), st.order!!.live.map { it.amountPaise })
        assertTrue(st.current != null && store.all().any { it.orderId == st.current!!.orderId })

        session.pay()
        st = waitFor("paid and printed", 20_000) { it.step == Step.STATUS && it.order?.status == "COMPLETED" }
        assertEquals("K7M4X", st.order!!.pickupCode)
        assertNull(store.load())                                   // the unfinished order is done with
    }

    @Test
    fun movingFilesChangesTheOrderTheyPrintIn() {
        val st0 = addAndWait(TestFiles.pdf("One.pdf", 1), TestFiles.pdf("Two.pdf", 1), TestFiles.png("Three.png"))
        val three = st0.docs[2]
        session.move(three.local, -1)
        session.move(three.local, -1)
        session.move(three.local, -1)                                 // already first: nothing happens
        assertEquals(listOf("Three.png", "One.pdf", "Two.pdf"), session.state.value.docs.map { it.name })
        assertEquals(listOf("Three.png", "One.pdf", "Two.pdf"), store.load()!!.docs.map { it.name })   // kept on the phone
        session.review()
        val st = waitFor("review") { it.step == Step.REVIEW }
        assertEquals(listOf("Three.png", "One.pdf", "Two.pdf"), backend.lastReview!!.documents.map { d -> st.docs.first { it.id == d.id }.name })
        assertEquals(listOf(1, 2, 3), st.order!!.live.map { it.position })
        assertEquals(listOf("Three.png", "One.pdf", "Two.pdf"), st.order!!.live.map { it.fileName })
    }

    @Test
    fun theServersSettingsAreKeptAfterReview() {
        val st0 = addAndWait(TestFiles.pdf("Chapter.pdf", 12))
        session.setPagesText(st0.docs[0].local, "1-4")
        backend.serverPagesOverride = "1-3"                        // the server's word counts, whatever the app thought
        session.review()
        val st = waitFor("review") { it.step == Step.REVIEW }
        assertEquals("1-3", st.docs[0].settings!!.pages)
        assertEquals("1-3", st.order!!.live[0].settings!!.pages)
        assertEquals("1-3", store.load()!!.docs[0].settings!!.pages)
    }

    @Test
    fun aRefusedFileShowsWhyOpensAndCanBeFixed() {
        val st0 = addAndWait(TestFiles.pdf("A.pdf", 3), TestFiles.pdf("B.pdf", 3))
        val b = st0.docs[1]
        backend.refuse["B.pdf"] = "Two-sided printing is not available. Choose one-sided."
        val opened = scope.async { session.problems.first() }
        session.review()
        val st = waitFor("refused") { s -> s.docs.any { it.serverError != null } && !s.reviewing }
        assertEquals(Step.SETUP, st.step)
        assertEquals("Two-sided printing is not available. Choose one-sided.", st.doc(b.local)!!.serverError)
        assertEquals(b.local, runBlocking { withTimeout(5000) { opened.await() } })
        assertEquals(b.local, st.selected)
        assertEquals("One file needs a change.", session.checkout(st).why)
        assertTrue(notices.any { it.startsWith("B.pdf: Two-sided") })
        // any change clears the server's objection until the next review
        session.change(b.local) { it.copy(copies = 1) }
        assertNull(session.state.value.doc(b.local)!!.serverError)
        backend.refuse.clear()
        session.review()
        waitFor("review") { it.step == Step.REVIEW }
    }

    @Test
    fun printersChangingUnderTheSettingsGetAOneTapFix() {
        val st0 = addAndWait(TestFiles.pdf("Notes.pdf", 4))
        val d = st0.docs[0]
        session.change(d.local) { it.copy(duplex = "LONG_EDGE") }
        backend.printers = backend.printers.map { it.copy(duplex = false) }        // the two-sided printer was switched off
        runBlocking { session.loadShop() }
        val st = session.state.value
        val n = session.norm(st.doc(d.local)!!)!!
        assertEquals("Two-sided printing is not available. Choose one-sided.", n.error)
        assertEquals("One file needs a change.", session.checkout(st).why)
        assertEquals(listOf("one-sided"), session.quickFixFor(st.doc(d.local)!!)!!.changes)
        session.applyQuickFix(d.local)
        assertEquals("ONE_SIDED", session.state.value.doc(d.local)!!.settings!!.duplex)
        assertNull(session.checkout().why)
    }

    @Test
    fun wrongFilesDuplicatesAndTheFileLimit() {
        runBlocking { session.loadShop() }
        val notes = TestFiles.pdf("Notes2.pdf", 2)
        session.addFiles(listOf(TestFiles.incoming(notes), TestFiles.incoming(TestFiles.text("Fake.pdf"))))
        var st = waitFor("checked") { s -> s.docs.size == 2 && s.docs.none { it.busy } }
        assertEquals(DocStatus.ERROR, st.docs[1].status)
        assertEquals("Only PDF, JPG and PNG files can be printed.", st.docs[1].error)
        assertFalse(st.docs[1].canRetry)
        assertEquals("One file needs attention: try again or remove.", session.checkout(st).why)
        assertEquals(st.docs[1].local, session.checkout(st).needs)
        // the same file again is skipped
        session.addFiles(listOf(TestFiles.incoming(notes)))
        waitNotice("duplicate") { it == "1 file was already in your list." }
        session.removeDoc(st.docs[1].local)
        st = session.state.value
        assertEquals(1, st.docs.size)
        assertNull(session.checkout(st).why)
        // the file limit
        backend.maxDocuments = 2
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("C.pdf", 1)), TestFiles.incoming(TestFiles.pdf("D.pdf", 1))))
        waitNotice("file limit") { it.startsWith("1 file was not added: one order can have up to 2 files") }
        allReady(2)
    }

    @Test
    fun uploadsCanBeCancelledTriedAgainAndRemoved() {
        runBlocking { session.loadShop() }
        backend.uploadDelayMs = 4_000                                   // the upload hangs (the answer is late)
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Big.pdf", 5))))
        var st = waitFor("uploading") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.UPLOADING }
        val d = st.docs[0]
        session.cancelUpload(d.local)
        st = waitFor("cancelled") { s -> s.docs[0].status == DocStatus.CANCELLED }
        assertEquals("One file needs attention: try again or remove.", session.checkout(st).why)
        backend.uploadDelayMs = 0
        session.retry(d.local)
        allReady(1)
        assertTrue(backend.requests.any { it.endsWith("/upload-url") })   // the same document, a fresh link
        session.removeDoc(d.local)
        assertTrue(session.state.value.docs.isEmpty())
        eventually("removed on the server") { backend.requests.any { r -> r.startsWith("DELETE ") } }
    }

    @Test
    fun anUnfinishedOrderIsOpenedAgainAfterTheAppWasClosed() {
        val st0 = addAndWait(TestFiles.pdf("Keep.pdf", 6), TestFiles.png("Keep.png"))
        session.change(st0.docs[0].local) { it.copy(pages = "2-4", copies = 3) }
        session.saveDraft()
        // the app closes; a new one opens with the same phone storage
        session = newSession()
        assertTrue(runBlocking { session.restoreDraft() })
        val st = session.state.value
        assertEquals(Step.SETUP, st.step)
        assertEquals(listOf("Keep.pdf", "Keep.png"), st.docs.map { it.name })
        assertTrue(st.docs.all { it.status == DocStatus.READY })
        assertEquals(PrintSettings(pages = "2-4", copies = 3), st.docs[0].settings)
        assertEquals(st0.draft, st.draft)
        session.review()
        waitFor("review after restore") { it.step == Step.REVIEW }
    }

    @Test
    fun changingSomethingAfterReviewAndTheLockedPrice() {
        addAndWait(TestFiles.pdf("E.pdf", 2))
        session.review()
        waitFor("review") { it.step == Step.REVIEW }
        session.edit()
        waitFor("back to setup") { it.step == Step.SETUP }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.png("Late.png"))))
        allReady(2)
        session.review()
        val st = waitFor("review 2") { it.step == Step.REVIEW && it.order!!.live.size == 2 }
        assertEquals(listOf("E.pdf", "Late.png"), st.order!!.live.map { it.fileName })
        // once payment has started the order is fixed: "Change something" is refused with the server's words
        backend.orders.values.first().paymentStarted = true
        session.edit()
        waitNotice("refusal") { it.startsWith("Payment was already started") }
        assertEquals(Step.REVIEW, session.state.value.step)
        // leaving and coming back returns to "Review and pay", not to the file list
        session.toHome()
        session.continueOrder()
        assertEquals(Step.REVIEW, session.state.value.step)
    }
}
