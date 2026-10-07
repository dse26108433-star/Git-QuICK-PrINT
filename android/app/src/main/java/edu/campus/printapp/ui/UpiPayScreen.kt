package edu.campus.printapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.PrintViewModel
import edu.campus.printapp.flow.SessionState
import edu.campus.printapp.flow.UpiRef

/** A UPI app installed on this phone (found by MainActivity). */
data class UpiApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * XeoGo Pay: pay the Xerox center with a UPI app on this phone. The apps
 * listed are the ones really installed. After paying, the app comes back
 * here by itself and waits for the bank's message, which confirms the payment
 * automatically; then the order opens, with its files and the times.
 */
@Composable
fun UpiPayScreen(vm: PrintViewModel, st: SessionState, apps: List<UpiApp>, onOpenUpi: (String, String?) -> Unit) {
    val session = vm.session
    val u = st.upi ?: return
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 520.dp).align(Alignment.CenterHorizontally).padding(16.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CP.Surface)) {
                // the amount, and who is paid
                Column(Modifier.fillMaxWidth().background(CP.Ink).padding(vertical = 24.dp, horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PAY WITH UPI", fontSize = 12.5.sp, letterSpacing = 3.sp, color = Color(0xFF9FB0C3), fontWeight = FontWeight.SemiBold)
                    Text("₹" + u.amountText, fontSize = 50.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.testTag("upiAmount"))
                    Text("to " + u.payeeName, fontSize = 15.sp, color = Color(0xFFC9D3DE))
                    Spacer(Modifier.size(10.dp))
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(20.dp))
                        .clickable { clipboard.setText(AnnotatedString(u.payeeVpa)) }.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(u.payeeVpa, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy", fontSize = 12.5.sp, color = Color(0xFF9FB0C3))
                    }
                    if (u.tagPaise > 0) {
                        Text("Includes ${UpiRef.paiseWords(u.tagPaise)} that ${if (u.tagPaise == 1) "marks" else "mark"} this " +
                            "payment as yours. Pay exactly ₹${u.amountText}.", fontSize = 13.sp, color = Color(0xFFB5C2D0),
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    // the UPI apps on this phone
                    Text("Choose your UPI app", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(10.dp))
                    if (apps.isEmpty()) {
                        Hint("No UPI app was found on this phone. Install Google Pay, PhonePe, Paytm or BHIM, or pay " +
                            "₹${u.amountText} to the UPI ID above from another phone.")
                    } else {
                        apps.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { app -> AppTile(app, Modifier.weight(1f)) { onOpenUpi(u.uri, app.packageName) } }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    GhostButton("Other UPI app…", { onOpenUpi(u.uri, null) }, Modifier.fillMaxWidth().testTag("upiOther"), small = true)

                    // waiting for the bank
                    Spacer(Modifier.size(16.dp))
                    Divider()
                    Spacer(Modifier.size(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Lamp(if (st.upiConfirming) CP.Accent else CP.Warn, 11.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (st.upiConfirming) "Confirming your payment…" else "Waiting for your payment",
                                fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.testTag("upiState"))
                            Text(if (u.autoConfirm) "Pay in the UPI app and come back: the bank confirms it by itself, usually " +
                                    "in a few seconds, and your prints open."
                                else "After paying, tap the button below: the Xerox center checks the payment, then it prints.",
                                fontSize = 13.sp, color = CP.Muted)
                        }
                    }
                    st.upiError?.let { ErrorLine(it) }

                    if (st.upiHelp) {
                        Spacer(Modifier.size(14.dp))
                        Text(if (u.autoConfirm) "Find my payment" else "After paying", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        OutlinedTextField(
                            value = st.upiRef, onValueChange = { session.setUpiRef(it) }, singleLine = true,
                            label = { Text("UPI reference number" + if (u.autoConfirm) "" else " (optional)") },
                            placeholder = { Text("e.g. 6273 1234 5678") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("upiRef")
                        )
                        Hint("On your UPI app's receipt, called UTR, UPI Ref No. or UPI transaction ID.")
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = { session.claimUpi() }, enabled = !st.claiming,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("upiPaid"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CP.Accent)
                        ) {
                            Text(if (st.claiming) "Sending…" else if (u.autoConfirm) "Find my payment" else "I have paid ₹${u.amountText}",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    } else if (st.upiHelpOffered) {
                        TextButton(onClick = { session.showUpiHelp() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Paid, but nothing happens?", color = CP.Muted)
                        }
                    }
                }
            }
            Text("XeoGo Pay · straight to the Xerox center's account", fontSize = 12.5.sp, color = CP.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            TextButton(onClick = { session.backFromPay() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Back to the summary", color = CP.Muted)
            }
        }
    }
}

@Composable
private fun AppTile(app: UpiApp, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, CP.Line, RoundedCornerShape(14.dp)).background(CP.Surface2)
        .clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp).testTag("upiApp"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (app.icon != null) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(CP.Ink), contentAlignment = Alignment.Center) {
                Text(app.label.take(1), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(app.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
    }
}
