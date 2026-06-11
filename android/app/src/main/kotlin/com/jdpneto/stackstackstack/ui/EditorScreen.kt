package com.jdpneto.stackstackstack.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdpneto.stackengine.CropAspect
import com.jdpneto.stackengine.ImageAdjustments
import com.jdpneto.stackstackstack.LibraryStore
import com.jdpneto.stackstackstack.ResultRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Non-destructive tonal editor. Mirrors iOS [EditorView] 1:1:
 * - Blend slider only when a reference frame was stored (long-exposure looks).
 * - All 7 tonal sliders + Straighten + Crop aspect picker.
 * - Off-main 1200 px preview rendered on slider release (same debounce model as iOS schedulePreview).
 * - Save renders at 0.95 quality, calls [LibraryStore.applyEdit], returns bytes to caller.
 * - Cancel closes without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    editSource: EditSource,
    store: LibraryStore,
    onSaved: (ByteArray) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    // Mutable adjustment state — copied from the incoming record.
    var adj by remember {
        mutableStateOf(editSource.adjustments.copy())
    }

    // Preview bitmap — initialised from the pre-loaded preview, updated on slider release.
    var previewBitmap by remember {
        mutableStateOf(editSource.previewBitmap)
    }
    var isSaving by remember { mutableStateOf(false) }
    var renderJob: Job? by remember { mutableStateOf(null) }

    // Render a new preview when adj changes (debounced: launched on slider release).
    fun schedulePreview() {
        renderJob?.cancel()
        val snapshot = adj.copy()
        val origData = editSource.originalData
        val refData  = editSource.referenceData
        val fmt      = editSource.format
        renderJob = scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                ResultRenderer.render(
                    originalData  = origData,
                    adjustments   = snapshot,
                    quality       = 0.85,
                    maxPixel      = 1200,
                    format        = fmt,
                    referenceData = refData
                )
            }
            bytes?.let { previewBitmap = BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }

    // Kick off an initial render if the pre-loaded preview is missing.
    LaunchedEffect(Unit) {
        if (previewBitmap == null) schedulePreview()
    }

    DisposableEffect(Unit) {
        onDispose { renderJob?.cancel() }
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Edit", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                navigationIcon = {
                    TextButton(onClick = onDismiss, enabled = !isSaving) {
                        Text("Cancel", color = Color.White)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (isSaving) return@TextButton
                            isSaving = true
                            val snapshot = adj.copy()
                            val origData = editSource.originalData
                            val refData  = editSource.referenceData
                            val fmt      = editSource.format
                            val id       = editSource.id
                            scope.launch {
                                val rendered = withContext(Dispatchers.Default) {
                                    ResultRenderer.render(
                                        originalData  = origData,
                                        adjustments   = snapshot,
                                        quality       = 0.95,
                                        format        = fmt,
                                        referenceData = refData
                                    )
                                }
                                isSaving = false
                                if (rendered == null) {
                                    snackbarHost.showSnackbar("Couldn't save the edit. Please try again.")
                                    return@launch
                                }
                                val saved = runCatching {
                                    withContext(Dispatchers.Main) {
                                        store.applyEdit(id = id, adjustments = snapshot, rendered = rendered)
                                    }
                                }
                                if (saved.isFailure) {
                                    snackbarHost.showSnackbar("Couldn't save the edit. Please try again.")
                                    return@launch
                                }
                                onSaved(rendered)
                                onDismiss()
                            }
                        },
                        enabled = !isSaving
                    ) { Text("Save", color = if (isSaving) Color.Gray else Color.White) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Blend slider — only when a reference frame exists (long-exposure looks).
            if (editSource.referenceData != null) {
                EditorSlider(
                    label = "Blend",
                    value = adj.blendStrength,
                    range = 0f..1f,
                    onValueChangeFinished = { v ->
                        adj = adj.copy().also { it.blendStrength = v }
                        schedulePreview()
                    }
                )
            }

            EditorSlider("Exposure", adj.exposureEV, -2f..2f) { v ->
                adj = adj.copy().also { it.exposureEV = v }
                schedulePreview()
            }
            EditorSlider("Contrast", adj.contrast, -1f..1f) { v ->
                adj = adj.copy().also { it.contrast = v }
                schedulePreview()
            }
            EditorSlider("Warmth", adj.temperature, -1f..1f) { v ->
                adj = adj.copy().also { it.temperature = v }
                schedulePreview()
            }
            EditorSlider("Tint", adj.tint, -1f..1f) { v ->
                adj = adj.copy().also { it.tint = v }
                schedulePreview()
            }
            EditorSlider("Shadows", adj.shadows, -1f..1f) { v ->
                adj = adj.copy().also { it.shadows = v }
                schedulePreview()
            }
            EditorSlider("Highlights", adj.highlights, -1f..1f) { v ->
                adj = adj.copy().also { it.highlights = v }
                schedulePreview()
            }
            EditorSlider("Straighten", adj.straightenDegrees, -15f..15f) { v ->
                adj = adj.copy().also { it.straightenDegrees = v }
                schedulePreview()
            }

            // Crop aspect picker (segmented control equivalent).
            Text("Crop", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                for (aspect in CropAspect.entries) {
                    val selected = adj.cropAspect == aspect
                    TextButton(
                        onClick = {
                            adj = adj.copy().also { it.cropAspect = aspect }
                            schedulePreview()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            aspect.shortLabel,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Short label for the crop aspect picker. Mirrors iOS [CropAspect.shortLabel]. */
val CropAspect.shortLabel: String
    get() = when (this) {
        CropAspect.ORIGINAL      -> "Original"
        CropAspect.SQUARE        -> "Square"
        CropAspect.FOUR_THREE    -> "4:3"
        CropAspect.SIXTEEN_NINE  -> "16:9"
    }

@Composable
private fun EditorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (Float) -> Unit
) {
    var sliderVal by remember(value) { mutableFloatStateOf(value) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(0.25f))
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChangeFinished(sliderVal) },
            valueRange = range,
            modifier = Modifier.weight(0.75f)
        )
    }
}
