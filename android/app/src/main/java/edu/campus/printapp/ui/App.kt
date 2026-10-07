package edu.campus.printapp.ui

import android.app.Activity
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.painterResource
import edu.campus.printapp.PrintViewModel
import edu.campus.printapp.R
import edu.campus.printapp.flow.NoticeKind
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState
import edu.campus.printapp.flow.Step
import edu.campus.printapp.net.PaymentStart

/**
 * The whole app: Home → your files (each set up on its own) → Review and pay
 * → your prints (the files, the times, "I'm at the counter"). Phones show one
 * file's settings full screen; tablets and wide screens show the list and the
 * settings side by side.
 *
 * The staff app (XeoGo Staff) is the same with a sign-in screen first, a strip
 * that says who is signed in and how many free pages are left, and "Print"
 * where the student app says "Pay".
 */
@Composable
fun CampusPrintApp(vm: PrintViewModel, onChooseFiles: () -> Unit, onOpenCheckout: (PaymentStart) -> Unit,
                   upiApps: () -> List<UpiApp> = { emptyList() }, onOpenUpi: (String, String?) -> Unit = { _, _ -> }) {
    val st by vm.session.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val intro by vm.intro.collectAsStateWithLifecycle()
    // The student app moves (an opening, pages that slide, uploads that show they are alive); the staff app
    // stays still, and so does everything when the phone is set to show no animations.
    val lively = !st.staffApp && !animationsOff()
    LaunchedEffect(lively) { if (!lively) vm.introAt(2) }
    // The opening is dark, the app is light: the clock and the battery at the top follow.
    val view = LocalView.current
    if (lively) LaunchedEffect(intro >= 1) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = intro >= 1
            isAppearanceLightNavigationBars = intro >= 1
        }
    }
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
    BackHandler(enabled = st.step != Step.HOME && st.step != Step.LOGIN) { vm.back() }

    CampusTheme {
      CompositionLocalProvider(LocalLively provides lively) {
       Box(Modifier.fillMaxSize()) {
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
                        if (st.staffApp && st.step != Step.LOGIN) StaffStrip(st) { vm.session.signOut() }
                        when (st.step) {
                            Step.SETUP -> StepBar(0, st.staffApp)
                            Step.REVIEW, Step.PAY -> StepBar(1, st.staffApp)
                            Step.STATUS -> StepBar(2, st.staffApp)
                            Step.HOME, Step.LOGIN -> {}
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        // One page of the app. `s` is the order as that page shows it.
                        val page: @Composable (Step, SessionState) -> Unit = { step, s ->
                            when (step) {
                                Step.LOGIN -> LoginScreen(s, vm.session)
                                Step.HOME -> HomeScreen(s, vm.session, onChooseFiles, arrived = intro >= 1)
                                Step.SETUP -> {
                                    val open = s.step == Step.SETUP && ui.open && s.selectedDoc != null && !wide
                                    if (open) {
                                        EditorScreen(vm, s, s.selectedDoc!!, fullScreen = true)
                                    } else if (wide) {
                                        Row(Modifier.fillMaxSize()) {
                                            Column(Modifier.width((screenW * 0.38f).coerceIn(340.dp, 460.dp)).fillMaxHeight()) {
                                                WorkspaceScreen(vm, s, onChooseFiles, wide = true, modifier = Modifier.weight(1f))
                                                CheckoutBar(vm, s)
                                            }
                                            Box(Modifier.fillMaxHeight().width(1.dp).background(CP.Line))
                                            val d = s.selectedDoc
                                            if (d != null) EditorScreen(vm, s, d, fullScreen = false)
                                            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text("Choose a file to set it up.", color = CP.Muted)
                                            }
                                        }
                                    } else {
                                        Column(Modifier.fillMaxSize()) {
                                            WorkspaceScreen(vm, s, onChooseFiles, wide = false, modifier = Modifier.weight(1f))
                                            CheckoutBar(vm, s)
                                        }
                                    }
                                }
                                Step.REVIEW -> ReviewScreen(vm, s)
                                Step.PAY -> {
                                    val apps = remember(s.upi?.uri) { upiApps() }
                                    UpiPayScreen(vm, s, apps, onOpenUpi)
                                }
                                Step.STATUS -> StatusScreen(s, vm.session)
                            }
                        }
                        if (!lively) {
                            page(st.step, st)
                        } else {
                            // Going on, the next page comes in from the right; going back, from the left. A page
                            // that is leaving keeps showing what it showed last (not the emptied order).
                            val last = remember { mutableMapOf<Step, SessionState>() }
                            last[st.step] = st
                            AnimatedContent(
                                targetState = st.step,
                                transitionSpec = {
                                    val forward = targetState.ordinal > initialState.ordinal
                                    val from: (Int) -> Int = { w -> if (forward) w / 7 else -w / 7 }
                                    (slideInHorizontally(tween(340, easing = EaseOutSoft), from) + fadeIn(tween(260, delayMillis = 50)))
                                        .togetherWith(slideOutHorizontally(tween(220), { w -> -from(w) / 2 }) + fadeOut(tween(150)))
                                },
                                modifier = Modifier.fillMaxSize(),
                                label = "page"
                            ) { step -> page(step, if (step == st.step) st else last[step] ?: st) }
                        }
                    }
                }
            }
        }
        // The opening of the student app, over everything, once per start (a tap opens the app at once).
        if (lively && intro < 2) Intro(onOpen = { vm.introAt(1) }, onDone = { vm.introAt(2) })
       }
      }
    }
}

@Composable
private fun Header(st: SessionState) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 420.dp
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo), contentDescription = null,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (st.staffApp) "XeoGo Staff" else "XeoGo", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CP.Ink, maxLines = 1)
            Text(st.shop?.centerName ?: "Xerox center", fontSize = 13.sp, color = CP.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val shop = st.shop
        val online = shop != null && (shop.bwOnline || shop.colorOnline)
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(CP.Surface).border(1.dp, CP.Line, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            val waking = shop == null && st.shopError == OrderSession.WAKING
            Lamp(if (waking) CP.Warn else if (shop == null) CP.Line2 else if (online) CP.Accent else CP.Danger, 9.dp)
            Spacer(Modifier.width(7.dp))
            Text(when {
                waking -> if (narrow) "Waking up" else "Service waking up"
                st.shopError != null && shop == null -> if (narrow) "No service" else "Service unreachable"
                shop == null -> "…"
                narrow -> if (online) "Online" else "Offline"
                online -> "Printers online" + if (shop.ordersWaiting > 0) " · ${shop.ordersWaiting} in queue" else ""
                else -> "Printers offline"
            }, fontSize = 12.5.sp, color = CP.Ink2, maxLines = 1)
        }
    }
}

/** The staff app: who is signed in, the free pages left this month, and "Sign out". */
@Composable
private fun StaffStrip(st: SessionState, onSignOut: () -> Unit) {
    val s = st.staff
    val ask = remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(CP.Ink).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(s?.name ?: "Signed in", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (s != null) {
            Text("${s.leftPages} of ${s.monthlyPages} free pages left", color = Color.White, fontSize = 12.5.sp, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (s.leftPages <= 0) CP.Danger else Color(0x26FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 3.dp).testTag("staffLeft"))
        }
        Spacer(Modifier.width(12.dp))
        Text("Sign out", color = Color(0xFFC9D3DF), fontSize = 13.sp, modifier = Modifier.clickable { ask.value = true }
            .padding(vertical = 4.dp).testTag("signOut"))
    }
    if (ask.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ask.value = false },
            title = { Text("Sign out?") },
            text = { Text("This phone forgets your staff ID and your prints. An order that is not sent yet is deleted.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { ask.value = false; onSignOut() }) { Text("Sign out", color = CP.Danger) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { ask.value = false }) { Text("Stay signed in") } }
        )
    }
}
