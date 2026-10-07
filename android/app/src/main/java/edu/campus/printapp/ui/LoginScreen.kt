package edu.campus.printapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState

/**
 * The staff app's first screen: the staff ID the Xerox center made (a
 * username and a password) and nothing else. The phone keeps the sign-in the
 * server gives back, never the password.
 */
@Composable
fun LoginScreen(st: SessionState, session: OrderSession) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }             // not saved anywhere, not even across a rotation
    var show by remember { mutableStateOf(false) }
    val go = { session.signIn(username, password) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 460.dp).align(Alignment.CenterHorizontally).padding(horizontal = 20.dp)) {
            Spacer(Modifier.size(28.dp))
            Text("Staff sign in", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = CP.Ink)
            Spacer(Modifier.size(6.dp))
            Text("Free printing for college staff at " + (st.shop?.centerName ?: "the Xerox center") +
                ". Sign in with the staff ID the Xerox center gave you.", fontSize = 15.sp, color = CP.Muted, lineHeight = 22.sp)
            Spacer(Modifier.size(22.dp))
            OutlinedTextField(username, { username = it.take(60) }, label = { Text("Username") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("loginUser"))
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(password, { password = it.take(60) }, label = { Text("Password") }, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { go() }),
                modifier = Modifier.fillMaxWidth().testTag("loginPass"))
            Row(Modifier.clickable { show = !show }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(show, { show = it })
                Text("Show the password", fontSize = 14.sp, color = CP.Ink2)
            }
            st.loginError?.let { ErrorLine(it, Modifier.testTag("loginError")) }
            Spacer(Modifier.size(12.dp))
            PrimaryButton(if (st.signingIn) "Signing in…" else "Sign in", go, Modifier.fillMaxWidth().testTag("loginBtn"),
                enabled = !st.signingIn)
            Spacer(Modifier.size(16.dp))
            Hint("No staff ID yet, or forgot the password? Ask at the Xerox center: they make one in a minute. " +
                "Capitals and the dash do not matter when you type the password.")
            st.shopError?.let {
                Spacer(Modifier.size(10.dp))
                ErrorLine(it)
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}
