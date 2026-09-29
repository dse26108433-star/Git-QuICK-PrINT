package edu.campus.printapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import edu.campus.printapp.net.PaymentStart
import edu.campus.printapp.ui.CampusPrintApp
import org.json.JSONObject

/**
 * The only screen. Razorpay requires the Activity itself to receive the
 * payment result, so that part lives here; the order is in flow/OrderSession
 * and what you see in ui/.
 */
class MainActivity : ComponentActivity(), PaymentResultWithDataListener {

    private val vm: PrintViewModel by viewModels()

    /** Several files at once (PDF, JPG, PNG). */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.addUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            CampusPrintApp(
                vm = vm,
                onChooseFiles = { picker.launch(arrayOf("application/pdf", "image/png", "image/jpeg")) },
                onOpenCheckout = ::openCheckout
            )
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

    private fun openCheckout(start: PaymentStart) {
        val checkout = Checkout()
        checkout.setKeyID(start.keyId)
        val options = JSONObject().apply {
            put("name", vm.session.state.value.shop?.centerName ?: "Campus Print")
            put("description", start.description)
            put("order_id", start.gatewayOrderId)     // created by OUR backend, never by the app
            put("amount", start.amountPaise)
            put("currency", start.currency)
            put("theme", JSONObject().put("color", "#0E1A2B"))
            put("retry", JSONObject().put("enabled", true).put("max_count", 4))
        }
        try {
            checkout.open(this, options)
        } catch (e: Exception) {
            vm.session.onPaymentError("Could not open the payment screen: ${e.message}")
        }
    }

    // ---- Razorpay calls these when its screen closes ----

    override fun onPaymentSuccess(razorpayPaymentId: String?, data: PaymentData?) {
        vm.session.onPaymentSuccess(data?.paymentId ?: razorpayPaymentId, data?.signature)
    }

    override fun onPaymentError(code: Int, response: String?, data: PaymentData?) {
        val reason = runCatching { JSONObject(response ?: "").optJSONObject("error")?.optString("description") }.getOrNull()
        vm.session.onPaymentError(
            if (reason.isNullOrBlank()) "Payment was not completed. No money was taken for this attempt."
            else "Payment failed: $reason. No money was taken for this attempt."
        )
    }
}
