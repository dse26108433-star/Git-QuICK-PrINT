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
import org.json.JSONObject

/**
 * The only screen. Razorpay requires the Activity itself to receive the
 * payment result, so that part lives here; everything else is in
 * PrintViewModel (logic) and Screens.kt (what you see).
 */
class MainActivity : ComponentActivity(), PaymentResultWithDataListener {

    private val vm: PrintViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.pickFile(uri)
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
                onChooseFile = { picker.launch(arrayOf("application/pdf", "image/png", "image/jpeg")) },
                onOpenCheckout = ::openCheckout
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** A file shared from WhatsApp / Files / Drive, or opened with "Open with". */
    private fun handleShared(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) vm.pickFile(uri)
    }

    private fun openCheckout(start: PaymentStart) {
        val checkout = Checkout()
        checkout.setKeyID(start.keyId)
        val options = JSONObject().apply {
            put("name", vm.state.value.shop?.centerName ?: "Campus Print")
            put("description", start.description)
            put("order_id", start.gatewayOrderId)     // created by OUR backend, never by the app
            put("amount", start.amountPaise)
            put("currency", start.currency)
            put("theme", JSONObject().put("color", "#14263B"))
            put("retry", JSONObject().put("enabled", true).put("max_count", 4))
        }
        try {
            checkout.open(this, options)
        } catch (e: Exception) {
            vm.onPaymentError("Could not open the payment screen: ${e.message}")
        }
    }

    // ---- Razorpay calls these when its screen closes ----

    override fun onPaymentSuccess(razorpayPaymentId: String?, data: PaymentData?) {
        vm.onPaymentSuccess(data?.paymentId ?: razorpayPaymentId, data?.signature)
    }

    override fun onPaymentError(code: Int, response: String?, data: PaymentData?) {
        val reason = runCatching {
            JSONObject(response ?: "").optJSONObject("error")?.optString("description")
        }.getOrNull()
        vm.onPaymentError(
            if (reason.isNullOrBlank()) "Payment was not completed. No money was taken for this attempt."
            else "Payment failed: $reason"
        )
    }
}
