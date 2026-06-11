package com.jdpneto.stackstackstack.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jdpneto.stackstackstack.ImageDecoder
import com.jdpneto.stackstackstack.LibraryStore
import com.jdpneto.stackstackstack.StackRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gallery grid — mirrors iOS [GalleryView].
 * Loads thumbnails off the main thread (via [LaunchedEffect] + [Dispatchers.IO]) and shows a
 * 3-column adaptive grid. Tapping a cell calls [onSelect] which the root routes to [PhotoDetailScreen].
 */
@Composable
fun GalleryScreen(
    store: LibraryStore,
    onSelect: (StackRecord) -> Unit,
    refreshKey: Int = 0     // bump this from the root to force a reload after edit/delete
) {
    var records by remember { mutableStateOf<List<StackRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        records = withContext(Dispatchers.IO) {
            try { store.loadAll() } catch (_: Exception) { emptyList() }
        }
        loaded = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            loaded && records.isEmpty() -> EmptyGallery()
            else -> GalleryGrid(records, store, onSelect)
        }
    }
}

@Composable
private fun EmptyGallery() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No stacks yet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Capture one from the Capture tab.",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun GalleryGrid(
    records: List<StackRecord>,
    store: LibraryStore,
    onSelect: (StackRecord) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(records, key = { it.id }) { rec ->
            ThumbnailCell(
                file = rec.resultURL(store.root),
                versionKey = rec.updatedAtAppleEpoch ?: rec.createdAtAppleEpoch,
                onClick = { onSelect(rec) },
                modifier = Modifier.testTag("stack-${rec.id}")
            )
        }
    }
}

/**
 * A single gallery cell that loads a downsampled thumbnail off the main thread.
 * [versionKey] is the record's `updatedAt` so an edit (which bumps the version) re-loads the cell.
 * Mirrors iOS [ThumbnailCell].
 */
@Composable
private fun ThumbnailCell(
    file: File,
    versionKey: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(file.path, versionKey) {
        bitmap = withContext(Dispatchers.IO) {
            decodeThumbnail(file, maxPixel = 240)
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color(0xFF262626))
            .clickable(onClick = onClick)
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * In-memory cache for decoded gallery/detail bitmaps, sized to 1/8 of the max heap (the standard
 * LruCache recipe), so scrolling back through the grid doesn't re-decode every cell from disk.
 * Keys are `path@lastModified#maxPixel` — an edit rewrites the file (new lastModified), which
 * naturally invalidates the stale entry.
 */
private val thumbnailCache: LruCache<String, android.graphics.Bitmap> by lazy {
    val maxKB = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()
    object : LruCache<String, android.graphics.Bitmap>(maxKB) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            value.byteCount / 1024
    }
}

/**
 * Decode a JPEG/HEIC file to a [android.graphics.Bitmap] downscaled to at most [maxPixel]
 * on the long edge. Never upscales. The downsample policy is owned by [ImageDecoder.decodeFile]
 * (streaming decode — no whole-file byte copy); results are memoized in [thumbnailCache].
 * Mirrors iOS [Thumbnailer.load].
 */
internal fun decodeThumbnail(file: File, maxPixel: Int): android.graphics.Bitmap? {
    if (!file.exists()) return null
    val key = "${file.path}@${file.lastModified()}#$maxPixel"
    thumbnailCache.get(key)?.let { return it }
    val bitmap = try { ImageDecoder.decodeFile(file.path, maxPixel) } catch (_: Exception) { null }
    if (bitmap != null) thumbnailCache.put(key, bitmap)
    return bitmap
}
