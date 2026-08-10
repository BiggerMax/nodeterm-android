package com.nodeterm.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeterm.android.notify.NotificationHelper

@Composable
fun SettingsScreen(
    state: RelayUiState,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit
) {
    val context = LocalContext.current
    var notifyEnabled by remember {
        mutableStateOf(NotificationHelper.hasPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifyEnabled = granted }

    // Real versionName from the package (AGP 8 disables BuildConfig by default — read it live).
    val versionName = remember {
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "0.1.0"
        } catch (_: Exception) {
            "0.1.0"
        }
    }
    val openLink: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))

        SectionTitle("Session")
        InfoRow("Connection", if (state.connected) "connected" else "disconnected")
        InfoRow("Channel SAS", if (state.sas.isBlank()) "—" else state.sas, monospace = true)
        // The transport this session rides on — relay URL or the free-tier LAN/SSH endpoint.
        if (state.relayEndpoint.isNotBlank()) {
            InfoRow(
                "Endpoint",
                state.relayEndpoint,
                monospace = true
            )
        }
        if (state.projects.isNotEmpty() || state.nodes.isNotEmpty()) {
            InfoRow("Host", "${state.projects.size} project${if (state.projects.size == 1) "" else "s"} · ${state.nodes.size} node${if (state.nodes.size == 1) "" else "s"}")
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        // Desktop parity: the host shows `⌘/` shortcuts — the phone's gestures are the same
        // muscle memory, mapped onto touch. Each row names the desktop action and the gesture.
        Spacer(Modifier.height(16.dp))
        SectionTitle("Shortcuts & gestures")
        ShortcutRow(Icons.Outlined.Search, "Jump anywhere (⌘K)", "Tap the search icon in the home header")
        ShortcutRow(Icons.Outlined.TouchApp, "Node actions (right-click)", "Long-press a node card")
        ShortcutRow(Icons.Outlined.CenterFocusStrong, "Focus a canvas node", "Double-tap it on the board")
        ShortcutRow(Icons.Outlined.SwapVert, "Scroll terminal history", "Swipe up / down in the terminal")
        ShortcutRow(Icons.Outlined.Mic, "Dictate (⌘⇧D)", "Tap the mic in a terminal — review, then send")
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        Spacer(Modifier.height(16.dp))
        SectionTitle("Notifications")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Host event notifications", fontSize = 14.sp)
                Text(
                    "Needs-you / done pushes from the host (requires a Firebase project wired in — see README).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = notifyEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notifyEnabled = enabled
                    }
                }
            )
        }
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()

        Spacer(Modifier.height(16.dp))
        SectionTitle("Connection")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect (keep pairing)")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onUnpair,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Unpair and reset")
        }
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()

        Spacer(Modifier.height(16.dp))
        SectionTitle("About")
        Spacer(Modifier.height(6.dp))
        InfoRow("nodeterm Android companion", "Version $versionName")
        LinkRow("nodeterm.dev", "https://nodeterm.dev", openLink)
        LinkRow("GitHub · eneskirca/nodeterm", "https://github.com/eneskirca/nodeterm", openLink)
        Text(
            "E2EE relay client — protocol docs in ANDROID_CLIENT_SPEC.md.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LinkRow(label: String, url: String, open: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open(url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** One row of the shortcuts reference: a small glyph + desktop action + how to do it on phone. */
@Composable
private fun ShortcutRow(icon: ImageVector, desktop: String, mobile: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(desktop, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                mobile,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}
