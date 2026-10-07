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

    private fun newSession() = OrderSession(PrintApi(backend.base), scope, TestFiles.reader, store, store, workDir = TestFiles.dir,
        wakeRetryMs = 60, wordPollMs = 40, pictures = pictures, painter = { d, _, _, to ->
            // what the phone's own painter does: the first sheet of the file as a picture (here: a few bytes that say whose)
            drawPictures && d.file != null && run { to.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ("phone:" + d.name).toByteArray()); true }
        }).also { s ->
        scope.launch { s.notices.collect { notices += it.text } }
    }

    /** The pictures of paid orders' files, in a folder of this test. */
    private val pictureDir = java.io.File(TestFiles.dir, "prints-" + System.nanoTime())
    private val pictures = object : PictureStore {
        override fun file(orderId: String, docId: String) = java.io.File(pictureDir, "$orderId/$docId.jpg").also { it.parentFile.mkdirs() }
        override fun keepOnly(orderIds: Set<String>) {
            pictureDir.listFiles()?.forEach { if (it.name !in orderIds) it.deleteRecursively() }
        }
    }
    private var drawPictures = true

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

    // ------------------------------------------------------------------ Word files

    @Test
    fun aWordFileBecomesPagesAndIsThenLikeAnyPdf() {
        backend.word = true
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.docx("Report.docx")), TestFiles.incoming(TestFiles.pdf("Notes.pdf", 2))))
        var st = waitFor("with the Xerox center's computer") { s ->
            s.docs.size == 2 && s.docs[0].status == DocStatus.CONVERTING && s.docs[1].status == DocStatus.READY
        }
        val word = st.docs[0]
        assertEquals("DOCX", word.type)
        assertTrue(word.word && !word.picture && word.busy)
        assertNull(word.pageCount)
        assertNull(session.norm(word))                             // no pages, no price yet
        val c = session.checkout(st)
        assertFalse(c.canReview)
        assertEquals("Wait until the Word file is turned into pages.", c.why)
        assertEquals(listOf(word.id), backend.wordWaiting())

        // the Xerox center's computer is done: seven pages
        backend.wordReady(word.id!!, 7)
        st = waitFor("its pages on the phone") { s -> s.docs[0].status == DocStatus.READY && s.docs[0].file != null }
        val pages = st.docs[0]
        assertEquals("PDF", pages.type)
        assertEquals(7, pages.pageCount)
        assertEquals("Report.docx", pages.name)                    // still called what the student called it
        assertEquals("PDF", edu.campus.printapp.flow.FileTypes.detect(pages.file!!))
        assertFalse(word.file!!.exists())                          // the Word file itself is gone from the phone

        // every setting a PDF has
        session.setPagesText(pages.local, "2-5")
        session.change(pages.local) { it.copy(copies = 3, duplex = "LONG_EDGE", pagesPerSheet = 2) }
        st = session.state.value
        val n = session.norm(st.docs[0])!!
        assertNull(n.error)
        assertEquals(4, n.plan.printPages)
        assertEquals(2, n.plan.sides)                              // four pages, two on a side
        assertEquals(1, n.plan.sheets)                             // ...on the two sides of one sheet
        assertTrue(session.checkout(st).canReview)

        session.review()
        st = waitFor("review") { it.step == Step.REVIEW }
        assertEquals("2-5", backend.lastReview!!.documents[0].settings.pages)
        session.pay()
        st = waitFor("paid and printed", 20_000) { it.step == Step.STATUS && it.order?.status == "COMPLETED" }
        assertEquals(listOf("Report.docx", "Notes.pdf"), st.order!!.live.map { it.fileName })
    }

    @Test
    fun withoutAXeroxPcWithWordAWordFileIsNotEvenSent() {
        backend.word = false
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.docx("Report.docx"))))
        var st = waitFor("told why") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.ERROR }
        assertTrue(st.docs[0].error!!.contains("not online right now"))
        assertTrue(st.docs[0].canRetry)
        assertTrue(backend.requests.none { it.startsWith("PUT /upload") })       // nothing was uploaded for nothing

        // the Xerox center opens: "Try again" is enough
        backend.word = true
        session.retry(st.docs[0].local)
        st = waitFor("with the Xerox center's computer") { s -> s.docs[0].status == DocStatus.CONVERTING }
        backend.wordReady(st.docs[0].id!!, 1)
        waitFor("ready") { s -> s.docs[0].status == DocStatus.READY && s.docs[0].pageCount == 1 }
    }

    @Test
    fun wordFilesThatCannotBePrintedSayWhy() {
        backend.word = true
        runBlocking { session.loadShop() }
        // an old .doc and a ZIP that is no Word file: said on the phone, nothing is sent
        session.addFiles(listOf(TestFiles.incoming(TestFiles.oldDoc("Notice.doc")),
            TestFiles.incoming(TestFiles.docx("Photos.zip"))))
        var st = waitFor("both told") { s -> s.docs.size == 2 && s.docs.all { it.status == DocStatus.ERROR } }
        assertTrue(st.docs[0].error!!.startsWith("This is an older kind of Word file (.doc)"))
        assertEquals("Only PDF, Word (.docx), JPG and PNG files can be printed.", st.docs[1].error)
        assertTrue(st.docs.none { it.canRetry })
        assertTrue(backend.requests.none { it.startsWith("PUT /upload") })
        st.docs.forEach { session.removeDoc(it.local) }

        // one the server refuses after looking inside, and one Word on the Xerox PC cannot open
        session.addFiles(listOf(TestFiles.incoming(TestFiles.docx("Macro.docx", mapOf("word/vbaProject.bin" to "x"))),
            TestFiles.incoming(TestFiles.docx("Odd.docx"))))
        st = waitFor("one refused, one waiting") { s ->
            s.docs.size == 2 && s.docs[0].status == DocStatus.ERROR && s.docs[1].status == DocStatus.CONVERTING
        }
        assertTrue(st.docs[0].error!!.contains("cannot be prepared automatically"))
        backend.wordRefused(st.docs[1].id!!, "Microsoft Word at the Xerox center could not open this file.")
        st = waitFor("the second refused") { s -> s.docs[1].status == DocStatus.ERROR }
        assertEquals("Microsoft Word at the Xerox center could not open this file.", st.docs[1].error)
        assertFalse(st.docs[1].canRetry)
    }

    @Test
    fun aWordFileBeingTurnedIntoPagesSurvivesClosingTheApp() {
        backend.word = true
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.docx("Thesis.docx"))))
        val before = waitFor("with the Xerox center's computer") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.CONVERTING }
        val id = before.docs[0].id!!

        // the app is closed and opened again while the Xerox center's computer works
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        session = newSession()
        assertTrue(runBlocking { session.restoreDraft() })
        var st = session.state.value
        assertEquals(DocStatus.CONVERTING, st.docs[0].status)
        assertEquals("Thesis.docx", st.docs[0].name)
        backend.wordReady(id, 12)
        st = waitFor("its pages") { s -> s.docs[0].status == DocStatus.READY && s.docs[0].file != null }
        assertEquals(12, st.docs[0].pageCount)
        assertEquals("PDF", st.docs[0].type)

        // closed again right after: the pages are fetched, never the Word file shown as a PDF
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        session = newSession()
        assertTrue(runBlocking { session.restoreDraft() })
        st = waitFor("ready after opening again") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.READY && s.docs[0].file != null }
        assertEquals("PDF", edu.campus.printapp.flow.FileTypes.detect(st.docs[0].file!!))
        assertEquals(12, st.docs[0].pageCount)
    }

    @Test
    fun whileThePrintServiceWakesUpTheFilesWaitForIt() {
        // the service sleeps after a quiet time: its host answers "not up yet" for a while
        backend.waking = 3
        assertNull(runBlocking { session.loadShop() })
        assertEquals(OrderSession.WAKING, session.state.value.shopError)     // said calmly, not "check your internet"
        // files chosen meanwhile are not thrown away: they are added as soon as the service is up
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Notes.pdf", 3))))
        val st = allReady(1)
        assertEquals(Step.SETUP, st.step)
        assertNull(st.shopError)
        assertTrue(notices.toList().toString(), notices.none { it.contains("Cannot reach") })
    }

    @Test
    fun noInternetIsNotCalledWakingUp() {
        backend.down = true
        assertNull(runBlocking { session.loadShop() })
        assertTrue(session.state.value.shopError!!.startsWith("Cannot reach the print service"))
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Notes.pdf", 3))))
        waitNotice("cannot reach") { it.startsWith("Cannot reach the print service") }
        assertTrue(session.state.value.docs.isEmpty())
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

    // ------------------------------------------------------------------ collecting by showing the files (no pickup code)

    /** The phone's own copies of the files of the order being put together. */
    private var copies = emptyList<java.io.File>()

    /** Two files, reviewed and paid: on the "your prints" screen. */
    private fun paidOrder(): SessionState {
        copies = addAndWait(TestFiles.pdf("Notes.pdf", 3), TestFiles.png("Photo.png")).docs.mapNotNull { it.file }
        assertEquals(2, copies.count { it.isFile })
        session.review()
        waitFor("review") { it.step == Step.REVIEW }
        if (drawPictures) waitFor("a picture of each file is kept") { it.pictures.size == 2 }
        session.pay()
        return waitFor("paid") { it.step == Step.STATUS && it.order?.paidAt != null }
    }

    @Test
    fun afterPayingTheFilesStayOnThePhoneAsPicturesWithTheTimes() {
        var st = paidOrder()
        val ids = st.order!!.live.map { it.id }
        assertEquals(ids.toSet(), st.pictures.keys)
        assertTrue(st.pictures.values.all { java.io.File(it).readBytes().size > 8 })
        assertTrue(java.io.File(st.pictures.getValue(ids[0])).readText(Charsets.ISO_8859_1).endsWith("phone:Notes.pdf"))
        // the phone's copies of the uploaded files are deleted once they are paid for; their pictures are not
        eventually("the phone's copies of the files are deleted") { copies.none { it.exists() } }
        assertTrue(st.pictures.values.all { java.io.File(it).isFile })
        assertEquals("Paid 3:30 pm  ·  Ready at about 3:34 pm",
            whenLine(st.order!!, java.time.Instant.parse("2026-09-29T10:01:00Z").toEpochMilli(), java.time.ZoneId.of("Asia/Kolkata")))
        st = waitFor("printed") { it.order?.status == "COMPLETED" }
        assertEquals("Paid 3:30 pm  ·  Ready since 3:32 pm",
            whenLine(st.order!!, java.time.Instant.parse("2026-09-29T10:03:00Z").toEpochMilli(), java.time.ZoneId.of("Asia/Kolkata")))
        assertTrue(st.order!!.canCollect)
        assertEquals("All 3 pages · B/W · A4 · 3 sheets", docSummary(st.order!!.live[0]))
    }

    @Test
    fun atTheCounterTheOrderGoesToTheStaffScreenAndTurnsToCollected() {
        var st = paidOrder()
        val id = st.order!!.orderId
        st = waitFor("printed") { it.order?.status == "COMPLETED" }
        assertFalse(st.atCounter)
        assertNull(backend.orders.getValue(id).arrivedAt)

        session.atCounter()
        st = waitFor("at the counter") { it.atCounter && it.order?.arrivedAt != null }
        assertNotNull(backend.orders.getValue(id).arrivedAt)       // the staff's screen shows this order now
        assertFalse(st.counterOffline)

        backend.handOver(id)                                         // staff press "Handed over"
        st = waitFor("collected") { it.order?.isCollected == true && !it.atCounter }
        assertFalse(st.order!!.canCollect)
        assertEquals("Paid 3:30 pm  ·  Collected 3:36 pm",
            whenLine(st.order!!, java.time.Instant.parse("2026-09-29T10:07:00Z").toEpochMilli(), java.time.ZoneId.of("Asia/Kolkata")))
        waitNotice("collected") { it.startsWith("Collected") }
        // nothing left to collect: the button does nothing
        val before = backend.requests.count { it.endsWith("/arrive") }
        session.atCounter()
        Thread.sleep(200)
        assertFalse(session.state.value.atCounter)
        assertEquals(before, backend.requests.count { it.endsWith("/arrive") })
    }

    @Test
    fun leavingTheScreenTakesTheOrderOffTheStaffScreen() {
        val id = paidOrder().order!!.orderId
        session.atCounter()
        waitFor("at the counter") { it.atCounter && it.order?.arrivedAt != null }
        session.leaveCounter()
        eventually("the order left the staff's screen") { backend.orders.getValue(id).arrivedAt == null }
        assertFalse(session.state.value.atCounter)
        // going back to the start does the same
        session.atCounter()
        eventually("at the counter again") { backend.orders.getValue(id).arrivedAt != null }
        session.toHome()
        eventually("left again") { backend.orders.getValue(id).arrivedAt == null }
    }

    @Test
    fun anOrderThatIsNotPaidCannotBeShownAtTheCounter() {
        addAndWait(TestFiles.pdf("Notes.pdf", 1))
        session.review()
        val st = waitFor("review") { it.step == Step.REVIEW }
        assertFalse(st.order!!.canCollect)
        session.atCounter()                                          // not on the "your prints" screen: nothing happens
        Thread.sleep(200)
        assertFalse(session.state.value.atCounter)
        assertTrue(backend.requests.none { it.endsWith("/arrive") })
    }

    @Test
    fun withoutAPictureOfItsOwnThePhoneShowsTheXeroxPcsPicture() {
        drawPictures = false                                         // e.g. the app was reinstalled meanwhile
        var st = paidOrder()
        assertTrue(st.pictures.isEmpty())
        st = waitFor("printed") { it.order?.status == "COMPLETED" }
        val doc = st.order!!.live[0].id
        val printed = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(200) { 7 }
        backend.previews[doc] = printed                              // the Xerox PC sent the picture of the sheet it printed
        st = waitFor("the picture was fetched") { it.pictures.containsKey(doc) }
        assertTrue(java.io.File(st.pictures.getValue(doc)).readBytes().contentEquals(printed))
        assertEquals(1, backend.requests.count { it.endsWith("/preview") })       // fetched once, then kept
    }

    @Test
    fun withoutInternetAtTheCounterTheOrderStillOpensWithItsFiles() {
        val paid = waitForPrinted(paidOrder())
        val saved = store.all().first { it.orderId == paid.order!!.orderId }
        // the app is opened again in a dead spot
        session.toHome()
        backend.down = true
        session = newSession()
        session.openStatus(saved)
        var st = waitFor("the saved order is shown") { it.step == Step.STATUS && it.order?.status == "COMPLETED" && it.offline }
        assertEquals(2, st.pictures.size)                            // the pictures were kept on the phone
        session.atCounter()
        st = waitFor("at the counter, offline") { it.atCounter && it.counterOffline }
        assertNull(backend.orders.getValue(saved.orderId).arrivedAt)
        // back online: the staff's screen gets the order at once (not at the next 45-second repeat), and the note goes
        backend.down = false
        st = waitFor("online again", 25_000) { !it.offline && !it.counterOffline && it.order?.arrivedAt != null }
        assertTrue(st.atCounter)
        assertNotNull(backend.orders.getValue(saved.orderId).arrivedAt)
    }

    private fun waitForPrinted(st: SessionState): SessionState =
        if (st.order?.status == "COMPLETED") st else waitFor("printed") { it.order?.status == "COMPLETED" }

    @Test
    fun anOlderServerStillShowsTheFilesAtTheCounter() {
        backend.oldServer = true
        waitForPrinted(paidOrder())
        session.atCounter()
        eventually("the app asked the server") { backend.requests.any { it.endsWith("/arrive") } }
        Thread.sleep(7000)                                           // two more looks at the order: no asking again and again
        val st = session.state.value
        assertTrue(st.atCounter)                                     // the screen is shown to the staff all the same
        assertFalse(st.counterOffline)
        assertTrue(notices.none { it.contains("not accepted") })
        assertEquals(1, backend.requests.count { it.endsWith("/arrive") })
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
        assertEquals("Only PDF, Word (.docx), JPG and PNG files can be printed.", st.docs[1].error)
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

    // ================================================================== XeoGo Pay: pay with a UPI app, confirmed by itself

    private fun toUpiScreen(): SessionState {
        backend.paymentMode = "upi"
        addAndWait(TestFiles.pdf("Notes.pdf", 2))
        session.review()
        waitFor("review") { it.step == Step.REVIEW }
        session.pay()
        return waitFor("the UPI payment screen") { it.step == Step.PAY && it.upi != null }
    }

    @Test
    fun aUpiPaymentConfirmsByItselfAndTheCodeOpens() {
        val st = toUpiScreen()
        assertEquals("4.01", st.upi!!.amountText)                    // Rs 4 + 1 paisa: this payment's own amount
        assertTrue(st.upi!!.uri.startsWith("upi://pay?pa=xeroxshop@okaxis"))
        assertFalse(st.upiHelp)                                      // nothing to type, nothing to press
        // back from the UPI app: "Confirming your payment"
        session.onUpiAnswer("txnId=ICI123&responseCode=00&Status=SUCCESS&txnRef=CPK7M4X&ApprovalRefNo=627312345678")
        val confirming = waitFor("confirming") { it.upiConfirming }
        assertEquals("6273 1234 5678", confirming.upiRef)            // kept, in case it is ever needed
        assertFalse(backend.requests.any { it.endsWith("/payment/claim") })   // automatic: no claim, the bank decides
        // the Xerox center's Verifier phone passes on the bank's message
        backend.bankConfirms(backend.orders.keys.first())
        val paid = waitFor("paid: your prints open", 20_000) { it.step == Step.STATUS && it.order?.paidAt != null }
        assertEquals("K7M4X", paid.order!!.pickupCode)
        waitNotice("payment received") { it.startsWith("Payment received") }
        assertNull(store.load())
    }

    @Test
    fun aFailedUpiPaymentSaysNoMoneyWasTaken() {
        toUpiScreen()
        session.onUpiAnswer("Status=FAILURE&responseCode=ZD")
        val st = waitFor("the failure") { it.upiError != null }
        assertTrue(st.upiError!!.contains("no money was taken"))
        assertEquals(Step.PAY, st.step)
        assertFalse(st.upiConfirming)
    }

    @Test
    fun paidButNothingHappensTheReferenceFindsIt() {
        toUpiScreen()
        session.showUpiHelp()
        session.setUpiRef("1234")
        session.claimUpi()
        assertTrue(waitFor("refused") { it.upiError != null }.upiError!!.contains("12 digits"))
        session.setUpiRef("6273 1234-5678")
        session.claimUpi()
        waitFor("sent to the counter") { it.step == Step.STATUS }
        assertEquals("627312345678", backend.orders.values.first().claimRef)
        // the status screen offers the payment details again while it is being checked
        assertTrue(session.state.value.order!!.upiOpen)
    }

    @Test
    fun withoutAutomaticConfirmationSuccessTellsTheCounterAtOnce() {
        backend.autoConfirm = false
        val st = toUpiScreen()
        assertTrue(st.upiHelp)                                       // "I have paid" is shown straight away
        session.onUpiAnswer("Status=SUCCESS&ApprovalRefNo=627312345679")
        waitFor("sent to the counter") { it.step == Step.STATUS }
        assertEquals("627312345679", backend.orders.values.first().claimRef)
    }

    @Test
    fun whatUpiAppsAnswer() {
        assertEquals(UpiAnswer(UpiAnswer.Status.SUCCESS, "627312345678"),
            UpiAnswer.parse("txnId=ICI123&responseCode=00&Status=SUCCESS&txnRef=CPK7M4X&ApprovalRefNo=627312345678"))
        assertEquals(UpiAnswer.Status.FAILURE, UpiAnswer.parse("Status=FAILURE").status)
        assertEquals(UpiAnswer.Status.SUBMITTED, UpiAnswer.parse("status=Submitted&txnId=X").status)
        assertEquals(UpiAnswer(UpiAnswer.Status.UNKNOWN, null), UpiAnswer.parse(null))
        // the reference found elsewhere when ApprovalRefNo is empty; our own txnRef is never taken for it
        assertEquals("627312345670", UpiAnswer.parse("Status=SUCCESS&ApprovalRefNo=&txnId=627312345670&txnRef=999999999999").reference)
        assertTrue(UpiRef.ok("") && UpiRef.ok("6273 1234 5678") && !UpiRef.ok("12345"))
        assertEquals("6273 1234 5678", UpiRef.group("627312345678"))
    }
}
