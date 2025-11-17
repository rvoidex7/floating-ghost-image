package com.rvoidex7.floatingghostimage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

class InverseRoundedDrawable(
    private val backgroundColor: Int,
    private val cornerRadius: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColor
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds

        // Create layer (for transparency support)
        val layerPaint = Paint()
        val saveCount = canvas.saveLayer(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), layerPaint)

        // Fill entire area (solid 50% black)
        canvas.drawRect(bounds, paint)

        // Draw circles at top corners and rectangle cutout in the middle (with clear mode)
        // Top left corner - shifted right by cornerRadius
        canvas.drawCircle(cornerRadius, 0f, cornerRadius, clearPaint)

        // Rectangle cutout between two circles
        canvas.drawRect(cornerRadius, 0f, bounds.width().toFloat() - cornerRadius, cornerRadius, clearPaint)

        // Top right corner - shifted left by cornerRadius
        canvas.drawCircle(bounds.width().toFloat() - cornerRadius, 0f, cornerRadius, clearPaint)

        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
