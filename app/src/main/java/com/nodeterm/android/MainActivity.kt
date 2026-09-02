package com.nodeterm.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nodeterm.android.ui.BoardScreen
import com.nodeterm.android.ui.ConnectingView
import com.nodeterm.android.ui.FileBrowserScreen
import com.nodeterm.android.ui.HomeScreen
import com.nodeterm.android.ui.NodeDetailScreen
import com.nodeterm.android.ui.PairingScreen
import com.nodeterm.android.ui.Phase
import com.nodeterm.android.ui.RelayViewModel
import com.nodeterm.android.ui.SasScreen
import com.nodeterm.android.ui.SettingsScreen
import com.nodeterm.android.notify.NotificationHelper
import com.nodeterm.android.ui.theme.NodetermTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private var viewModel: RelayViewModel? = null
    /** Deep-link code arriving before the ViewModel exists (cold start via `nodeterm://pair`). */
    private var pendingPairCode: String? = null
    /** Notification deep link: nodeId from a tapped NEEDS-YOU push, opened once its row syncs. */
    private val pendingNodeId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPairCode = intent.pairCodeParam()
        pendingNodeId.value = intent.extraNodeId()
        enableEdgeToEdge()
        setContent {
            NodetermTheme {
                val viewModel: RelayViewModel = viewModel()
                this.viewModel = viewModel
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val snackbar = remember { SnackbarHostState() }
                val context = LocalContext.current

                // Consume a `nodeterm://pair?code=…` deep link that arrived with the intent.
                LaunchedEffect(Unit) {
                    pendingPairCode?.let { code ->
                        pendingPairCode = null
                        if (viewModel.pairCode(code)) {
                            nav.navigate("sas") { popUpTo("pairing") { inclusive = false } }
                        }
                    }
                }

                // Battery: stop the 5s status polling while backgrounded; resume (with an
                // immediate refresh) when the user returns.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.setPollingEnabled(false) }
                LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.setPollingEnabled(true) }

                // Android 13+: NEEDS YOU pushes require the runtime notification grant.
                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* outcome reflected by NotificationHelper.hasPermission */ }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // The NavHost's startDestination must be FROZEN after first composition: recomputing
                // it from ui.phase recreates the NavGraph (NavHost keys `remember` on it) and calls
                // setGraph(), which resets navigation and tears down in-flight composables. Concretely,
                // after "Unpair and reset" the phase flip READY→NO_SESSION changed the start
                // destination home→pairing, which recreated the graph, double-pushed "pairing", and
                // disposed the QR camera entry ~1.2s later — leaving a frozen scan box. All routing
                // below is explicit navigate() calls, so the frozen value only picks the first screen.
                val startDest = remember { startDestination(ui.phase) }
                // A session restored with an unconfirmed SAS code needs the SAS screen, but the start
                // destination is frozen (see above) — navigate explicitly when the phase flips to it.
                LaunchedEffect(ui.phase) {
                    if (ui.phase == Phase.SAS_CONFIRM) {
                        val route = nav.currentBackStackEntry?.destination?.route
                        if (route != "sas") nav.navigate("sas") { popUpTo("home") { inclusive = true } }
                    }
                }

                ui.error?.let { msg ->
                    LaunchedEffect(msg) {
                        snackbar.showSnackbar(msg)
                        viewModel.clearError()
                    }
                }
                ui.notice?.let { msg ->
                    LaunchedEffect(msg) {
                        snackbar.showSnackbar(msg)
                        viewModel.clearNotice()
                    }
                }

                androidx.compose.material3.Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = startDest,
                        modifier = Modifier.padding(padding)
                    ) {
                        composable("pairing") {
                            PairingScreen(onCode = { code ->
                                if (viewModel.pairCode(code)) {
                                    nav.navigate("sas") { popUpTo("pairing") { inclusive = false } }
                                }
                            })
                        }
                        composable("sas") {
                            when (ui.phase) {
                                Phase.SAS_CONFIRM -> SasScreen(
                                    sas = ui.sas,
                                    hostLabel = "desktop",
                                    onConfirm = {
                                        viewModel.confirmSas()
                                        nav.navigate("home") { popUpTo("sas") { inclusive = true } }
                                    },
                                    onCancel = { viewModel.unpair(); nav.navigate("pairing") { popUpTo("sas") { inclusive = true } } }
                                )
                                Phase.CONNECTING -> ConnectingView()
                                Phase.NO_SESSION -> {
                                    // Pairing failed — fall back to the pairing screen.
                                    LaunchedEffect(Unit) {
                                        nav.navigate("pairing") { popUpTo("sas") { inclusive = true } }
                                    }
                                }
                                else -> {
                                    // Session restored and already confirmed — jump home.
                                    LaunchedEffect(Unit) { nav.navigate("home") { popUpTo("sas") { inclusive = true } } }
                                }
                            }
                        }
                        composable("home") {
                            // Notification deep link: tapping a NEEDS-YOU push opens the node's
                            // terminal once its row has been synced (cold start waits for the
                            // first poll). Hidden/swiped-away nodes still open — the row is
                            // rebuilt from the raw project data in the ViewModel.
                            val pendingNodeIdValue = pendingNodeId.value
                            LaunchedEffect(pendingNodeIdValue, ui.nodes, ui.projects, ui.phase) {
                                val id = pendingNodeIdValue ?: return@LaunchedEffect
                                val found = ui.nodes.any { it.nodeId == id } ||
                                    ui.projects.any { p -> p.nodes.any { it.id == id } }
                                if (found) {
                                    pendingNodeId.value = null
                                    viewModel.openNodeById(id)
                                    nav.navigate("node")
                                } else if (ui.phase == Phase.DISCONNECTED || ui.phase == Phase.NO_SESSION) {
                                    // The session is gone and the node never synced — don't hold
                                    // the deep link forever.
                                    pendingNodeId.value = null
                                }
                            }
                            HomeScreen(
                                state = ui,
                                onOpenNode = { node ->
                                    viewModel.openNode(node)
                                    nav.navigate("node")
                                },
                                onAnswer = { nodeId, pendingId, decision ->
                                    viewModel.answerApproval(nodeId, pendingId, decision)
                                },
                                onAnswerQuestion = { nodeId, text -> viewModel.answerQuestion(nodeId, text) },
                                onOpenBoard = { nav.navigate("board") },
                                onBrowse = { node ->
                                    viewModel.openFiles(node.cwd)
                                    nav.navigate("files")
                                },
                                onSettings = { nav.navigate("settings") },
                                onRepair = {
                                    viewModel.disconnect()
                                    nav.navigate("pairing") { popUpTo("home") { inclusive = true } }
                                },
                                onDeleteNode = { node -> viewModel.hideNode(node.nodeId) },
                                onMoveNode = { nodeId, targetId -> viewModel.moveNode(nodeId, targetId) },
                                onReorderCommit = { viewModel.commitNodeOrder() },
                                onClearInbox = { viewModel.clearInbox() },
                                onDismissInboxEvent = { eventId -> viewModel.dismissInboxEvent(eventId) },
                                onRefresh = { viewModel.refreshNow() },
                                onRestoreNode = { node -> viewModel.restoreNode(node.nodeId) }
                            )
                        }
                        composable("board") {
                            BoardScreen(
                                nodes = ui.board,
                                status = ui.status,
                                previews = ui.boardPreviews,
                                nodeNames = ui.nodeNames,
                                nodeNow = ui.nodeNow,
                                kanban = ui.kanban,
                                onOpenNode = { node ->
                                    viewModel.openNode(node)
                                    nav.navigate("node")
                                },
                                onRefresh = { viewModel.refreshBoard() },
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable("files") {
                            val browser = ui.browser
                            if (browser == null) {
                                // Same guard as "node": the Back handler is the ONLY thing allowed to
                                // pop this screen — an auto-pop here can double-pop the stack.
                                ConnectingView()
                            } else {
                                FileBrowserScreen(
                                    browser = browser,
                                    viewer = ui.viewer,
                                    git = ui.git,
                                    gitDiff = ui.gitDiff,
                                    onBack = {
                                        viewModel.closeFiles()
                                        nav.popBackStack()
                                    },
                                    onListDir = { viewModel.listDir(it) },
                                    onGoUp = { viewModel.browserGoUp() },
                                    onOpenFile = { entry -> viewModel.openFile(entry, browser.path) },
                                    onCloseViewer = { viewModel.closeViewer() },
                                    onOpenGit = { viewModel.openGit(it) },
                                    onRefreshGit = { viewModel.refreshGit() },
                                    onCloseGit = { viewModel.closeGit() },
                                    onOpenDiff = { viewModel.openGitDiff(it) },
                                    onBackFromDiff = { viewModel.backFromGitDiff() }
                                )
                            }
                        }
                        composable("node") {
                            val terminal = ui.terminal
                            if (terminal == null) {
                                // Terminal state was cleared (its Back button pops this screen, or a
                                // session teardown ran). Render a placeholder — NEVER auto-pop here:
                                // an async auto-pop races the Back handler's explicit popBackStack()
                                // and can pop below the start destination, emptying the back stack
                                // and blanking the whole UI (white screen).
                                ConnectingView()
                            } else {
                                NodeDetailScreen(
                                    state = terminal,
                                    nodeStatus = ui.status[terminal.nodeId] ?: com.nodeterm.android.core.model.NodeStatus.IDLE,
                                    nodeNow = ui.nodeNow[terminal.nodeId],
                                    onBack = {
                                        viewModel.closeTerminal()
                                        nav.popBackStack()
                                    },
                                    onSendInput = { viewModel.sendInput(it) },
                                    onScroll = { dir -> viewModel.scrollTerminal(dir) },
                                    onResize = { cols, rows -> viewModel.resizeTerminal(cols, rows) },
                                    onAnswer = { pendingId, decision ->
                                        viewModel.answerApproval(terminal.nodeId, pendingId, decision)
                                    }
                                )
                            }
                        }
                        composable("settings") {
                            SettingsScreen(
                                state = ui,
                                language = com.nodeterm.android.data.UiPrefsStore(this@MainActivity).language,
                                onLanguageChange = { code ->
                                    LocaleManager.setLocale(this@MainActivity, code)
                                    recreate()
                                },
                                onBack = { nav.popBackStack() },
                                onDisconnect = { viewModel.disconnect() },
                                onUnpair = {
                                    viewModel.unpair()
                                    nav.navigate("pairing") { popUpTo("home") { inclusive = true } }
                                },
                                onUpdateLanHost = { host -> viewModel.updateLanHost(host) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startDestination(phase: Phase): String = when (phase) {
        Phase.NO_SESSION -> "pairing"
        Phase.SAS_CONFIRM -> "sas"
        else -> "home"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm deep link: the running activity receives `nodeterm://pair?code=…` here.
        intent.pairCodeParam()?.let { code -> viewModel?.pairCode(code) }
        // Warm notification tap: open the node once its row is synced.
        intent.extraNodeId()?.let { pendingNodeId.value = it }
    }

    private fun Intent?.pairCodeParam(): String? {
        val code: String? = this?.data?.getQueryParameter("code")
        return code?.takeIf { it.isNotBlank() }
    }

    /** The nodeId carried by a tapped NEEDS-YOU notification, if any. */
    private fun Intent?.extraNodeId(): String? = this?.getStringExtra(NotificationHelper.EXTRA_NODE_ID)
}
