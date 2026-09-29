package edu.campus.printapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.campus.printapp.PrintViewModel
import edu.campus.printapp.flow.NoticeKind
import edu.campus.printapp.flow.SessionState
import edu.campus.printapp.flow.Step
import edu.campus.printapp.net.PaymentStart

/**
 * The whole app: Home → your files (each set up on its own) → Review and pay
 * → the pickup code. Phones show one file's settings full screen; tablets and
 * wide screens show the list and the settings side by side.
 */
@Composable
fun CampusPrintApp(vm: PrintViewModel, onChooseFiles: () -> Unit, onOpenCheckout: (PaymentStart) -> Unit) {
    val st by vm.session.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val kind = remember { mutableStateOf(NoticeKind.OK) }

    LaunchedEffect(Unit) {
        vm.session.notices.collect { n ->
            kind.value = n.kind
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(n.text, duration = if (n.kind == NoticeKind.BAD) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }
    LaunchedEffect(Unit) { vm.session.checkout.collect { onOpenCheckout(it) } }
    // the printers' options stay up to date while the app is on screen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) vm.onScreen(true)
            if (e == Lifecycle.Event.ON_STOP) vm.onScreen(false)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = st.step != Step.HOME) { vm.back() }

    CampusTheme {
        Scaffold(
            containerColor = CP.Bg,
            contentColor = CP.Ink,
            snackbarHost = {
                SnackbarHost(snack) { data ->
                    Snackbar(data, containerColor = when (kind.value) {
                        NoticeKind.BAD -> CP.Danger; NoticeKind.WARN -> Color(0xFF6B4410); else -> CP.Ink
                    }, contentColor = Color.White, shape = RoundedCornerShape(12.dp))
                }
            }
        ) { pad ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
                val screenW = maxWidth
                val wide = screenW >= 840.dp
                val editing = st.step == Step.SETUP && ui.open && st.selectedDoc != null && !wide
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    if (!editing) {
                        Header(st)
                        when (st.step) {
                            Step.SETUP -> StepBar(0)
                            Step.REVIEW -> StepBar(1)
                            Step.STATUS -> StepBar(2)
                            Step.HOME -> {}
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (st.step) {
                            Step.HOME -> HomeScreen(st, vm.session, onChooseFiles)
                            Step.SETUP -> if (editing) {
                                EditorScreen(vm, st, st.selectedDoc!!, fullScreen = true)
                            } else if (wide) {
                                Row(Modifier.fillMaxSize()) {
                                    Column(Modifier.width((screenW * 0.38f).coerceIn(340.dp, 460.dp)).fillMaxHeight()) {
                                        WorkspaceScreen(vm, st, onChooseFiles, wide = true, modifier = Modifier.weight(1f))
                                        CheckoutBar(vm, st)
                                    }
                                    Box(Modifier.fillMaxHeight().width(1.dp).background(CP.Line))
                                    val d = st.selectedDoc
                                    if (d != null) EditorScreen(vm, st, d, fullScreen = false)
                                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Choose a file to set it up.", color = CP.Muted)
                                    }
                                }
                            } else {
                                Column(Modifier.fillMaxSize()) {
                                    WorkspaceScreen(vm, st, onChooseFiles, wide = false, modifier = Modifier.weight(1f))
                                    CheckoutBar(vm, st)
                                }
                            }
                            Step.REVIEW -> ReviewScreen(vm, st)
                            Step.STATUS -> StatusScreen(st, vm.session)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(st: SessionState) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 420.dp
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(CP.Ink), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 15.dp, height = 19.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
            Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(CP.Accent))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Campus Print", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CP.Ink, maxLines = 1)
            Text(st.shop?.centerName ?: "Xerox center", fontSize = 13.sp, color = CP.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val shop = st.shop
        val online = shop != null && (shop.bwOnline || shop.colorOnline)
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(CP.Surface).border(1.dp, CP.Line, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Lamp(if (shop == null) CP.Line2 else if (online) CP.Accent else CP.Danger, 9.dp)
            Spacer(Modifier.width(7.dp))
            Text(when {
                st.shopError != null && shop == null -> if (narrow) "No service" else "Service unreachable"
                shop == null -> "…"
                narrow -> if (online) "Online" else "Offline"
                online -> "Printers online" + if (shop.ordersWaiting > 0) " · ${shop.ordersWaiting} in queue" else ""
                else -> "Printers offline"
            }, fontSize = 12.5.sp, color = CP.Ink2, maxLines = 1)
        }
    }
}
