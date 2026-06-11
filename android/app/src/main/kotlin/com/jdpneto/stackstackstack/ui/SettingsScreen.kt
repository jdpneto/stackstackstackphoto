package com.jdpneto.stackstackstack.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdpneto.stackstackstack.AppSettings
import com.jdpneto.stackstackstack.CoordinatorUiState
import com.jdpneto.stackstackstack.ImageEncoder
import com.jdpneto.stackstackstack.LibraryStore
import com.jdpneto.stackstackstack.StackCaptureCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen — 4 sections mirroring iOS [SettingsView]:
 * - Capture & Export (Save to Photos toggle + Format picker)
 * - Storage (stack count, space used, Delete All)
 * - This Device (RAW + Depth capability)
 * - About (version, Replay Introduction)
 *
 * Settings are written through [AppSettings]; the coordinator is synced via the app root's
 * [LaunchedEffect] listeners (same `.onReceive(settings.$exportFormat)` pattern as iOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    coordinator: StackCaptureCoordinator,
    store: LibraryStore,
    onReplayOnboarding: () -> Unit
) {
    val uiState by coordinator.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var stackCount  by remember { mutableStateOf<Int?>(null) }
    var usedBytes   by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    // Mutable settings state (read once; writes go through AppSettings).
    var saveToPhotos  by remember { mutableStateOf(settings.saveToPhotos) }
    var exportFormat  by remember { mutableStateOf(settings.exportFormat) }

    LaunchedEffect(Unit) {
        val (bytes, count) = withContext(Dispatchers.IO) {
            Pair(store.storageUsedBytes(), try { store.loadAll().size } catch (_: Exception) { 0 })
        }
        usedBytes  = bytes
        stackCount = count
    }

    Scaffold(
        containerColor = Color(0xFF1C1C1E),
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1C1C1E))
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Capture & Export ─────────────────────────────────────────────
            SectionHeader("Capture & Export")
            ListItem(
                headlineContent = { Text("Save to Photos", color = Color.White) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                trailingContent = {
                    Switch(
                        checked = saveToPhotos,
                        onCheckedChange = { v ->
                            saveToPhotos = v
                            settings.saveToPhotos = v
                        }
                    )
                }
            )
            ListItem(
                headlineContent = { Text("Format: JPEG", color = Color.White) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                trailingContent = {
                    RadioButton(
                        selected = exportFormat == ImageEncoder.Format.JPEG,
                        onClick = {
                            exportFormat = ImageEncoder.Format.JPEG
                            settings.exportFormat = ImageEncoder.Format.JPEG
                        }
                    )
                }
            )
            Divider(color = Color.White.copy(alpha = 0.1f))
            ListItem(
                headlineContent = { Text("Format: HEIC", color = Color.White) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                trailingContent = {
                    RadioButton(
                        selected = exportFormat == ImageEncoder.Format.HEIC,
                        onClick = {
                            exportFormat = ImageEncoder.Format.HEIC
                            settings.exportFormat = ImageEncoder.Format.HEIC
                        }
                    )
                }
            )

            // ── Storage ──────────────────────────────────────────────────────
            SectionHeader("Storage")
            ListItem(
                headlineContent = { Text("Stacks", color = Color.White) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                trailingContent = { Text(stackCount?.toString() ?: "…", color = Color.White.copy(alpha = 0.7f)) }
            )
            ListItem(
                headlineContent = { Text("Space Used", color = Color.White) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                trailingContent = {
                    Text(
                        usedBytes?.let { formatBytes(it) } ?: "…",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            )
            ListItem(
                headlineContent = { Text("Delete All Stacks", color = Color.Red) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                modifier = Modifier.fillMaxWidth(),
                trailingContent = null,
                supportingContent = deleteError?.let { { Text(it, color = Color.Red, fontSize = 11.sp) } }
            )
            TextButton(
                onClick = { confirmDeleteAll = true },
                enabled = (stackCount ?: 0) > 0,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("Delete All Stacks", color = Color.Red) }

            // ── This Device ──────────────────────────────────────────────────
            SectionHeader("This Device")
            DeviceRow("RAW Capture", if (uiState.supportsRAW) "Supported" else "Not supported")
            DeviceRow("Depth (Manual Focus)", if (uiState.supportsDepth) "Supported" else "Not supported")

            // ── About ────────────────────────────────────────────────────────
            SectionHeader("About")
            ListItem(
                headlineContent = { Text("Version", color = Color.White) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                trailingContent = { Text(versionString(context), color = Color.White.copy(alpha = 0.7f)) }
            )
            ListItem(
                headlineContent = { Text("Replay Introduction", color = Color(0xFF0A84FF)) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                modifier = Modifier.fillMaxWidth()
                    .let { m -> m }
            )
            TextButton(
                onClick = onReplayOnboarding,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("Replay Introduction") }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all stacks? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    val err = runCatching { store.deleteAll() }.exceptionOrNull()
                    deleteError = err?.message
                    scope.launch {
                        val (bytes, count) = withContext(Dispatchers.IO) {
                            Pair(store.storageUsedBytes(), try { store.loadAll().size } catch (_: Exception) { 0 })
                        }
                        usedBytes  = bytes
                        stackCount = count
                    }
                }) { Text("Delete All", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun DeviceRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label, color = Color.White) },
        colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
        trailingContent = { Text(value, color = Color.White.copy(alpha = 0.7f)) }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L     -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L         -> "%.1f KB".format(bytes / 1_000.0)
    else                    -> "$bytes B"
}

private fun versionString(context: android.content.Context): String {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: Exception) { "?" }
}
