package com.jdpneto.stackstackstack

import com.jdpneto.stackengine.CFAPattern
import com.jdpneto.stackengine.RawSensorFrame
import com.jdpneto.stackengine.Vec3
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Disk spool for in-burst RAW frames (device finding, Pixel 10 Pro): holding every converted
 * [RawSensorFrame] in memory for the whole burst put a 30-frame fast burst at ~25 MB × 30 ≈
 * 750 MB — past the 512 MB largeHeap ceiling, OOM at ~frame 21. (iOS holds all 30 too, but has a
 * ~3 GB per-app ceiling.) Instead each frame is written to a per-burst spool dir as it converts
 * and re-read lazily during processing.
 *
 * File format (version [MAGIC], all little-endian):
 *   int    magic
 *   int    width, int height
 *   float  blackLevel, float whiteLevel
 *   int    cfa ordinal
 *   float  wbGains r, g, b
 *   float  colorMatrix[9] (column-major, engine layout)
 *   short  mosaic[width*height]
 *
 * IO is chunked ([CHUNK_BYTES]) so a spool read/write never allocates a second mosaic-sized
 * buffer next to the mosaic itself.
 */
object BurstSpool {

    /** Format magic/version ("SSR1"). Bump on any layout change. */
    private const val MAGIC = 0x53535231

    private const val HEADER_BYTES = 4 + 4 + 4 + 4 + 4 + 4 + 12 + 36
    private const val CHUNK_BYTES = 1 shl 20   // 1 MiB

    /** Write [frame] to [file] (creating parent dirs). Throws [IOException] on failure. */
    fun write(file: File, frame: RawSensorFrame) {
        file.parentFile?.mkdirs()
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { channel ->
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(MAGIC)
            header.putInt(frame.width)
            header.putInt(frame.height)
            header.putFloat(frame.blackLevel)
            header.putFloat(frame.whiteLevel)
            header.putInt(frame.cfa.ordinal)
            header.putFloat(frame.wbGains.x)
            header.putFloat(frame.wbGains.y)
            header.putFloat(frame.wbGains.z)
            for (i in 0 until 9) header.putFloat(frame.colorMatrix[i])
            header.flip()
            while (header.hasRemaining()) channel.write(header)

            val chunk = ByteBuffer.allocate(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var off = 0
            while (off < frame.mosaic.size) {
                val n = minOf(frame.mosaic.size - off, CHUNK_BYTES / 2)
                chunk.clear()
                chunk.asShortBuffer().put(frame.mosaic, off, n)
                chunk.limit(n * 2)
                while (chunk.hasRemaining()) channel.write(chunk)
                off += n
            }
        }
    }

    /** Read a frame back. Throws [IOException] on a missing/truncated/foreign file. */
    fun read(file: File): RawSensorFrame {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            readFully(channel, header)
            header.flip()
            val magic = header.int
            if (magic != MAGIC) throw IOException("not a burst-spool frame: $file")
            val width  = header.int
            val height = header.int
            val black  = header.float
            val white  = header.float
            val cfaOrd = header.int
            val cfa = CFAPattern.values().getOrNull(cfaOrd)
                ?: throw IOException("bad CFA ordinal $cfaOrd in $file")
            val wb = Vec3(header.float, header.float, header.float)
            val matrix = FloatArray(9) { header.float }

            val count = width * height
            if (count <= 0) throw IOException("bad dimensions ${width}x$height in $file")
            val mosaic = ShortArray(count)
            val chunk = ByteBuffer.allocate(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var off = 0
            while (off < count) {
                val n = minOf(count - off, CHUNK_BYTES / 2)
                chunk.clear()
                chunk.limit(n * 2)
                readFully(channel, chunk)
                chunk.flip()
                chunk.asShortBuffer().get(mosaic, off, n)
                off += n
            }
            return RawSensorFrame(
                width = width, height = height, mosaic = mosaic,
                blackLevel = black, whiteLevel = white, cfa = cfa,
                wbGains = wb, colorMatrix = matrix
            )
        }
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("truncated burst-spool frame")
        }
    }

    /**
     * Delete every spool dir under [root] except [keep]. Used by the service at each burst arm
     * to reclaim ITS OWN previous generations — [root] must be the calling instance's private
     * spool namespace, never the shared root (another instance's spool may still be lazily read
     * by an orphaned processing job; see [Camera2CaptureService]'s `instanceSpoolRoot` kdoc).
     */
    fun clearSpools(root: File, keep: File? = null) {
        root.listFiles()?.forEach { entry ->
            if (keep == null || entry.name != keep.name) entry.deleteRecursively()
        }
    }

    /**
     * AGE-GATED sweep of the SHARED spool root: delete only entries not modified within
     * [maxAgeMs] — crash leftovers by definition (a live burst writes for seconds and its
     * processing job reads for minutes). Young entries are presumed to belong to a live service
     * instance (possibly a PREVIOUS one whose processing job survived activity recreation and is
     * still reading its spool) and are left alone; a later app start reclaims them. A dir's
     * lastModified updates when its direct children change — i.e. at burst arm — so a live
     * instance's namespace is always young while it can still matter. [nowMs] is injectable for
     * tests.
     */
    fun sweepStaleSpools(root: File, maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        root.listFiles()?.forEach { entry ->
            if (nowMs - entry.lastModified() > maxAgeMs) entry.deleteRecursively()
        }
    }

    /**
     * A fixed-size, disk-backed `List<RawSensorFrame>` over spool files.
     *
     * RESIDENCY CONTRACT: this list holds NO frame data — every [get] re-reads and decodes its
     * file (~25 MB, tens of ms on UFS), and the caller owns the returned frame's lifetime. The
     * engine consumes it within that contract: `reduceStreamingWithReference` indexes one frame
     * at a time (peak ≈ 1 mosaic), and `developedFrames`' bounded parallelMap loads ≤ 4 at once
     * (≈ 100 MB transient). Random access from multiple threads is safe (each read opens its own
     * channel); a [get] after the spool dir is deleted throws [IOException] — the service only
     * clears spools when no processing can still be consuming them (the coordinator's shutter
     * gate serializes bursts behind processing).
     */
    class LazyFrameList(private val files: List<File>) : AbstractList<RawSensorFrame>() {
        override val size: Int get() = files.size
        override fun get(index: Int): RawSensorFrame = read(files[index])
    }
}
