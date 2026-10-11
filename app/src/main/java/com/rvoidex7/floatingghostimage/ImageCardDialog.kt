package com.rvoidex7.floatingghostimage

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import java.io.BufferedInputStream

/**
 * Native framework dialog (android.app.AlertDialog) carrying the app's content:
 * an aspect-sized flat preview of the image, plus a small brand row (logo +
 * app name) on the share-intent variant only. The FGI Code field exists in the
 * layout but is commented out — the feature it belongs to is not built yet.
 *
 * Everything around the content is the system dialog's own default — no custom
 * window theming, no rounding overrides, no scrim changes. Deliberate: we look
 * at the untouched native result first, then iterate.
 */
object ImageCardDialog {

    /** Resolves the file name of a content URI, or null when unavailable. */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun show(
        context: Context,
        uri: Uri,
        showBrand: Boolean,
        positiveLabel: String? = null,
        negativeLabel: String? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null
    ): Dialog {
        // Fixed dark dialog: the system's own dark alert-dialog theme, applied in
        // code so it stays dark whatever the app/base theme or device theme is.
        val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_image_card, null)
        view.findViewById<View>(R.id.brandRow).visibility =
            if (showBrand) View.VISIBLE else View.GONE

        val builder = AlertDialog.Builder(themed).setView(view)
        if (positiveLabel != null && onPositive != null) {
            builder.setPositiveButton(positiveLabel) { _, _ -> onPositive() }
        }
        if (negativeLabel != null && onNegative != null) {
            builder.setNegativeButton(negativeLabel) { _, _ -> onNegative() }
        }
        val dialog = builder.create()
        loadImageAsync(themed, uri, view.findViewById(R.id.imgCardPreview))
        dialog.show()
        return dialog
    }

    /**
     * Loads the dialog preview without any size jump. First the real aspect is
     * read via a bounds-only pass (file header, no pixel decode), and the box is
     * sized to it before the dialog's first layout. The window is therefore
     * already at its final size while the placeholder (gallery icon + frame)
     * shows, matching the early-aspect pattern of the history list. The pixel
     * decode then swaps the image in without resizing anything.
     */
    private fun loadImageAsync(context: Context, uri: Uri, imageView: ImageView) {
        val density = context.resources.displayMetrics.density
        val maxW = (240 * density).toInt()
        val maxH = (280 * density).toInt()
        val targetPx = (440 * density).toInt()

        val aspect = readAspect(context, uri) ?: 1f
        val (w, h) = aspectFitSize(aspect, maxW, maxH)
        val lp = LinearLayout.LayoutParams(w, h)
        lp.topMargin = (8 * density).toInt()
        imageView.layoutParams = lp

        Thread {
            val bitmap = try {
                decodeSampled(context, uri, targetPx)
            } catch (_: Throwable) {
                null
            }
            imageView.post {
                if (bitmap != null) {
                    imageView.imageTintList = null
                    // Loading placeholder (icon + frame) goes away once the image lands.
                    imageView.background = null
                    imageView.setImageBitmap(bitmap)
                    // Authoritative aspect from the decoded pixels; re-span to be exact.
                    val (w2, h2) =
                        aspectFitSize(bitmap.width.toFloat() / bitmap.height, maxW, maxH)
                    val lp2 = LinearLayout.LayoutParams(w2, h2)
                    lp2.topMargin = (8 * density).toInt()
                    imageView.layoutParams = lp2
                }
            }
        }.start()
    }

    /** Bounds-only aspect read (header, not pixels) so the box shape is known early. */
    private fun readAspect(context: Context, uri: Uri): Float? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val orientation = readExifOrientation(context, uri)
            val rotated = orientation in 5..8
            val bw = if (rotated) bounds.outHeight else bounds.outWidth
            val bh = if (rotated) bounds.outWidth else bounds.outHeight
            if (bw > 0 && bh > 0) bw.toFloat() / bh else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Largest box with the given aspect that fits inside maxW x maxH (no crop). */
    private fun aspectFitSize(aspect: Float, maxW: Int, maxH: Int): Pair<Int, Int> {
        val r = aspect.coerceAtLeast(0.001f)
        val w: Int
        val h: Int
        if (maxW / r <= maxH) {
            w = maxW
            h = (maxW / r).toInt()
        } else {
            h = maxH
            w = (maxH * r).toInt()
        }
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    private fun decodeSampled(context: Context, uri: Uri, reqPx: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeModern(context, uri, reqPx)
        } else {
            decodeLegacy(context, uri, reqPx)
        }
    }

    /** API 28+: ImageDecoder samples, colors and applies EXIF rotation itself. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(context: Context, uri: Uri, reqPx: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / (sample * 2) >= reqPx
                && info.size.height / (sample * 2) >= reqPx
            ) {
                sample *= 2
            }
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** API 26-27 fallback: BitmapFactory ignores EXIF, so rotate manually. */
    private fun decodeLegacy(context: Context, uri: Uri, reqPx: Int): Bitmap? {
        val orientation = readExifOrientation(context, uri)
        val rotated = orientation in 5..8
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val bw = if (rotated) bounds.outHeight else bounds.outWidth
        val bh = if (rotated) bounds.outWidth else bounds.outHeight
        var sampleSize = 1
        while (bw / (sampleSize * 2) >= reqPx && bh / (sampleSize * 2) >= reqPx) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        return applyOrientation(decoded, orientation)
    }

    /** Minimal dependency-free JPEG EXIF reader for the Orientation tag (0x0112). */
    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return 1
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
                    return parseExifOrientation(payload)
                }
            } else if (input.skip(payloadSize.toLong()) != payloadSize.toLong()) {
                return 1
            }
        }
    }

    private fun parseExifOrientation(data: ByteArray): Int {
        // Exif payload: the TIFF header starts right after the 6-byte
        // "Exif\0\0" signature, so its offset is fixed.
        val off = 6
        if (off + 8 > data.size) return 1
        val little = data[off] == 'I'.code.toByte() && data[off + 1] == 'I'.code.toByte()
        val big = data[off] == 'M'.code.toByte() && data[off + 1] == 'M'.code.toByte()
        if (!little && !big) return 1
        if (getU16(data, off + 2, big) != 42) return 1
        val ifdOff = if (little) {
            (data[off + 4].toInt() and 0xFF) or
                ((data[off + 5].toInt() and 0xFF) shl 8) or
                ((data[off + 6].toInt() and 0xFF) shl 16) or
                ((data[off + 7].toInt() and 0xFF) shl 24)
        } else {
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