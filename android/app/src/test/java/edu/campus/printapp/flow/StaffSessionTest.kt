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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The staff app (XeoGo Staff): a college staff member signs in with the staff
 * ID the Xerox center made, prints for free up to the month's pages, and
 * collects like everyone else, on any phone signed in with the ID.
 */
class StaffSessionTest {

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
        session = newSession(store)
    }

    /** The app on a phone: its own storage, the same print service. */
    private fun newSession(phone: MemoryStore) =
        OrderSession(PrintApi(backend.base, staffToken = { phone.token() }), scope, TestFiles.reader, phone, phone,
            workDir = TestFiles.dir, staffStore = phone).also { s ->
            scope.launch { s.notices.collect { notices += it.text } }
        }

    @After
    fun stop() {
        scope.cancel()
        backend.close()
    }

    private fun waitFor(what: String, s: OrderSession = session, ms: Long = 15_000, test: (SessionState) -> Boolean): SessionState =
        runBlocking {
            try {
                withTimeout(ms) { s.state.first(test) }
            } catch (e: Exception) {
                throw AssertionError("Waited for: $what. step=" + s.state.value.step + " staff=" + s.state.value.staff +
                    " loginError=" + s.state.value.loginError + " payError=" + s.state.value.payError + " notices=" + notices +
                    " server got: " + backend.requests)
            }
        }

    private fun signIn(s: OrderSession = session): SessionState {
        s.signIn("asha.kulkarni", "kmtpx-2vw9d")
        return waitFor("signed in", s) { it.step == Step.HOME && it.staff != null }
    }

    private fun addAndReview(pages: Int, name: String = "Notes.pdf"): SessionState {
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf(name, pages))))
        waitFor("the file is ready") { s -> s.docs.size == 1 && s.docs.all { it.status == DocStatus.READY } }
        session.review()
        return waitFor("reviewed") { it.step == Step.REVIEW && it.order?.freePages == pages }
    }

    @Test
    fun theAppStartsAtTheSignInAndNeedsTheRightPassword() {
        var st = session.state.value
        assertTrue(st.staffApp)
        assertEquals(Step.LOGIN, st.step)                              // nothing else can be reached without signing in
        assertFalse(session.signedIn)

        session.signIn("", "")
        assertEquals("Type your username and your password.", session.state.value.loginError)
        session.signIn("asha.kulkarni", "wrong-wrong")
        st = waitFor("the wrong password is refused") { it.loginError != null && !it.signingIn }
        assertEquals(Step.LOGIN, st.step)
        assertTrue(st.loginError!!.contains("Wrong username or password"))
        assertNull(store.token())

        st = signIn()
        assertEquals("Prof. Asha Kulkarni", st.staff!!.name)
        assertEquals(1000, st.staff!!.leftPages)
        assertNotNull(store.token())                                  // the phone keeps the sign-in, never the password
        assertFalse(store.token()!!.contains("kmtpx"))
        assertNull(st.loginError)
    }

    @Test
    fun aStaffOrderIsPrintedForFreeAndCountedAgainstTheMonth() {
        signIn()
        var st = addAndReview(7)
        assertEquals(0, st.order!!.amountPaise)
        val c = session.checkout(st)
        assertEquals(7, c.pages)

        session.pay()                                                  // "Print now": no payment of any kind
        st = waitFor("sent to print") { it.step == Step.STATUS && it.order?.paidAt != null }
        assertTrue(st.order!!.isFree)
        assertTrue(backend.requests.any { it.endsWith("/staff-print") })
        assertTrue(backend.requests.none { it.endsWith("/payment") || it.contains("/payment/") })
        st = waitFor("the free pages left are fresh") { it.staff?.usedPages == 7 }
        assertEquals(993, st.staff!!.leftPages)
        assertEquals("Sent 3:30 pm  ·  Ready at about 3:34 pm",
            whenLine(st.order!!, java.time.Instant.parse("2026-09-29T10:01:00Z").toEpochMilli(), java.time.ZoneId.of("Asia/Kolkata")))
        assertNull(store.load())                                       // the unfinished order is done with
    }

    @Test
    fun moreThanTheFreePagesLeftCannotBePrinted() {
        backend.staffLimit = 10
        signIn()
        // 12 pages of 10: the bar says so before anything is reviewed
        session.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Long.pdf", 12))))
        var st = waitFor("the file is ready") { s -> s.docs.size == 1 && s.docs.all { it.status == DocStatus.READY } }
        var c = session.checkout(st)
        assertTrue(c.over)
        assertFalse(c.canReview)
        assertEquals("Only 10 free pages are left this month.", c.why)

        // 8 pages fit
        session.setPagesText(st.docs[0].local, "1-8")
        c = session.checkout(session.state.value)
        assertTrue(c.canReview)
        session.review()
        waitFor("reviewed") { it.step == Step.REVIEW && it.order?.freePages == 8 }
        // meanwhile the pages were used on another device: the server refuses, and the app shows what is left now
        backend.staffLimit = 5
        session.pay()
        st = waitFor("refused") { it.payError != null && !it.paying }
        assertEquals(Step.REVIEW, st.step)
        assertTrue(st.payError!!.contains("only 5 of your 5 free pages"))
        st = waitFor("the pages left are fresh") { it.staff?.leftPages == 5 }
        assertNull(st.order!!.paidAt)
        assertEquals(0, backend.staffUsed("asha.kulkarni"))
    }

    @Test
    fun colourIsNotOfferedWhereItIsNotFree() {
        signIn()
        runBlocking { session.loadShop() }
        var st = session.state.value
        assertFalse(st.offered.color)                                   // only black & white is offered
        assertTrue(st.printers.none { it.color })

        // the Xerox center allows colour for staff: the app follows by itself
        backend.staffColor = true
        runBlocking { session.refreshStaff() }
        st = waitFor("colour is offered") { it.offered.color }
        assertTrue(st.staff!!.colorAllowed)
    }

    @Test
    fun whatWasSentFromOneDeviceIsCollectedOnAnother() {
        signIn()
        addAndReview(3, "Timetable.pdf")
        session.pay()
        val sent = waitFor("sent") { it.step == Step.STATUS && it.order?.paidAt != null }
        val id = sent.order!!.orderId
        waitFor("printed") { it.order?.status == "COMPLETED" }

        // the staff member's phone: signed in with the same ID, it never saw this order
        val phone = MemoryStore()
        val there = newSession(phone)
        signIn(there)
        var st = waitFor("their prints are listed", there) { it.recent.isNotEmpty() }
        assertEquals(listOf(id), st.recent.map { it.first.orderId })
        assertEquals("Timetable.pdf", st.recent[0].first.fileName)
        there.openStatus(st.recent[0].first)
        st = waitFor("the order opens", there) { it.step == Step.STATUS && it.order?.status == "COMPLETED" }
        there.atCounter()
        waitFor("at the counter", there) { it.atCounter && it.order?.arrivedAt != null }
        assertNotNull(backend.orders.getValue(id).arrivedAt)
        backend.handOver(id)
        st = waitFor("collected", there) { it.order?.isCollected == true }
        assertEquals("Sent 3:30 pm  ·  Collected 3:36 pm",
            whenLine(st.order!!, java.time.Instant.parse("2026-09-29T10:07:00Z").toEpochMilli(), java.time.ZoneId.of("Asia/Kolkata")))
    }

    @Test
    fun aNewPasswordSignsThePhoneOut() {
        signIn()
        addAndReview(2)
        backend.newStaffPassword("asha.kulkarni", "zzzzz99999")           // the Xerox center made a new one
        session.pay()
        var st = waitFor("signed out") { it.step == Step.LOGIN }
        assertNull(store.token())
        assertNull(st.staff)
        assertEquals("Please sign in again with your staff ID.", st.loginError)
        assertEquals(0, backend.staffUsed("asha.kulkarni"))                 // nothing was printed with the old sign-in

        // the old password is nothing now; the new one works, and the unfinished order is still there
        session.signIn("asha.kulkarni", "kmtpx-2vw9d")
        st = waitFor("refused") { it.loginError?.contains("Wrong username") == true && !it.signingIn }
        session.signIn("asha.kulkarni", "ZZZZZ-99999")
        st = waitFor("signed in again") { it.step == Step.HOME && it.staff != null }
        assertNotNull(store.load())
    }

    @Test
    fun signingOutForgetsEverythingOnThePhone() {
        signIn()
        addAndReview(2)
        session.pay()
        waitFor("sent") { it.step == Step.STATUS && it.order?.paidAt != null }
        session.toHome()
        waitFor("listed") { it.recent.isNotEmpty() }
        assertTrue(store.all().isNotEmpty())

        session.signOut()
        val st = waitFor("signed out") { it.step == Step.LOGIN }
        assertNull(st.staff)
        assertTrue(st.recent.isEmpty() && st.docs.isEmpty() && st.order == null)
        assertTrue(store.all().isEmpty())
        assertNull(store.load())
        Thread.sleep(500)
        assertNull(store.token())
        // and without a sign-in nothing is asked of the server as this person
        session.refreshRecent()
        Thread.sleep(200)
        assertTrue(session.state.value.recent.isEmpty())
    }

    @Test
    fun theStudentAppNeverSignsInAndNeverPrintsForFree() {
        val phone = MemoryStore()
        val student = OrderSession(PrintApi(backend.base), scope, TestFiles.reader, phone, phone, workDir = TestFiles.dir)
        assertFalse(student.state.value.staffApp)
        assertEquals(Step.HOME, student.state.value.step)
        assertTrue(student.signedIn)
        student.signIn("asha.kulkarni", "kmtpx-2vw9d")                      // does nothing in the student app
        Thread.sleep(200)
        assertNull(phone.token())
        assertTrue(backend.requests.none { it.contains("/staff/") })
        student.addFiles(listOf(TestFiles.incoming(TestFiles.pdf("Mine.pdf", 2))))
        waitFor("ready", student) { s -> s.docs.size == 1 && s.docs.all { it.status == DocStatus.READY } }
        student.review()
        val st = waitFor("priced", student) { it.step == Step.REVIEW }
        assertEquals(400, st.order!!.amountPaise)                           // 2 pages x Rs 2: it is paid for
        assertNull(st.order!!.freePages)
        student.pay()
        waitFor("paid", student) { it.step == Step.STATUS && it.order?.paidAt != null }
        assertTrue(backend.requests.none { it.endsWith("/staff-print") })
    }
}
