package com.jdpneto.stackstackstack.ui

import androidx.compose.foundation.clickable
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
 * The screen's single write path for the export format: persist to [AppSettings] AND push to the
 * live coordinator, the Android stand-in for iOS's `.onReceive(settings.$exportFormat)` mirror.
 * Extracted (internal) so Robolectric can exercise exactly what the radio button does.
 */
internal fun applyExportFormat(
    settings: AppSettings,
    coordinator: StackCaptureCoordinator,
    format: ImageEncoder.Format
) {
    settings.exportFormat    = format
    coordinator.exportFormat = format
}

/** Single write path for the Save-to-Photos toggle; see [applyExportFormat]. */
internal fun applySaveToPhotos(
    settings: AppSettings,
    coordinator: StackCaptureCoordinator,
    enabled: Boolean
) {
    settings.saveToPhotos            = enabled
    coordinator.saveToPhotosEnabled  = enabled
}

/**
 * Settings screen — 4 sections mirroring iOS [SettingsView]:
 * - Capture & Export (Save to Photos toggle + Format picker)
 * - Storage (stack count, space used, Delete All)
 * - This Device (RAW + Depth capability)
 * - About (version, Replay Introduction)
 *
 * Settings writes go through [applyExportFormat]/[applySaveToPhotos], which persist to
 * [AppSettings] and sync the live coordinator in one step (same effect as iOS's
 * `.onReceive(settings.$exportFormat)` mirroring — without restart-only dead wiring).
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

    // Mutable settings state (read once; writes go through applyExportFormat/applySaveToPhotos).
    var saveToPhotos  by remember { mutableStateOf(settings.saveToPhotos) }
    var exportFormat  by remember { mutableStateOf(settings.exportFormat) }

    // One reload routine for both the initial load and the post-delete refresh.
    suspend fun reloadStorageStats() {
        val (bytes, count) = withContext(Dispatchers.IO) {
            Pair(store.storageUsedBytes(), try { store.loadAll().size } catch (_: Exception) { 0 })
        }
        usedBytes  = bytes
        stackCount = count
    }

    LaunchedEffect(Unit) { reloadStorageStats() }

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
                            applySaveToPhotos(settings, coordinator, v)
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
                            applyExportFormat(settings, coordinator, ImageEncoder.Format.JPEG)
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
                            applyExportFormat(settings, coordinator, ImageEncoder.Format.HEIC)
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
            // Single clickable row (no duplicate TextButton — the row text itself acts).
            val deleteEnabled = (stackCount ?: 0) > 0
            ListItem(
                headlineContent = {
                    Text("Delete All Stacks", color = if (deleteEnabled) Color.Red else Color.Red.copy(alpha = 0.4f))
                },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = deleteEnabled) { confirmDeleteAll = true },
                trailingContent = null,
                supportingContent = deleteError?.let { { Text(it, color = Color.Red, fontSize = 11.sp) } }
            )

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
            // Single clickable row (no duplicate TextButton — the row text itself acts).
            ListItem(
                headlineContent = { Text("Replay Introduction", color = Color(0xFF0A84FF)) },
                colors = ListItemDefaults.colors(containerColor = Color(0xFF2C2C2E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onReplayOnboarding() }
            )
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
                    scope.launch { reloadStorageStats() }
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
