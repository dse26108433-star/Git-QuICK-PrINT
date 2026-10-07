package edu.campus.printapp

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.campus.printapp.core.UserError
import edu.campus.printapp.flow.IncomingFile
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.Step
import edu.campus.printapp.net.PrintApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What only the screen needs: which file is open full screen, which tab, which sheet of the preview. */
data class EditorUi(
    val open: Boolean = false,          // phones: the settings of one file fill the screen
    val pagesTab: Boolean = false,      // "Choose pages" instead of "Print preview"
    val sheet: Map<Long, Int> = emptyMap()
)

/**
 * Connects the order (flow/OrderSession, plain Kotlin) to Android: picked and
 * shared files, drawing pages (and the picture of each file that is shown at
 * the counter), keeping the printers' options up to date, and Razorpay's
 * screen (opened by MainActivity).
 */
class PrintViewModel(app: Application) : AndroidViewModel(app) {

    private val stores = PhoneStores(app)
    private val work = File(app.filesDir, "work").apply { mkdirs() }

    val images = PageImages()
    val session = OrderSession(PrintApi(AppConfig.API_BASE, staffToken = { if (AppConfig.STAFF) stores.token() else null }),
        viewModelScope, PageImages.reader, stores, stores, workDir = work,
        log = { what, e -> android.util.Log.w("CampusPrint", what, e) },
        pictures = PhonePictures(app), painter = edu.campus.printapp.ui.SheetPictureMaker(images),
        staffStore = if (AppConfig.STAFF) stores else null)

    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    /**
     * The opening animation of the student app (ui/Intro.kt): 0 = playing, 1 = the app shows through and its
     * first page arrives, 2 = over. Kept here so turning the phone does not play it again. The staff app has none.
     */
    private val _intro = MutableStateFlow(if (AppConfig.STAFF) 2 else 0)
    val intro: StateFlow<Int> = _intro.asStateFlow()

    fun introAt(stage: Int) = _intro.update { maxOf(it, stage) }

    private var refresher: Job? = null

    init {
        viewModelScope.launch {
            session.refreshStaff()                        // the staff app: who is signed in, and the pages left
            session.loadShop()
            val restored = session.signedIn && session.restoreDraft()
            if (!restored) cleanWorkFiles()
            session.refreshRecent()
        }
        viewModelScope.launch {
            session.problems.collect { local -> openEditor(local) }
        }
    }

    // ---------------------------------------------------------------- files from the picker or "Share"

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        session.addFiles(uris.distinct().map { uri ->
            val (name, size) = describe(uri)
            IncomingFile(name, size) { copyIn(uri) }
        })
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        return (name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document") to size
    }

    /** The app's own copy of a picked file, so reading, preview and upload are reliable. */
    private suspend fun copyIn(uri: Uri): File = withContext(Dispatchers.IO) {
        val max = session.state.value.shop?.maxFileSizeBytes ?: (50L shl 20)
        val out = File(work, "f-${System.nanoTime()}.bin")
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw UserError("This file cannot be opened.")
        input.use { src ->
            out.outputStream().use { dst ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buffer)
                    if (n == -1) break
                    total += n
                    if (total > max) {
                        dst.close()
                        out.delete()
                        throw UserError("Too big: files can be up to ${max / 1048576} MB.")
                    }
                    dst.write(buffer, 0, n)
                }
            }
        }
        out
    }

    /** Copies left behind by an order that was paid or cancelled while the app was closed. */
    private fun cleanWorkFiles() {
        val keep = session.state.value.docs.mapNotNull { it.file?.path }.toSet()
        work.listFiles()?.forEach { if (it.path !in keep) it.delete() }
    }

    // ---------------------------------------------------------------- the editor

    fun openEditor(local: Long) {
        session.select(local)
        _ui.update { it.copy(open = true, pagesTab = false) }
    }

    fun closeEditor() = _ui.update { it.copy(open = false) }

    fun showPagesTab(on: Boolean) = _ui.update { it.copy(pagesTab = on) }

    fun setSheet(local: Long, k: Int) = _ui.update { it.copy(sheet = it.sheet + (local to k)) }

    fun removeDoc(local: Long) {
        session.state.value.doc(local)?.file?.let { images.forget(it) }
        session.removeDoc(local)
        if (session.state.value.docs.isEmpty()) closeEditor()
    }

    /** Phone back button. Returns false when there is nowhere to go back to (the app closes). */
    fun back(): Boolean {
        val s = session.state.value
        return when {
            s.step == Step.SETUP && _ui.value.open -> { closeEditor(); true }
            s.step == Step.SETUP -> { session.toHome(); true }
            s.step == Step.REVIEW -> { if (!s.paymentStarted && s.order?.editable == true) session.edit() else session.toHome(); true }
            s.step == Step.PAY -> { session.backFromPay(); true }
            s.step == Step.STATUS -> { session.toHome(); true }
            else -> false                                  // HOME, and the staff app's sign-in screen
        }
    }

    // ---------------------------------------------------------------- the printers' options stay up to date

    /** While the app is on screen: every minute, and at once when it comes back. */
    fun onScreen(visible: Boolean) {
        session.onScreen(visible)
        refresher?.cancel()
        if (!visible) return
        refresher = viewModelScope.launch {
            while (isActive) {
                session.refreshStaff()
                val shop = session.loadShop()
                delay(if (shop == null) 6_000 else 60_000)      // not there yet (it may be waking up): soon again
            }
        }
    }
}
