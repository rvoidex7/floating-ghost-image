package com.rvoidex7.floatingghostimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

class ImageHistoryAdapter(
    context: Context,
    private val items: List<String>,
    private val onItemClick: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : BaseAdapter() {

    companion object {
        const val EXAMPLE_FGI_CODE = "FGI1:X31.6;Y27.2;W26.4;R90;A60;S1080x2400"
    }

    private val inflater = LayoutInflater.from(context)
    private val contentResolver = context.contentResolver
    private val reqSizePx = (160 * context.resources.displayMetrics.density).toInt().coerceAtLeast(320)
    private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Device screen corner radius in px; row separators inset from each edge by this. */
    var cornerRadiusPx: Float = 60f

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
        holder.bind(items[position], position)
        return row
    }

    private inner class ViewHolder(row: View) {
        private val thumb: ImageView = row.findViewById(R.id.imgItemThumb)
        val share: ImageButton = row.findViewById(R.id.btnItemShare)
        val fgiCode: TextView = row.findViewById(R.id.txtItemFgiCode)
        val delete: ImageButton = row.findViewById(R.id.btnItemDelete)
        private val separator: View = row.findViewById(R.id.divItemSeparator)
        private var currentUri: String? = null

        init {
            share.setOnClickListener { /* placeholder, share not wired up yet */ }
            delete.setOnClickListener { currentUri?.let(onDelete) }
            row.setOnClickListener { currentUri?.let(onItemClick) }
        }

        fun bind(uriString: String, position: Int) {
            currentUri = uriString
            fgiCode.text = EXAMPLE_FGI_CODE
            thumb.setImageBitmap(thumbCache.get(uriString) ?: decodeThumb(uriString)?.also {
                thumbCache.put(uriString, it)
            })

            // Separator only marks the gap between rows: hidden under the last item
            // (and when there is a single item), inset by the device corner radius.
            separator.visibility = if (position == items.lastIndex) View.GONE else View.VISIBLE
            val inset = cornerRadiusPx.toInt()
            val lp = separator.layoutParams as ViewGroup.MarginLayoutParams
            if (lp.marginStart != inset || lp.marginEnd != inset) {
                lp.marginStart = inset
                lp.marginEnd = inset
                separator.layoutParams = lp
            }
        }

        private fun decodeThumb(uriString: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriString)
                val orientation = readExifOrientation(uri)
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
                applyOrientation(decoded, orientation)
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * BitmapFactory ignores EXIF, so portrait/mirrored photos come out rotated.
         * Minimal dependency-free JPEG EXIF reader for the Orientation tag (0x0112).
         * Non-JPEG or malformed data returns 1 (normal) — never crashes.
         */
        private fun readExifOrientation(uri: Uri): Int {
            return try {
                val stream = contentResolver.openInputStream(uri) ?: return 1
                stream.use { readJpegOrientation(it) }
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