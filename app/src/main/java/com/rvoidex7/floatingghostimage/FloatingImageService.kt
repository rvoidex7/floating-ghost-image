package com.rvoidex7.floatingghostimage

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.*

class FloatingImageService : Service() {

    private val TAG = "FGI-Floating"

    private lateinit var windowManager: WindowManager

    // Main image window (full-screen with translation)
    private var imageRoot: FrameLayout? = null
    private var imageView: ImageView? = null
    private var imageParams: WindowManager.LayoutParams? = null
    private var imageAdded = false

    // FAB
    private lateinit var fabView: ImageView
    private lateinit var fabParams: WindowManager.LayoutParams

    // Controls
    private lateinit var controlsView: View
    private lateinit var controlsParams: WindowManager.LayoutParams
    private var isControlsVisible = false

    companion object {
        const val MAX_OPACITY = 0.8f

        fun sliderToAlpha(progress: Int): Float =
            (progress / 100f).coerceIn(0f, 1f) * MAX_OPACITY

        fun alphaToSlider(alpha: Float): Int =
            ((alpha / MAX_OPACITY) * 100f).roundToInt().coerceIn(0, 100)
    }

    // State
    // Lock ON: guide mode, semi-transparent with full passthrough
    // Lock OFF: edit mode, touchable
    private var isImageLocked = true
    private var lastImageAlpha = sliderToAlpha(60)
    private var lastImageUri: String? = null

    // Transform state
    private var scale = 1.0f
    private var rotationDeg = 0.0f
    private var translationX = 0f
    private var translationY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        setupFab(layoutFlag)
        setupControls(layoutFlag)

        try { windowManager.addView(fabView, fabParams); Log.d(TAG, "fab added") } catch (t: Throwable) { Log.e(TAG, "add fab failed: ${t.message}") }

        setupFabTouch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        intent?.let {
            val uri = it.getStringExtra("imageUri")
            val opacity = it.getIntExtra("opacity", 60).coerceIn(0, 100)
            lastImageUri = uri
            // Opacity slider value 0..100 maps to window alpha 0.0..MAX_OPACITY (0.8f)
            lastImageAlpha = sliderToAlpha(opacity)

            if (!uri.isNullOrEmpty()) {
                if (imageRoot == null) {
                    val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    // Start locked (passthrough) by default
                    isImageLocked = true
                    setupImageWindow(layoutFlag)
                }

                // Apply transforms synchronously (cheap); decode the bitmap off the main thread.
                // imageView.alpha is fixed at 1.0f — opacity driven by LayoutParams.alpha only.
                imageView?.translationX = translationX
                imageView?.translationY = translationY
                imageView?.scaleX = scale
                imageView?.scaleY = scale
                imageView?.rotation = rotationDeg

                try {
                    if (!imageAdded) {
                        windowManager.addView(imageRoot, imageParams)
                        imageAdded = true
                        Log.d(TAG, "image window added")
                        // Same-type overlays stack in add order; re-add the FAB so the image never covers it.
                        if (fabView.parent != null) { windowManager.removeView(fabView); windowManager.addView(fabView, fabParams) }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "add image window failed: ${t.message}")
                }

                loadImageAsync(uri)

                // Sync panel
                controlsView.findViewById<SeekBar>(R.id.opacity_slider)?.progress = opacity
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        handler.removeCallbacks(alphaSync)
        if (isControlsVisible) try { windowManager.removeView(controlsView) } catch (_: Throwable) {}
        if (::fabView.isInitialized && fabView.parent != null) try { windowManager.removeView(fabView) } catch (_: Throwable) {}
        if (imageAdded && imageRoot != null) try { windowManager.removeView(imageRoot) } catch (_: Throwable) {}
        FloatingServiceState.setRunning(this, false)
        super.onDestroy()
    }

    /** Full-size setImageURI was a too-large-bitmap / ANR source; decode sampled off-thread. */
    private fun loadImageAsync(uriString: String) {
        val target = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        Thread {
            val bitmap = try {
                decodeSampledBitmap(Uri.parse(uriString), target)
            } catch (t: Throwable) {
                null
            }
            handler.post {
                if (lastImageUri == uriString && bitmap != null) {
                    imageView?.setImageBitmap(bitmap)
                    // imageView.alpha is always 1.0f — no reset needed.
                } else {
                    bitmap?.recycle()
                }
            }
        }.start()
    }

    private fun decodeSampledBitmap(uri: Uri, reqPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= reqPx && bounds.outHeight / (sampleSize * 2) >= reqPx) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private val handler = Handler(Looper.getMainLooper())

    // Coalesce updates to once per frame (~60/120fps) without delaying user touch feedback
    private var isAlphaSyncPending = false
    private val alphaSync = Runnable {
        isAlphaSyncPending = false
        imageParams?.let { lp ->
            try { windowManager.updateViewLayout(imageRoot, lp) } catch (_: Throwable) {}
        }
    }
    private fun requestAlphaSync() {
        if (!isAlphaSyncPending) {
            isAlphaSyncPending = true
            handler.post(alphaSync)
        }
    }

    private fun setupImageWindow(layoutFlag: Int) {
        imageRoot = FrameLayout(this)
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Always 1.0f: opacity is driven exclusively by the window's LayoutParams.alpha
            // to prevent alpha² doubling (view-alpha × window-alpha = squared opacity).
            alpha = 1.0f
        }

        imageRoot?.addView(imageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // imageRoot should not consume touches - let them pass through
        imageRoot?.setOnTouchListener { _, _ -> false }

        // lastImageAlpha is always in 0..0.8f (slider maps 0–100 → 0.0–0.8f),
        // so no coerceAtMost is needed — same flags/alpha regardless of lock state at init.
        val initialFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        imageParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            initialFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Window-level alpha drives all opacity (0..0.8f); never exceeds 0.8f.
            alpha = lastImageAlpha
        }

        // Touch listener in edit mode, no listener in passthrough mode
        if (!isImageLocked) {
            imageView?.setOnTouchListener(ImageTouchListener())
        } else {
            imageView?.setOnTouchListener(null)
        }

        Log.d(TAG, "setupImageWindow: locked=$isImageLocked flags=${imageParams?.flags} alpha=${imageParams?.alpha}")
    }

    private fun setupFab(layoutFlag: Int) {
        fabView = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        fabParams = WindowManager.LayoutParams(
            dp(48), dp(48),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(16)
        }
    }

    private fun setupControls(layoutFlag: Int) {
        // Use themed context for AppCompat components
        val ctx = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_FloatingGhostImage)
        val inflater = ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        controlsView = inflater.inflate(R.layout.floating_controls, null)
        controlsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val opacitySlider: SeekBar? = controlsView.findViewById(R.id.opacity_slider)
        val lockSwitch: SwitchCompat? = controlsView.findViewById(R.id.lock_switch)
        val btnBack: View? = controlsView.findViewById(R.id.btn_back)
        val btnClose: View? = controlsView.findViewById(R.id.btn_close)

        lockSwitch?.isChecked = isImageLocked
        lockSwitch?.setOnCheckedChangeListener { _, checked ->
            // checked = true => guide/passthrough; checked = false => edit
            isImageLocked = checked
            val params = imageParams ?: return@setOnCheckedChangeListener

            if (checked) {
                // Passthrough: NOT_TOUCHABLE. lastImageAlpha is already ≤ 0.8f (slider enforces it).
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                params.alpha = lastImageAlpha
                imageView?.setOnTouchListener(null)
                Log.d(TAG, "Lock ON -> PASSTHROUGH alpha=${params.alpha}")
            } else {
                // Edit: touchable. Alpha stays the same — no visual jump on mode switch.
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                params.alpha = lastImageAlpha
                imageView?.setOnTouchListener(ImageTouchListener())
                Log.d(TAG, "Lock OFF -> EDIT alpha=${params.alpha}")
            }

            try {
                windowManager.updateViewLayout(imageRoot, params)
                Log.d(TAG, "flags updated=${params.flags} alpha=${params.alpha}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to update flags: ${t.message}")
            }
        }

        opacitySlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Map slider 0–100 → window alpha 0.0–MAX_OPACITY (0.8f).
                // Capping at 0.8f means slider at full = actual visual max (passthrough legal limit).
                // No coerceAtMost at lock/unlock time → zero visual jump on mode change.
                // imageView.alpha stays 1.0f to avoid alpha² squaring.
                val a = sliderToAlpha(progress)
                lastImageAlpha = a
                imageParams?.alpha = a
                requestAlphaSync()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Ensure the final resting value is applied immediately
                handler.removeCallbacks(alphaSync)
                isAlphaSyncPending = false
                alphaSync.run()
            }
        })

        btnBack?.setOnClickListener { closeControls() }
        btnClose?.setOnClickListener { stopSelf() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFabTouch() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Toggle image visibility
                imageView?.let {
                    it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    // imageView.alpha is always 1.0f — no restore needed.
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) { toggleControls() }
        })

        val drag = object : View.OnTouchListener {
            private var sx = 0
            private var sy = 0
            private var tx = 0f
            private var ty = 0f
            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                detector.onTouchEvent(ev)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = fabParams.x; sy = fabParams.y; tx = ev.rawX; ty = ev.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        fabParams.x = (sx + (ev.rawX - tx)).roundToInt()
                        fabParams.y = (sy + (ev.rawY - ty)).roundToInt()
                        try { windowManager.updateViewLayout(fabView, fabParams) } catch (_: Throwable) {}
                    }
                }
                return true
            }
        }
        fabView.setOnTouchListener(drag)
    }

    private fun toggleControls() { if (!isControlsVisible) openControls() else closeControls() }

    private fun openControls() {
        if (isControlsVisible) return
        // Hide FAB and open panel at FAB's exact position
        fabView.visibility = View.GONE
        controlsParams.x = fabParams.x
        controlsParams.y = fabParams.y
        try { windowManager.addView(controlsView, controlsParams); isControlsVisible = true } catch (_: Throwable) {}
        // Sync lock switch and opacity with current state
        controlsView.findViewById<SwitchCompat>(R.id.lock_switch)?.isChecked = isImageLocked
        controlsView.findViewById<SeekBar>(R.id.opacity_slider)?.progress = alphaToSlider(lastImageAlpha)
    }

    private fun closeControls() {
        if (!isControlsVisible) return

        // Always switch to lock mode when closing panel.
        // No alpha coerce needed: lastImageAlpha is already ≤ 0.8f (slider enforces the range).
        if (!isImageLocked) {
            isImageLocked = true
            val params = imageParams
            if (params != null) {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                params.alpha = lastImageAlpha
                imageView?.setOnTouchListener(null)
                try {
                    windowManager.updateViewLayout(imageRoot, params)
                    Log.d(TAG, "closeControls: Switched to LOCK mode")
                } catch (_: Throwable) {}
            }
        }

        try { windowManager.removeView(controlsView); isControlsVisible = false } catch (_: Throwable) {}
        // Show FAB again
        fabView.visibility = View.VISIBLE
    }

    // Image Touch Listener (for editing mode)
    @SuppressLint("ClickableViewAccessibility")
    private inner class ImageTouchListener : View.OnTouchListener {
        private var dragging = false
        private var startTX = 0f
        private var startTY = 0f
        private var downRawX = 0f
        private var downRawY = 0f

        private var scaling = false
        private var startDist = 0f
        private var startAngle = 0f
        private var startScale = scale
        private var startRot = rotationDeg

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val img = imageView ?: return false

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Check if touch is actually on the drawable, not just empty space
                    if (!isTouchOnDrawable(img, ev.x, ev.y)) {
                        return false // Let touch pass through to apps below
                    }
                    dragging = true
                    scaling = false
                    startTX = img.translationX
                    startTY = img.translationY
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount >= 2) {
                        dragging = false
                        scaling = true
                        startDist = distance(ev)
                        startAngle = angle(ev)
                        startScale = scale
                        startRot = rotationDeg
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaling && ev.pointerCount >= 2) {
                        val d = distance(ev).coerceAtLeast(1f)
                        val a = angle(ev)
                        val newScale = (startScale * (d / startDist)).coerceIn(0.1f, 6.0f)
                        val deltaRot = a - startAngle
                        val newRot = startRot + deltaRot

                        scale = newScale
                        rotationDeg = normalizeDeg(newRot)

                        img.scaleX = scale
                        img.scaleY = scale
                        img.rotation = rotationDeg
                    } else if (dragging) {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        translationX = startTX + dx
                        translationY = startTY + dy
                        img.translationX = translationX
                        img.translationY = translationY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (ev.pointerCount <= 2) scaling = false
                }
            }
            return true
        }

        private fun distance(ev: MotionEvent): Float {
            if (ev.pointerCount < 2) return 1f
            val dx = ev.getX(1) - ev.getX(0)
            val dy = ev.getY(1) - ev.getY(0)
            return sqrt(dx * dx + dy * dy)
        }

        private fun angle(ev: MotionEvent): Float {
            if (ev.pointerCount < 2) return 0f
            val dx = ev.getX(1) - ev.getX(0)
            val dy = ev.getY(1) - ev.getY(0)
            return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }

        private fun normalizeDeg(d: Float): Float {
            var v = d
            while (v < 0f) v += 360f
            while (v >= 360f) v -= 360f
            return v
        }

        private fun isTouchOnDrawable(v: ImageView, x: Float, y: Float): Boolean {
            val drawable = v.drawable ?: return false

            val viewWidth = v.width.toFloat()
            val viewHeight = v.height.toFloat()
            val drawableWidth = drawable.intrinsicWidth.toFloat()
            val drawableHeight = drawable.intrinsicHeight.toFloat()

            if (drawableWidth <= 0 || drawableHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return false

            // Calculate scaled dimensions (FIT_CENTER maintains aspect ratio)
            val scaleX = viewWidth / drawableWidth
            val scaleY = viewHeight / drawableHeight
            val actualScale = min(scaleX, scaleY)

            val scaledWidth = drawableWidth * actualScale * scale // Include user's scale transform
            val scaledHeight = drawableHeight * actualScale * scale

            // Center position
            val left = (viewWidth - scaledWidth) / 2f
            val top = (viewHeight - scaledHeight) / 2f
            val right = left + scaledWidth
            val bottom = top + scaledHeight

            // Add some padding for easier touch (20dp margin)
            val padding = dp(20).toFloat()
            return x >= (left - padding) && x <= (right + padding) &&
                   y >= (top - padding) && y <= (bottom + padding)
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()
}
