package edu.campus.printapp

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.campus.printapp.core.PrintSettings
import edu.campus.printapp.flow.Doc
import edu.campus.printapp.flow.DocStatus
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState
import edu.campus.printapp.flow.Step
import edu.campus.printapp.ui.CP
import edu.campus.printapp.ui.CampusTheme
import edu.campus.printapp.ui.HomeScreen
import edu.campus.printapp.ui.IntroTiming
import edu.campus.printapp.ui.LocalLively
import edu.campus.printapp.ui.WorkspaceScreen
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How the student app moves, as pictures: the opening, one frame every 100 ms
 * (the clock is moved by hand, so each picture is one exact moment), and the
 * list of files while they are being sent. The pictures are saved in the
 * app's files (shots/motion-...) to be looked at.
 *
 *   gradlew connectedStudentDebugAndroidTest -PapiBase=http://10.0.2.2:8080
 */
private fun save(name: String, picture: () -> Bitmap) {
    // A slow emulator can take longer over one picture than the test library waits; the clock stands still
    // meanwhile, so asking again gives the same moment.
    var bmp: Bitmap? = null
    var tries = 0
    while (bmp == null) {
        try {
            bmp = picture()
        } catch (e: ComposeTimeoutException) {
            if (++tries >= 8) throw e
            Thread.sleep(400)
        }
    }
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

@RunWith(AndroidJUnit4::class)
class OpeningFramesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theOpeningPlaysOnceAndLeavesTheApp() {
        assumeTrue("the student app", !AppConfig.STAFF)
        rule.mainClock.autoAdvance = false
        lateinit var vm: PrintViewModel
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[PrintViewModel::class.java] }
        // the shop's prices come over the network meanwhile (real time); the opening only moves when told
        val until = System.currentTimeMillis() + 20_000
        while (vm.session.state.value.shop == null && System.currentTimeMillis() < until) Thread.sleep(200)
        var t = 0
        fun frame() = save("motion-intro-%04d".format(t)) { rule.onRoot().captureToImage().asAndroidBitmap() }
        frame()
        while (t < IntroTiming.TOTAL + 900) {
            rule.mainClock.advanceTimeBy(100)
            t += 100
            frame()
        }
        assertEquals("the opening is over", 2, vm.intro.value)
        assertEquals(Step.HOME, vm.session.state.value.step)
        // turning the phone does not play it again
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[PrintViewModel::class.java] }
        assertEquals(2, vm.intro.value)
    }
}

@RunWith(AndroidJUnit4::class)
class UploadFramesTest {

    @get:Rule
    val rule = createComposeRule()

    private fun asset(name: String): File {
        val test = InstrumentationRegistry.getInstrumentation().context
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "motion-$name")
        test.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    @Test
    fun theFilesOnTheirWay() {
        assumeTrue("the student app", !AppConfig.STAFF)
        val vm = PrintViewModel(ApplicationProvider.getApplicationContext())
        val s = PrintSettings()
        fun doc(local: Long, name: String, size: Long, pages: Int?, status: DocStatus, progress: Float = 0f) =
            Doc(local, name, size, file = asset(name), type = if (name.endsWith(".pdf")) "PDF" else "JPEG", status = status,
                progress = progress, pageCount = pages, settings = if (pages == null) null else s, id = "id-$local")
        var st by mutableStateOf(SessionState(step = Step.SETUP, docs = listOf(
            doc(1, "Notes.pdf", 2_400_000, null, DocStatus.READING), doc(2, "Assignment.pdf", 1_100_000, null, DocStatus.READING),
            doc(3, "Photo.jpg", 3_800_000, null, DocStatus.READING))))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CampusTheme {
                CompositionLocalProvider(LocalLively provides true) {
                    Box(Modifier.fillMaxSize().background(CP.Bg)) { WorkspaceScreen(vm, st, {}, wide = false) }
                }
            }
        }
        var n = 0
        fun frame(what: String, ms: Long = 450) {
            rule.mainClock.advanceTimeBy(ms)
            Thread.sleep(250)                              // the first pages are drawn off the main thread (real time)
            rule.mainClock.advanceTimeBy(50)
            save("motion-upload-%02d-%s".format(++n, what)) { rule.onRoot().captureToImage().asAndroidBitmap() }
        }
        frame("reading")
        st = st.copy(docs = listOf(doc(1, "Notes.pdf", 2_400_000, 10, DocStatus.UPLOADING, 0.18f),
            doc(2, "Assignment.pdf", 1_100_000, 8, DocStatus.QUEUED), doc(3, "Photo.jpg", 3_800_000, 1, DocStatus.QUEUED)))
        frame("sending-18")
        st = st.copy(docs = listOf(doc(1, "Notes.pdf", 2_400_000, 10, DocStatus.UPLOADING, 0.74f),
            doc(2, "Assignment.pdf", 1_100_000, 8, DocStatus.UPLOADING, 0.35f), doc(3, "Photo.jpg", 3_800_000, 1, DocStatus.QUEUED)))
        frame("sending-two")
        frame("sending-two-later", 600)
        st = st.copy(docs = listOf(doc(1, "Notes.pdf", 2_400_000, 10, DocStatus.CHECKING, 1f),
            doc(2, "Assignment.pdf", 1_100_000, 8, DocStatus.UPLOADING, 0.9f), doc(3, "Photo.jpg", 3_800_000, 1, DocStatus.UPLOADING, 0.4f)))
        frame("checking-one")
        st = st.copy(docs = listOf(doc(1, "Notes.pdf", 2_400_000, 10, DocStatus.READY),
            doc(2, "Assignment.pdf", 1_100_000, 8, DocStatus.CHECKING, 1f), doc(3, "Photo.jpg", 3_800_000, 1, DocStatus.UPLOADING, 0.85f)))
        frame("one-ready", 150)
        frame("one-ready-settled", 500)
        st = st.copy(docs = listOf(doc(1, "Notes.pdf", 2_400_000, 10, DocStatus.READY),
            doc(2, "Assignment.pdf", 1_100_000, 8, DocStatus.READY), doc(3, "Photo.jpg", 3_800_000, 1, DocStatus.CHECKING, 1f)))
        frame("last-checking")
        st = st.copy(docs = listOf(doc(1, "Notes.pdf", 2_400_000, 10, DocStatus.READY),
            doc(2, "Assignment.pdf", 1_100_000, 8, DocStatus.READY), doc(3, "Photo.jpg", 3_800_000, 1, DocStatus.READY)))
        frame("all-ready-turning", 60)
        frame("all-ready-arriving", 120)
        frame("all-ready-tick", 150)
        frame("all-ready-burst", 150)
        frame("all-ready-burst-later", 200)
        frame("all-ready", 900)
        // one more file is added: the card goes back to work, and nothing breaks
        st = st.copy(docs = st.docs + doc(4, "Notes.pdf", 900_000, null, DocStatus.READING).copy(name = "Lab report.pdf"))
        frame("one-more", 500)
        st = st.copy(docs = st.docs.dropLast(1) + doc(4, "Notes.pdf", 900_000, 3, DocStatus.READY).copy(name = "Lab report.pdf"))
        frame("one-more-ready", 700)
    }
}

/** The first page while the print service is waking up (it sleeps after a quiet time): said calmly, and moving. */
@RunWith(AndroidJUnit4::class)
class WakingFrameTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theFirstPageWhileTheServiceWakesUp() {
        assumeTrue("the student app", !AppConfig.STAFF)
        val vm = PrintViewModel(ApplicationProvider.getApplicationContext())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CampusTheme {
                CompositionLocalProvider(LocalLively provides true) {
                    Box(Modifier.fillMaxSize().background(CP.Bg)) {
                        HomeScreen(SessionState(shopError = OrderSession.WAKING), vm.session, {}, arrived = true)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        rule.onNodeWithTag("waking").performScrollTo()
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("waking").assertIsDisplayed()
        save("motion-waking") { rule.onRoot().captureToImage().asAndroidBitmap() }
    }
}
