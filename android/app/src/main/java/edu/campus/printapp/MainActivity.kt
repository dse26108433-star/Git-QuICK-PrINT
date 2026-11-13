package edu.campus.printapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.IntentCompat
import androidx.core.graphics.drawable.toBitmap
import edu.campus.printapp.flow.Step
import edu.campus.printapp.ui.CampusPrintApp
import edu.campus.printapp.ui.UpiApp

/**
 * The only screen: picking and sharing files, and opening a UPI app to pay.
 * The order is in flow/OrderSession and what you see in ui/.
 */
class MainActivity : ComponentActivity() {

    private val vm: PrintViewModel by viewModels()

    /**
     * Several files at once (PDF, Word, JPG, PNG). Old Word files (.doc) can be chosen too: the app then says
     * how to save them in a form that can be printed, which is kinder than a file that cannot be tapped.
     */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.addUris(uris)
    }

    /** XeoGo Pay: the UPI app closes and comes back here with its answer ("Status=SUCCESS&..."). */
    private val upiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        val answer = data?.getStringExtra("response")
            ?: data?.extras?.let { b -> b.keySet().joinToString("&") { k -> k + "=" + (b.get(k)?.toString() ?: "") } }
        vm.session.onUpiAnswer(answer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_CampusPrint)            // the start screen's colours were only for the moment before this
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShared(intent)
        else vm.introAt(2)        // coming back to where one was (not a fresh start): no opening in the way
        setContent {
            CampusPrintApp(
                vm = vm,
                onChooseFiles = {
                    picker.launch(arrayOf("application/pdf", "image/png", "image/jpeg",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword"))
                },
                upiApps = ::upiApps,
                onOpenUpi = ::openUpi
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from a UPI app (even without an answer): look at once whether the bank confirmed the payment.
        if (vm.session.state.value.step == Step.PAY) vm.session.checkUpi()
    }

    /** The UPI apps installed on this phone: every app that opens upi://pay links. */
    private fun upiApps(): List<UpiApp> {
        val pm = packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=test@upi&pn=Test&am=1.00&cu=INR"))
        return pm.queryIntentActivities(probe, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .map { ri ->
                val icon = runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                UpiApp(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), icon)
            }
            .sortedBy { popular.indexOf(it.packageName).let { i -> if (i < 0) 99 else i } }
    }

    private val popular = listOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
        "in.org.npci.upiapp", "in.amazon.mShop.android.shopping", "com.dreamplug.androidapp")

    /** Opens one UPI app with the payment filled in, or Android's own list of UPI apps (packageName null). */
    private fun openUpi(uri: String, packageName: String?) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        try {
            if (packageName != null) {
                upiLauncher.launch(intent.setPackage(packageName))
            } else {
                upiLauncher.launch(Intent.createChooser(intent, "Pay with"))
            }
        } catch (e: ActivityNotFoundException) {
            vm.session.onUpiAnswer("Status=FAILURE")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** Files shared from WhatsApp / Files / Drive (one or several), or opened with "Open with". */
    private fun handleShared(intent: Intent?) {
        if (intent != null) vm.addUris(sharedFiles(intent))
    }

    companion object {
        /** The files an intent brings: one or several shared, or one opened. */
        fun sharedFiles(intent: Intent): List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
    }
}
