package edu.campus.printapp

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.campus.printapp.flow.DocStatus
import edu.campus.printapp.flow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real app on a phone, against the real print service (LocalDemo on the
 * computer, reached from the emulator at 10.0.2.2:8080):
 *
 *   gradlew connectedDebugAndroidTest -PapiBase=http://10.0.2.2:8080
 *
 * The same order as the website's example: Notes.pdf all pages B/W
 * two-sided, Assignment.pdf pages 3 and 7 × 2 in colour, a phone photo
 * (turned by its camera) in colour, filling the page. Screenshots of each
 * step are saved in the app's files (shots/).
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var vm: PrintViewModel

    @Before
    fun fresh() {
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[PrintViewModel::class.java] }
        // start from an empty order
        rule.runOnIdle { if (vm.session.state.value.docs.isNotEmpty()) vm.session.resetToStart() }
        waitUntil("the shop", 60_000) { vm.session.state.value.shop != null }
    }

    private fun waitUntil(what: String, ms: Long = 30_000, test: () -> Boolean) {
        try {
            rule.waitUntil(ms) { test() }
        } catch (e: Throwable) {
            shot("zz-failed-" + what.replace(' ', '-'))
            val s = vm.session.state.value
            throw AssertionError("Waited for $what; step=${s.step} docs=" + s.docs.map { "${it.name}=${it.status} ${it.error ?: it.serverError ?: ""}" })
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(700)
        // the app's own window drawn into a picture (the test emulator's display itself cannot be captured)
        val bmp: Bitmap = runCatching { rule.onRoot().captureToImage().asAndroidBitmap() }.getOrNull() ?: return
        val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun asset(name: String): Uri {
        val test = InstrumentationRegistry.getInstrumentation().context
        val out = File(ctx.cacheDir, "in-$name")
        test.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(out)
    }

    /** A tap through the button's own click action: works even when the emulator's system dialogs steal the focus. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private val radio = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    /** A choice in the editor (scrolled into view first). */
    private fun choice(text: String): SemanticsNodeInteraction {
        val m = hasText(text) and radio
        rule.onNodeWithTag("editorList").performScrollToNode(m)
        return rule.onNode(m)
    }

    private fun tapInEditor(m: SemanticsMatcher) {
        rule.onNodeWithTag("editorList").performScrollToNode(m)
        rule.onNode(m).tap()
    }

    private fun doc(name: String) = vm.session.state.value.docs.first { it.name.endsWith(name) }

    @Test
    fun theExampleOrderOnAPhone() {
        shot("a00-home")
        rule.runOnUiThread { vm.addUris(listOf(asset("Notes.pdf"), asset("Assignment.pdf"), asset("Photo.jpg"))) }
        waitUntil("3 files ready", 90_000) { vm.session.state.value.docs.let { d -> d.size == 3 && d.all { it.status == DocStatus.READY } } }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("All 3 files ready")).fetchSemanticsNodes().isNotEmpty() }
        shot("a01-list")

        // Notes: two-sided
        rule.onNodeWithTag("file:in-Notes.pdf").tap()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("editorList")).fetchSemanticsNodes().isNotEmpty() }
        shot("a02-editor")
        choice("flip on long edge").tap()
        waitUntil("two-sided") { doc("Notes.pdf").settings?.duplex == "LONG_EDGE" }
        shot("a03-two-sided")

        // Assignment: pages 3 and 7 on the page pictures, 2 copies, colour
        rule.onNode(hasContentDescription("Next file")).tap()
        waitUntil("assignment open") { vm.session.state.value.selectedDoc?.name?.endsWith("Assignment.pdf") == true }
        rule.onNode(hasText("Choose pages") and radio).tap()
        waitUntil("the page pictures") { vm.ui.value.pagesTab }
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag("pagesBar")).fetchSemanticsNodes().isNotEmpty() }
        shot("a04a-page-pictures")
        tapInEditor(hasText("None") and button)
        waitUntil("no pages") { doc("Assignment.pdf").settings?.pages == "" }
        tapInEditor(hasContentDescription("Page 3"))
        tapInEditor(hasContentDescription("Page 7"))
        waitUntil("pages 3 and 7") { doc("Assignment.pdf").settings?.pages == "3,7" }
        shot("a04-pages")
        rule.onNodeWithTag("editorList").performScrollToNode(hasTestTag("pagesSummary"))
        val summary = rule.onNodeWithTag("pagesSummary").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
        assertEquals("Selected: 3, 7 · Total pages to print: 2", summary)
        choice("2").tap()
        choice("Colour").tap()
        waitUntil("2 colour copies") { doc("Assignment.pdf").settings?.let { it.copies == 2 && it.color } == true }
        choice("Print preview").tap()
        waitUntil("the preview again") { !vm.ui.value.pagesTab }
        shot("a05-assignment")

        // the photo: colour, fill the page (never stretched)
        rule.onNode(hasContentDescription("Next file")).tap()
        waitUntil("photo open") { vm.session.state.value.selectedDoc?.name?.endsWith("Photo.jpg") == true }
        choice("Colour").tap()
        choice("Fill").tap()
        waitUntil("photo colour fill") { doc("Photo.jpg").settings?.let { it.color && it.scaling == "FILL" } == true }
        rule.onNodeWithTag("editorList").performScrollToNode(hasContentDescription("Print preview"))
        shot("a06-photo")
        rule.onNode(hasText("Done") and hasClickAction()).tap()
        waitUntil("list again") { !vm.ui.value.open }
        shot("a07-list-set")
        assertEquals("₹70", rule.onNodeWithTag("total").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""))

        // review: the server's answer, what prints
        rule.onNodeWithTag("reviewBtn").tap()
        waitUntil("review", 30_000) { vm.session.state.value.step == Step.REVIEW }
        val order = vm.session.state.value.order!!
        assertEquals(7000, order.amountPaise)
        assertEquals(listOf("3,7"), order.live.filter { it.fileName.endsWith("Assignment.pdf") }.map { it.settings!!.pages })
        shot("a08-review")

        // pay (test mode) and the pickup code
        rule.onNodeWithTag("payBtn").tap()
        waitUntil("paid", 30_000) { vm.session.state.value.let { it.step == Step.STATUS && it.order?.paidAt != null } }
        shot("a09-status")
        assertTrue(vm.session.state.value.order!!.pickupCode.length == 5)
    }

    /** Several files shared into the app (WhatsApp, Files...) land in one order; the arrows change the print order. */
    @Test
    fun sharedFilesCanBeMovedIntoTheRightOrder() {
        val shared = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(asset("Notes.pdf"), asset("Assignment.pdf"), asset("Photo.jpg")))
        }
        val uris = MainActivity.sharedFiles(shared)
        assertEquals(3, uris.size)
        rule.runOnUiThread { vm.addUris(uris) }
        waitUntil("3 shared files ready", 90_000) { vm.session.state.value.docs.let { d -> d.size == 3 && d.all { it.status == DocStatus.READY } } }
        assertEquals(1, vm.session.state.value.docs.map { vm.session.state.value.draft!!.orderId }.distinct().size)
        // the photo first
        rule.onNode(hasContentDescription("Print in-Photo.jpg earlier")).tap()
        rule.onNode(hasContentDescription("Print in-Photo.jpg earlier")).tap()
        waitUntil("photo first") { vm.session.state.value.docs.first().name.endsWith("Photo.jpg") }
        shot("b01-shared-moved")
        assertEquals(listOf("in-Photo.jpg", "in-Notes.pdf", "in-Assignment.pdf"), vm.session.state.value.docs.map { it.name })
        rule.onNodeWithTag("reviewBtn").tap()
        waitUntil("review", 30_000) { vm.session.state.value.step == Step.REVIEW }
        assertEquals(listOf("in-Photo.jpg", "in-Notes.pdf", "in-Assignment.pdf"), vm.session.state.value.order!!.live.map { it.fileName })
    }

    /** On a tablet (840 dp and wider) the list and one file's settings are side by side, no full-screen editor. */
    @Test
    fun onATabletTheListAndSettingsAreSideBySide() {
        assumeTrue("needs a tablet-size screen", ctx.resources.configuration.screenWidthDp >= 840)
        rule.runOnUiThread { vm.addUris(listOf(asset("Notes.pdf"), asset("Photo.jpg"))) }
        waitUntil("2 files ready", 90_000) { vm.session.state.value.docs.let { d -> d.size == 2 && d.all { it.status == DocStatus.READY } } }
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag("editorList")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasTestTag("fileList")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("file:in-Photo.jpg").tap()
        waitUntil("photo shown") { vm.session.state.value.selectedDoc?.name?.endsWith("Photo.jpg") == true }
        assertTrue(rule.onAllNodes(hasTestTag("fileList")).fetchSemanticsNodes().isNotEmpty())   // the list stays
        shot("c01-tablet")
    }
}
