package edu.campus.printapp.flow

import edu.campus.printapp.net.PrintApi
import edu.campus.printapp.support.FakeBackend
import edu.campus.printapp.support.TestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What happens to a file when the connection is not there: the cases that used
 * to leave the app stuck ("after the net came back I could not upload any
 * more").
 *
 *   - a file goes in pieces; a dropped connection loses one piece, not the file
 *   - a file that stopped for lack of a connection is sent again by itself
 *   - a file that did arrive (only the answer was lost) is not sent again,
 *     and not shown as "already uploaded" for ever
 *   - an order that ran out while the phone was away is replaced by a new one
 *     with the same files, also after the app was closed
 *   - choosing the same file again means "try again"
 *   - a Xerox center whose computer is off is "closed", not an error
 */
class UploadRecoveryTest {

    private lateinit var backend: FakeBackend
    private lateinit var scope: CoroutineScope
    private lateinit var store: MemoryStore
    private lateinit var session: OrderSession
    private val notices = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        backend = FakeBackend()
        backend.pieceBytes = 200                 // a server that takes files in pieces; tiny ones, so test files are several
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = MemoryStore()
        session = newSession()
    }

    /** retryMs: the first wait before a stopped file is sent again by itself. */
    private fun newSession(retryMs: Long = 80) = OrderSession(PrintApi(backend.base, pieceRetryMs = 25), scope, TestFiles.reader,
        store, store, workDir = TestFiles.dir, wakeRetryMs = 60, wordPollMs = 40, retryBaseMs = retryMs, checkRetryMs = 25).also { s ->
        scope.launch { s.notices.collect { notices += it.text } }
    }

    @After
    fun stop() {
        scope.cancel()
        backend.close()
    }

    private fun waitFor(what: String, ms: Long = 20_000, test: (SessionState) -> Boolean): SessionState = runBlocking {
        try {
            withTimeout(ms) { session.state.first(test) }
        } catch (e: Exception) {
            throw AssertionError("Waited for: $what. State: " + session.state.value.docs.map { "${it.name}=${it.status} ${it.error ?: ""}" } +
                " notices=" + notices + " server got: " + backend.requests.takeLast(12))
        }
    }

    private fun uploads() = backend.requests.count { it.startsWith("PUT /upload") }

    // ------------------------------------------------------------------ pieces

    @Test
    fun aFileGoesInPiecesAndArrivesWhole() {
        val file = TestFiles.pdf("Notes.pdf", 6)
        assertTrue("the test file must be several pieces", file.length() > 3 * 200)
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(file)))
        val st = waitFor("ready") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.READY }
        assertEquals(6, st.docs[0].pageCount)
        assertTrue(backend.piecesReceived.get() > 3)
        assertEquals("every byte once", file.length(), backend.bytesReceived.get())
    }

    @Test
    fun aDroppedConnectionLosesOnePieceNotTheFile() {
        val file = TestFiles.pdf("Big.pdf", 12)
        runBlocking { session.loadShop() }
        backend.dropPieces = 3                    // three times the connection goes while a piece is on its way
        session.addFiles(listOf(TestFiles.incoming(file)))
        val st = waitFor("ready all the same") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.READY }
        assertEquals(12, st.docs[0].pageCount)
        assertEquals(0, backend.dropPieces)
        // nothing that had arrived was sent a second time: the upload carried on from where the server was
        assertEquals(file.length(), backend.bytesReceived.get())
        assertTrue(notices.none { it.contains("stopped") })
    }

    // ------------------------------------------------------------------ the connection is away for a while

    @Test
    fun aFileThatStoppedIsSentAgainByItselfWhenTheConnectionIsBack() {
        runBlocking { session.loadShop() }
        backend.down = true                       // the phone walks out of its signal
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Notes.pdf", 3))))
        var st = waitFor("says it stopped") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.ERROR }
        assertEquals(OrderSession.STOPPED, st.docs[0].error)
        assertTrue(st.docs[0].canRetry && st.docs[0].temporary)

        backend.down = false                      // ...and back in. Nobody presses anything.
        st = waitFor("ready by itself") { s -> s.docs[0].status == DocStatus.READY }
        assertEquals(3, st.docs[0].pageCount)
        assertFalse(st.docs[0].temporary)
    }

    @Test
    fun whenAndroidSaysTheConnectionIsBackItCarriesOnAtOnce() {
        session = newSession(retryMs = 600_000)   // by itself it would wait ten minutes
        runBlocking { session.loadShop() }
        backend.down = true
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("A.pdf", 2)), TestFiles.incoming(TestFiles.pdf("B.pdf", 4))))
        waitFor("both stopped") { s -> s.docs.size == 2 && s.docs.all { it.status == DocStatus.ERROR && it.temporary } }
        backend.down = false
        session.networkBack()
        val st = waitFor("both ready") { s -> s.docs.all { it.status == DocStatus.READY } }
        assertEquals(listOf(2, 4), st.docs.map { it.pageCount })
    }

    @Test
    fun newFilesGoUpAfterAnotherOneStopped() {
        // what was reported: "an upload got cancelled because the net went, and after the net came back I
        // could not upload new files"
        session = newSession(retryMs = 600_000)
        runBlocking { session.loadShop() }
        backend.down = true
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("First.pdf", 5))))
        waitFor("the first stopped") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.ERROR }
        backend.down = false
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Second.pdf", 2)), TestFiles.incoming(TestFiles.png("Photo.png"))))
        var st = waitFor("the new files are ready") { s ->
            s.docs.size == 3 && s.docs[1].status == DocStatus.READY && s.docs[2].status == DocStatus.READY
        }
        // the first one is still waiting for its turn (ten minutes, in this test): "Try again" sends it now
        assertEquals(DocStatus.ERROR, st.docs[0].status)
        session.retry(st.docs[0].local)
        st = waitFor("all three ready") { s -> s.docs.all { it.status == DocStatus.READY } }
        assertTrue(session.checkout(st).canReview)
    }

    // ------------------------------------------------------------------ it arrived, but the phone did not hear of it

    @Test
    fun aFileWhoseCheckGotNoAnswerIsAskedAboutNotSentAgain() {
        val file = TestFiles.pdf("Report.pdf", 9)
        runBlocking { session.loadShop() }
        backend.loseCheckAnswers = 1              // the server checks it; the answer never reaches the phone
        session.addFiles(listOf(TestFiles.incoming(file)))
        val st = waitFor("ready") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.READY }
        assertEquals(9, st.docs[0].pageCount)
        assertEquals("sent once", file.length(), backend.bytesReceived.get())
    }

    @Test
    fun aFileThatArrivedBeforeTheConnectionWentIsNotAlreadyUploadedForEver() {
        val file = TestFiles.pdf("Thesis.pdf", 8)
        runBlocking { session.loadShop() }
        // the server checks the file, and at that very moment the phone loses its connection for a while
        backend.loseCheckAnswers = 1
        backend.downAfterCheck = true
        session.addFiles(listOf(TestFiles.incoming(file)))
        var st = waitFor("says it stopped") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.ERROR }
        assertTrue(st.docs[0].temporary)
        val before = uploads()

        backend.down = false
        st = waitFor("ready, not \"already uploaded\"") { s -> s.docs[0].status == DocStatus.READY }
        assertEquals(8, st.docs[0].pageCount)
        assertEquals("not one more byte was sent", before, uploads())
        assertTrue(session.checkout(st).canReview)
    }

    // ------------------------------------------------------------------ an order that ran out

    @Test
    fun anOrderThatRanOutIsReplacedAndItsFilesGoIntoTheNewOne() {
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Notes.pdf", 3))))
        var st = waitFor("ready") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.READY }
        val old = st.draft!!.orderId
        session.change(st.docs[0].local) { it.copy(copies = 2) }

        // the phone is in a pocket for three hours; the server lets the unfinished order run out
        backend.expire(old)
        session.addFiles(listOf(TestFiles.incoming(TestFiles.png("Photo.png"))))
        st = waitFor("both ready in a new order") { s ->
            s.draft != null && s.draft.orderId != old && s.docs.size == 2 && s.docs.all { it.status == DocStatus.READY }
        }
        val fresh = st.draft!!.orderId
        assertNotEquals(old, fresh)
        assertEquals(listOf("Notes.pdf", "Photo.png"), backend.fileNames(fresh).sorted())
        assertEquals("the settings chosen are kept", 2, st.docs.first { it.name == "Notes.pdf" }.settings!!.copies)
        assertTrue(notices.any { it.contains("A new one was started with the same files") })
        assertEquals(fresh, store.load()!!.orderId)                // and it is what the phone remembers

        session.review()
        st = waitFor("priced") { it.step == Step.REVIEW }
        assertEquals(2, st.order!!.live.size)
        session.pay()
        waitFor("paid and printed", 30_000) { it.step == Step.STATUS && it.order?.status == "COMPLETED" }
    }

    @Test
    fun afterTheAppWasClosedOvernightTheFilesGoIntoANewOrder() {
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Notes.pdf", 4)), TestFiles.incoming(TestFiles.pdf("Lab.pdf", 2))))
        var st = waitFor("ready") { s -> s.docs.size == 2 && s.docs.all { it.status == DocStatus.READY } }
        val old = st.draft!!.orderId
        session.setPagesText(st.docs[0].local, "2-3")
        session.saveDraft()

        // the app is closed; the center closes for the night and the order runs out
        scope.cancel()
        backend.expire(old)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        session = newSession()
        assertTrue(runBlocking { session.restoreDraft() })
        st = waitFor("both there again, in a new order") { s ->
            s.draft != null && s.draft.orderId != old && s.docs.size == 2 && s.docs.all { it.status == DocStatus.READY }
        }
        assertEquals(listOf("Notes.pdf", "Lab.pdf"), st.docs.map { it.name })
        assertEquals("2-3", st.docs[0].settings!!.pages)
        assertEquals(listOf("Lab.pdf", "Notes.pdf"), backend.fileNames(st.draft!!.orderId).sorted())
    }

    // ------------------------------------------------------------------ small things that felt like "stuck"

    @Test
    fun choosingTheSameFileAgainMeansTryAgain() {
        session = newSession(retryMs = 600_000)
        val file = TestFiles.pdf("Notes.pdf", 3)
        runBlocking { session.loadShop() }
        backend.down = true
        session.addFiles(listOf(TestFiles.incoming(file)))
        waitFor("stopped") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.ERROR }
        backend.down = false

        session.addFiles(listOf(TestFiles.incoming(file)))          // the student simply picks it again
        val st = waitFor("ready") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.READY }
        assertEquals(1, st.docs.size)
        assertTrue(notices.none { it.contains("already in your list") })

        // a file that is fine and is chosen again is still "already in your list"
        session.addFiles(listOf(TestFiles.incoming(file)))
        Thread.sleep(300)
        assertEquals(1, session.state.value.docs.size)
        assertTrue(notices.any { it.contains("already in your list") })
    }

    @Test
    fun aCancelledUploadCanBeTriedAgainAndOthersAreNotHeldUp() {
        backend.uploadDelayMs = 400
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Slow.pdf", 10))))
        var st = waitFor("on its way") { s -> s.docs.size == 1 && s.docs[0].status == DocStatus.UPLOADING }
        session.cancelUpload(st.docs[0].local)
        st = waitFor("cancelled") { s -> s.docs[0].status == DocStatus.CANCELLED }

        backend.uploadDelayMs = 0
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Other.pdf", 2))))
        waitFor("the other one is ready") { s -> s.docs.size == 2 && s.docs[1].status == DocStatus.READY }
        session.retry(st.docs[0].local)
        st = waitFor("and the cancelled one too, after Try again") { s -> s.docs.all { it.status == DocStatus.READY } }
        assertEquals(10, st.docs[0].pageCount)
    }

    // ------------------------------------------------------------------ the print service moves house

    @Test
    fun whenThePrintServiceMovesTheAppFollowsAndTakesItsUnfinishedOrderAlong() {
        // today: the print service in the cloud. A student has two files in an unfinished order there.
        runBlocking { session.loadShop() }
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Notes.pdf", 3)), TestFiles.incoming(TestFiles.png("Photo.png"))))
        var st = waitFor("ready at the old place") { s -> s.docs.size == 2 && s.docs.all { it.status == DocStatus.READY } }
        val oldOrder = st.draft!!.orderId
        session.saveDraft()

        // tonight the Xerox center moves it to its own PC: a new server, and the old one says where it went
        val fresh = FakeBackend()
        fresh.pieceBytes = 200
        try {
            backend.movedTo = fresh.base
            val remembered = java.util.concurrent.atomic.AtomicReference<String?>()

            // next morning the app is opened (the same app: nothing was installed)
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val api = PrintApi(backend.base, pieceRetryMs = 25)
            session = OrderSession(api, scope, TestFiles.reader, store, store, workDir = TestFiles.dir, wakeRetryMs = 60,
                retryBaseMs = 80, checkRetryMs = 25, onMoved = { remembered.set(it) })
            val shop = runBlocking { session.loadShop() }
            assertTrue(shop != null && shop.movedTo == null)             // this is the new place's answer
            assertEquals(fresh.base, api.base)
            assertEquals(fresh.base, remembered.get())

            // the unfinished order: the files are on the phone, and go into a new order at the new place
            assertTrue(runBlocking { session.restoreDraft() })
            st = waitFor("both files ready at the new place") { s ->
                s.draft != null && s.draft.orderId != oldOrder && s.docs.size == 2 && s.docs.all { it.status == DocStatus.READY }
            }
            assertEquals(listOf("Notes.pdf", "Photo.png"), fresh.fileNames(st.draft!!.orderId).sorted())
            session.review()
            waitFor("priced at the new place") { it.step == Step.REVIEW }

            // an app that starts with what the phone remembered goes straight to the new place
            val later = PrintApi(backend.base, moved = remembered.get())
            assertEquals(fresh.base, later.base)
            // nothing but a proper address is ever followed
            assertFalse(PrintApi("https://print.example.in").moveTo("http://evil.example"))
            assertFalse(PrintApi("https://print.example.in").moveTo("javascript:alert(1)"))
            assertTrue(PrintApi("https://print.example.in").moveTo("https://print.mycollege.in/"))
        } finally {
            fresh.close()
        }
    }

    @Test
    fun aCenterWhoseComputerIsOffIsClosedNotBroken() {
        backend.closed = true                     // its address answers "nothing behind me" (the PC is switched off)
        assertEquals(null, runBlocking { session.loadShop() })
        assertEquals(OrderSession.CLOSED, session.state.value.shopError)
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Notes.pdf", 1))))
        Thread.sleep(300)
        assertTrue(session.state.value.docs.isEmpty())
        assertTrue(notices.any { it == OrderSession.CLOSED })

        backend.closed = false                    // the center opens
        assertTrue(runBlocking { session.loadShop() } != null)
        assertEquals(null, session.state.value.shopError)
    }
}
