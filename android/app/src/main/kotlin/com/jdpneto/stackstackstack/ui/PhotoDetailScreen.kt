package com.jdpneto.stackstackstack.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jdpneto.stackstackstack.LibraryStore
import com.jdpneto.stackstackstack.ResultRenderer
import com.jdpneto.stackstackstack.StackRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// EditSource — everything the editor needs, loaded off-main before the sheet.
// ---------------------------------------------------------------------------

data class EditSource(
    val id: java.util.UUID,
    val originalData: ByteArray,
    val referenceData: ByteArray?,
    val adjustments: com.jdpneto.stackengine.ImageAdjustments,
    val previewBitmap: android.graphics.Bitmap?,
    val format: com.jdpneto.stackstackstack.ImageEncoder.Format
) {
    override fun equals(other: Any?) = other is EditSource && id == other.id
    override fun hashCode() = id.hashCode()
}

// ---------------------------------------------------------------------------
// PhotoDetailScreen
// ---------------------------------------------------------------------------

/**
 * Full-screen viewer for one saved stack. Zoom via transformable (Compose), rotate left/right
 * persisted as non-destructive [ImageAdjustments.quarterTurns], share via [FileProvider] +
 * [Intent.ACTION_SEND], edit via [EditorScreen], delete with confirmation.
 * Mirrors iOS [PhotoDetailView] 1:1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    record: StackRecord,
    store: LibraryStore,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (EditSource) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }

    // Load a screen-sized image off the main thread.
    LaunchedEffect(record.id) {
        bitmap = withContext(Dispatchers.IO) {
            decodeThumbnail(record.resultURL(store.root), maxPixel = 2048)
        }
        loaded = true
    }

    // Zoom + pan state (transformable).
    var scale by remember { mutableFloatStateOf(1f) }
    var panX  by remember { mutableFloatStateOf(0f) }
    var panY  by remember { mutableFloatStateOf(0f) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Done",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // Rotate left
                    IconButton(
                        onClick = { if (!rotating) rotate(record, store, delta = -1, scope, onChanged) { bmp -> bitmap = bmp; rotating = false }.also { rotating = true } },
                        enabled = !rotating,
                        modifier = Modifier
                            .testTag("rotate-left")
                            .semantics { contentDescription = "Rotate left" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Rotate90DegreesCcw,
                            contentDescription = "Rotate left",
                            tint = Color.White
                        )
                    }
                    // Rotate right
                    IconButton(
                        onClick = { if (!rotating) rotate(record, store, delta = 1, scope, onChanged) { bmp -> bitmap = bmp; rotating = false }.also { rotating = true } },
                        enabled = !rotating,
                        modifier = Modifier
                            .testTag("rotate-right")
                            .semantics { contentDescription = "Rotate right" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Rotate90DegreesCw,
                            contentDescription = "Rotate right",
                            tint = Color.White
                        )
                    }
                    // Share
                    IconButton(
                        onClick = {
                            val file = record.resultURL(store.root)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = if (record.format == "heic") "image/heic" else "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        },
                        modifier = Modifier
                            .testTag("share")
                            .semantics { contentDescription = "Share" }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                    }
                    // Edit
                    IconButton(
                        onClick = {
                            scope.launch {
                                val src = withContext(Dispatchers.IO) {
                                    loadEditSource(record, store)
                                }
                                src?.let(onEdit)
                            }
                        },
                        modifier = Modifier
                            .testTag("edit")
                            .semantics { contentDescription = "Edit" }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.White)
                    }
                    // Delete
                    IconButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier
                            .testTag("delete")
                            .semantics { contentDescription = "Delete" }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Stack photo",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = panX
                                translationY = panY
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 8f)
                                    panX += pan.x
                                    panY += pan.y
                                }
                            }
                    )
                }
                loaded -> {
                    Text("Couldn't load this photo.", color = Color.White)
                }
                else -> {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this stack?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    runCatching { store.delete(id = record.id) }
                    onChanged()
                    onDismiss()
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Persist a 90° rotation as a non-destructive [quarterTurns] adjustment: render the original
 * through the updated adjustments off-main, then save via [LibraryStore.applyEdit].
 * Mirrors iOS [PhotoDetailView.rotate(by:)].
 */
private fun rotate(
    record: StackRecord,
    store: LibraryStore,
    delta: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    onChanged: () -> Unit,
    onResult: (android.graphics.Bitmap?) -> Unit
) {
    scope.launch {
        val (newAdj, rendered) = withContext(Dispatchers.IO) {
            val original = store.originalData(record.id) ?: return@withContext null to null
            val adj = store.adjustments(record.id)
            val ref = if (adj.hasBlend) store.referenceData(record.id) else null
            // Add delta turns; ImageAdjustments.quarterTurns normalises to 0..3 via its setter.
            val newAdj = adj.copy().also { it.quarterTurns = adj.quarterTurns + delta }
            val rendered = ResultRenderer.render(
                originalData = original,
                adjustments = newAdj,
                quality = 0.95,
                format = record.encoderFormat,
                referenceData = ref
            )
            newAdj to rendered
        }
        if (newAdj != null && rendered != null) {
            // applyEdit is a write; call on main thread (MainActor equivalent).
            withContext(Dispatchers.Main) {
                runCatching { store.applyEdit(id = record.id, adjustments = newAdj, rendered = rendered) }
            }
            val bmp = BitmapFactory.decodeByteArray(rendered, 0, rendered.size)
            onResult(bmp)
            onChanged()
        } else {
            onResult(null)
        }
    }
}

/**
 * Load the original data, reference, adjustments, and a downscaled preview all off the main thread.
 * Returns null if the original can't be read.
 * Mirrors iOS [openEditor] loading logic in [PhotoDetailView].
 */
internal fun loadEditSource(record: StackRecord, store: LibraryStore): EditSource? {
    val original = store.originalData(record.id) ?: return null
    val ref  = store.referenceData(record.id)
    val adj  = store.adjustments(record.id)
    val fmt  = record.encoderFormat
    val prevBytes = ResultRenderer.render(
        originalData = original,
        adjustments  = adj,
        quality      = 0.85,
        maxPixel     = 1200,
        format       = fmt,
        referenceData = ref
    )
    val prevBitmap = prevBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    return EditSource(
        id            = record.id,
        originalData  = original,
        referenceData = ref,
        adjustments   = adj,
        previewBitmap = prevBitmap,
        format        = fmt
    )
}
