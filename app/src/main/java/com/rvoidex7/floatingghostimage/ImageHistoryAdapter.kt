package com.rvoidex7.floatingghostimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import java.io.BufferedInputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class ImageHistoryAdapter(
    private val listView: ListView,
    context: Context,
    private val items: List<String>,
    private val onItemClick: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private val contentResolver = context.contentResolver
    private val density = context.resources.displayMetrics.density

    // Fixed fisheye tuning (final values; the debug panel is gone). The list
    // always grows toward the scroll peak (linear down to MIN), sizes images to
    // their aspect, and keeps a constant image-to-image gap of SPACING_DP.

    // Memory-effective image cache.
    private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val exifCache = object : LruCache<String, Int>(64) {
        override fun sizeOf(key: String, value: Int): Int = 1
    }
    private val aspectCache = object : LruCache<String, Float>(128) {
        override fun sizeOf(key: String, value: Float): Int = 1
    }
    // Three workers instead of one: the thumbnails visible together decode
    // concurrently instead of waiting on a serial queue. Bounded pool — decode
    // stays fast and background work stays predictable; the LRU thumb cache
    // keeps repeat decodes of the same image at zero.
    private val executor = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        /**
         * Fisheye scale bounds: size grows to MAX on the peak and shrinks to MIN
         * one span away. MAX and MIN are the fixed extremes of the range.
         */
        const val MAX_SCALE = 1.30f
        const val MIN_SCALE = 0.70f
        /**
         * How many travel-lengths it takes to fall from MAX to MIN: the distance TO
         * the peak is spread over this many multiples of the peak's travel extent,
         * so 1.2 keeps rows near the edges of the screen visibly sized.
         */
        const val SPAN_MULTIPLIER = 1.20f
        const val BASE_DP = 150f
        const val LONG_CLAMP_DP = 240f
        const val SHORT_CLAMP_DP = 110f
        const val SPACING_DP = 15f
    }

    /**
     * Rectangle of equal area around BASE_DP for a given aspect ratio.
     * Long/short edges are derived from √r so every image occupies the same
     * screen area (no cropping, no deformation), then clamped. Falls back to a
     * BASE_DP×BASE_DP square for an unknown/zero aspect.
     */
    fun boxSizePx(aspect: Float): Pair<Int, Int> {
        val r = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val m = if (r >= 1f) r else 1f / r
        val longDp = (BASE_DP * sqrt(m)).coerceAtMost(LONG_CLAMP_DP)
        val shortDp = (BASE_DP / sqrt(m)).coerceAtLeast(SHORT_CLAMP_DP)
        val wDp = if (r >= 1f) longDp else shortDp
        val hDp = if (r >= 1f) shortDp else longDp
        return (wDp * density).toInt().coerceAtLeast(1) to (hDp * density).toInt().coerceAtLeast(1)
    }

    /**
     * Representative resting row height, averaged over all items. Rows keep a
     * stable layout height (imageH + spacing) — the fisheye moves their visual
     * centres via translations, never their layout — so the average stays fixed.
     */
    val averageRowHeightPx: Int
        get() {
            if (items.isEmpty()) return (BASE_DP * density).toInt() + (SPACING_DP * density).toInt()
            var sum = 0f
            for (uri in items) {
                sum += boxSizePx(aspectCache.get(uri) ?: 1f).second
            }
            return (sum / items.size).toInt() + (SPACING_DP * density).toInt()
        }

    // Decode just large enough for the biggest grown display size (clamped long
    // edge × peak scale), so a grown rectangle never upscales a too-small bitmap.
    private val reqSizePx: Int
        get() = (LONG_CLAMP_DP * MAX_SCALE * density).toInt().coerceAtLeast(320)

    /**
     * Grows/shrinks the thumbnail itself (a pure scale transform, not layout
     * params), sized by the row's distance to the peak [peakY]. The scale reaches
     * MAX on the peak and MIN one span away.
     *
     * To keep the edge-to-edge gap between two neighbouring images EXACTLY equal
     * to the configured spacing at every scale, each row's visual centre is also
     * shifted via a translation (never layout). The visual pitch between two
     * neighbours must satisfy:
     *
     *     visualPitch = imageH·sᵢ/2 + spacing + imageH·sᵢ₊₁/2
     *
     * The layout pitch is imageH + spacing, so the rows gently spread apart where
     * images grow and contract where they shrink — the whitespace itself never
     * changes. Translations are pure transforms: the ListView never re-measures
     * mid-scroll (no stutter), and the whole visible block is re-centred each
     * frame so the reflow never drifts off the screen.
     */
    fun applyPeakScale(peakY: Float, top: Float, bottom: Float) {
        val count = listView.childCount
        val rows = ArrayList<ScaledRow>(count)
        val span = (bottom - top).coerceAtLeast(1f) * SPAN_MULTIPLIER
        for (i in 0 until count) {
            val row = listView.getChildAt(i) ?: continue
            val holder = row.tag as? ViewHolder ?: continue
            val centerY = row.top + row.height / 2f
            val distance = abs(centerY - peakY)
            val t = (distance / span).coerceIn(0f, 1f)
            val scale = MAX_SCALE - (MAX_SCALE - MIN_SCALE) * t
            rows.add(ScaledRow(row, holder, centerY, scale))
        }
        if (rows.isEmpty()) return
        val spacingPx = SPACING_DP * density
        // Visual centre of the first visible row anchors the block.
        val visualCenter = FloatArray(rows.size)
        visualCenter[0] = rows[0].layoutCenter
        for (i in 1 until rows.size) {
            val prev = rows[i - 1]
            val cur = rows[i]
            val prevHalf = prev.imageHeightPx * prev.scale / 2f
            val curHalf = cur.imageHeightPx * cur.scale / 2f
            visualCenter[i] = visualCenter[i - 1] + prevHalf + spacingPx + curHalf
        }
        // Re-centre the whole visible block so accumulated shrink/grow reflow
        // never drags the strip off-screen: mean(visual) == mean(layout).
        var vis = 0f
        var lay = 0f
        for (i in rows.indices) {
            vis += visualCenter[i]
            lay += rows[i].layoutCenter
        }
        val shift = (lay - vis) / rows.size
        for (i in rows.indices) {
            val r = rows[i]
            r.row.translationY = visualCenter[i] + shift - r.layoutCenter
            r.holder.applyThumbScale(r.scale)
        }
    }

    private class ScaledRow(
        val row: View,
        val holder: ViewHolder,
        val layoutCenter: Float,
        val scale: Float
    ) {
        val imageHeightPx: Float get() = holder.imageHeightPx
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): String = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val holder: ViewHolder
        val row: View
        if (convertView == null) {
            row = inflater.inflate(R.layout.item_image_history, parent, false)
            holder = ViewHolder(row)
            row.tag = holder
        } else {
            row = convertView
            @Suppress("UNCHECKED_CAST")
            holder = convertView.tag as ViewHolder
        }
        holder.bind(items[position])
        return row
    }

    private inner class ViewHolder(row: View) {
        private val row: View = row
        private val thumb: ImageView = row.findViewById(R.id.imgItemThumb)
        private var currentUri: String? = null

        /** Resting (unscaled) thumb height in px, applied by [applyAspectSize]. */
        private var restHeightPx: Float = 0f

        /** Unscaled image height, used by the gap-stable lens in applyPeakScale(). */
        val imageHeightPx: Float
            get() = if (restHeightPx > 0f) restHeightPx else BASE_DP * density

        init {
            row.setOnClickListener { currentUri?.let(onItemClick) }
        }

        fun bind(uriString: String) {
            currentUri = uriString
            // Recycled row: drop the scale and translation it carried from its
            // previous position; applyPeakScale() re-derives both from the scroll
            // position on the next frame.
            applyThumbScale(1f)
            row.translationY = 0f
            applyAspectSize()

            val cached = thumbCache.get(uriString)
            if (cached != null) {
                thumb.setImageBitmap(cached)
                thumb.background = null
            } else {
                // Loading placeholder: the gray frame outlines the exact box the
                // thumbnail will fill (background always hugs the view bounds);
                // the gallery icon sits centred inside. Both are replaced once
                // the async decode lands.
                thumb.setImageResource(android.R.drawable.ic_menu_gallery)
                thumb.setBackgroundResource(R.drawable.thumb_frame)
                loadThumbAsync(uriString)
            }
        }

        /**
         * Spans the ImageView to the aspect-aware base rectangle (same area for
         * every image, clamped for extremes) and keeps a constant resting gap band
         * (spacing/2 on each side). That padding is the lens's layout anchor: it
         * makes the resting layout pitch equal the resting visual pitch
         * (imageH + spacing), so the per-frame re-centring in applyPeakScale()
         * stays offset-free and the safe-area extremes keep the grown first/last
         * rows pinned to the status-bar and "+" button lines. Without it the rows
         * pile a static spacing offset into the mean, and the extremes drift.
         * The dynamic re-arrangement itself happens via transforms, never here.
         */
        private fun applyAspectSize() {
            val uriString = currentUri ?: return
            val aspect = aspectCache.get(uriString) ?: 1f
            val (w, h) = boxSizePx(aspect)
            restHeightPx = h.toFloat()
            val lp = thumb.layoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                thumb.layoutParams = lp
            }
            val padV = ((SPACING_DP * density) / 2f).toInt()
            if (padV != row.paddingTop || padV != row.paddingBottom) {
                row.setPadding(row.paddingLeft, padV, row.paddingRight, padV)
            }
        }

        /**
         * Scales the thumbnail around its own centre via a pure view transform.
         * The gap-stable lens in applyPeakScale() pushes each row (translationY)
         * so the edge-to-edge whitespace between neighbouring images stays exactly
         * SPACING_DP at every scale — the size changes, never the gap. Pure
         * transforms throughout: the ListView never re-measures mid-scroll.
         */
        fun applyThumbScale(scale: Float) {
            thumb.scaleX = scale
            thumb.scaleY = scale
        }

        private fun loadThumbAsync(uriString: String) {
            executor.execute {
                // Early aspect: a bounds-only read (just the file header) gives
                // the true displayed aspect before the pixel decode even starts,
                // so a cold-start row carries a correctly shaped frame from the
                // first frame instead of a square that snaps into shape later.
                // The decode below stays the only pixel pass for this image.
                val uri = Uri.parse(uriString)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val orientation = exifCache.get(uriString) ?: run {
                    readExifOrientation(uri).also { exifCache.put(uriString, it) }
                }
                val rotated = orientation in 5..8
                val bw = if (rotated) bounds.outHeight else bounds.outWidth
                val bh = if (rotated) bounds.outWidth else bounds.outHeight
                if (bw > 0 && bh > 0) aspectCache.put(uriString, bw.toFloat() / bh)
                handler.post {
                    if (currentUri != uriString) return@post
                    applyAspectSize()
                }
                val bitmap = decodeThumb(uriString) ?: return@execute
                thumbCache.put(uriString, bitmap)
                handler.post {
                    if (currentUri != uriString) return@post
                    thumb.setImageBitmap(bitmap)
                    // The frame is a loading cue only: drop it once the image lands.
                    thumb.background = null
                    // Authoritative aspect from the decoded pixels; re-span.
                    aspectCache.put(uriString, bitmap.width.toFloat() / bitmap.height)
                    applyAspectSize()
                }
            }
        }

        private fun decodeThumb(uriString: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriString)
                // API 28+ decodes, samples and applies EXIF orientation itself;
                // older devices keep the hand-rolled BitmapFactory + EXIF path.
                if (Build.VERSION.SDK_INT >= 28) decodeThumbModern(uri)
                else decodeThumbLegacy(uri)
            } catch (_: Throwable) {
                null
            }
        }

        /** API 28+: ImageDecoder handles sampling, colour and EXIF rotation. */
        @RequiresApi(Build.VERSION_CODES.P)
        private fun decodeThumbModern(uri: Uri): Bitmap? {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Mirror the legacy sampling rule: halve until one edge would
                // drop below the required grown display size.
                var sample = 1
                while (info.size.width / (sample * 2) >= reqSizePx
                    && info.size.height / (sample * 2) >= reqSizePx
                ) {
                    sample *= 2
                }
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }

        /** API 26-27 fallback: manual EXIF rotation + BitmapFactory sampling. */
        private fun decodeThumbLegacy(uri: Uri): Bitmap? {
            val uriString = uri.toString()
            val orientation = exifCache.get(uriString) ?: run {
                readExifOrientation(uri).also { exifCache.put(uriString, it) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= reqSizePx
                && bounds.outHeight / (sampleSize * 2) >= reqSizePx
            ) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            return applyOrientation(decoded, orientation)
        }

        /**
         * BitmapFactory ignores EXIF, so portrait/mirrored photos come out rotated.
         * Minimal dependency-free JPEG EXIF reader for the Orientation tag (0x0112).
         * Non-JPEG or malformed data returns 1 (normal) — never crashes.
         */
        private fun readExifOrientation(uri: Uri): Int {
            return try {
                val stream = contentResolver.openInputStream(uri) ?: return 1
                stream.use {
                    readJpegOrientation(BufferedInputStream(it, 8192))
                }
            } catch (_: Throwable) {
                1
            }
        }

        private fun readJpegOrientation(input: java.io.InputStream): Int {
            if (input.read() != 0xFF || input.read() != 0xD8) return 1
            while (true) {
                var b = input.read()
                if (b != 0xFF) return 1
                while (b == 0xFF) b = input.read()
                when (b) {
                    -1, 0x01, 0xD8 -> return 1
                }
                if (b in 0xD0..0xD9 || b == 0xDA) return 1
                val len = (input.read() shl 8) or input.read()
                if (len < 2) return 1
                val payloadSize = len - 2
                if (b == 0xE1) {
                    val payload = ByteArray(payloadSize)
                    if (input.read(payload) != payloadSize) return 1
                    if (payload.size >= 6 && payload[0] == 'E'.code.toByte()
                        && payload[1] == 'x'.code.toByte() && payload[2] == 'i'.code.toByte()
                        && payload[3] == 'f'.code.toByte() && payload[4] == 0.toByte()
                        && payload[5] == 0.toByte()
                    ) {
                        return parseExifOrientation(payload, 6)
                    }
                } else if (input.skip(payloadSize.toLong()) != payloadSize.toLong()) {
                    return 1
                }
            }
        }

        private fun parseExifOrientation(data: ByteArray, off: Int): Int {
            if (off + 8 > data.size) return 1
            val little = data[off] == 'I'.code.toByte() && data[off + 1] == 'I'.code.toByte()
            val big = data[off] == 'M'.code.toByte() && data[off + 1] == 'M'.code.toByte()
            if (!little && !big) return 1
            if (getU16(data, off + 2, big) != 42) return 1
            val ifdOff = if (little) {
                // little-endian 32-bit at off+4: lsb first
                (data[off + 4].toInt() and 0xFF) or
                    ((data[off + 5].toInt() and 0xFF) shl 8) or
                    ((data[off + 6].toInt() and 0xFF) shl 16) or
                    ((data[off + 7].toInt() and 0xFF) shl 24)
            } else {
                // big-endian 32-bit at off+4: msb first
                ((data[off + 4].toInt() and 0xFF) shl 24) or
                    ((data[off + 5].toInt() and 0xFF) shl 16) or
                    ((data[off + 6].toInt() and 0xFF) shl 8) or
                    (data[off + 7].toInt() and 0xFF)
            } + off
            if (ifdOff + 2 > data.size) return 1
            val count = getU16(data, ifdOff, big)
            var p = ifdOff + 2
            for (i in 0 until count) {
                if (p + 12 > data.size) return 1
                if (getU16(data, p, big) == 0x0112) {
                    // Orientation is stored inline as a SHORT (type 3)
                    return if (getU16(data, p + 2, big) == 3) getU16(data, p + 8, big) else 1
                }
                p += 12
            }
            return 1
        }

        private fun getU16(data: ByteArray, o: Int, big: Boolean): Int =
            if (big) ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
            else (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)

        private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            if (orientation <= 1) return bitmap
            val matrix = android.graphics.Matrix()
            when (orientation) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setRotate(180f).also { matrix.postScale(-1f, 1f) }
                5 -> matrix.setRotate(90f).also { matrix.postScale(-1f, 1f) }
                6 -> matrix.setRotate(90f)
                7 -> matrix.setRotate(-90f).also { matrix.postScale(-1f, 1f) }
                8 -> matrix.setRotate(-90f)
                else -> return bitmap
            }
            val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (out !== bitmap) bitmap.recycle()
            return out
        }
    }
}
