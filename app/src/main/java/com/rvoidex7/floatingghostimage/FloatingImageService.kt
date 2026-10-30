package com.rvoidex7.floatingghostimage

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import androidx.core.view.isVisible
import kotlin.math.*

class FloatingImageService : Service() {

    private val tag = "FGI-Floating"
    private val touchTag = "FGI_TOUCH"
    private var debugTouchRectView: DebugTouchRectView? = null
    private var imageGestureActive = false

    private fun logTouch(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(touchTag, msg)
        }
    }

    private inner class DebugTouchRectView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val img = imageView ?: return
            val drawable = img.drawable ?: return
            val viewWidth = img.width.toFloat()
            val viewHeight = img.height.toFloat()
            val drawableWidth = drawable.intrinsicWidth.toFloat()
            val drawableHeight = drawable.intrinsicHeight.toFloat()
            if (drawableWidth <= 0 || drawableHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return

            val scaleX = viewWidth / drawableWidth
            val scaleY = viewHeight / drawableHeight
            val actualScale = min(scaleX, scaleY)

            val scaledWidth = drawableWidth * actualScale * scale
            val scaledHeight = drawableHeight * actualScale * scale

            val left = (viewWidth - scaledWidth) / 2f
            val top = (viewHeight - scaledHeight) / 2f
            val right = left + scaledWidth
            val bottom = top + scaledHeight
            val padding = dp(20).toFloat()

            rect.set(left - padding, top - padding, right + padding, bottom + padding)
            canvas.withTranslation(img.translationX, img.translationY) {
                rotate(rotationDeg, img.width / 2f, img.height / 2f)
                drawRect(rect, paint)
            }
        }

        fun update() {
            if (BuildConfig.DEBUG) {
                invalidate()
            }
        }
    }

    /** Window type shared by every overlay this service adds. */
    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * Full-screen layout scope for the image window only: NOT_FOCUSABLE plus
     * LAYOUT_IN_SCREEN/NO_LIMITS, so the image can be dragged under the status bar,
     * navbar and notch areas (it is still drawn UNDER the system bars). The FAB and
     * controls stay in the safe area on purpose — a small window below the navbar's
     * gesture zone would be unreachable by touch and permanently stuck.
     * Single source of truth so mode toggles can't accidentally drop the scope.
     */
    private val fullScreenFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

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
    private var visualAlpha = sliderToAlpha(60)

    // Transform state
    private var scale = 1.0f
    private var rotationDeg = 0.0f
    private var translationX = 0f
    private var translationY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setupFab()
        setupControls()

        try { windowManager.addView(fabView, fabParams); Log.d(tag, "fab added") } catch (t: Throwable) { Log.e(tag, "add fab failed: ${t.message}") }

        setupFabTouch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand")
        intent?.let {
            val uri = it.getStringExtra("imageUri")
            val opacity = it.getIntExtra("opacity", 60).coerceIn(0, 100)
            lastImageUri = uri
            // Slider 0–100 maps to window alpha 0.0–0.8 (MAX_OPACITY).
            visualAlpha = sliderToAlpha(opacity)
            lastImageAlpha = visualAlpha

            if (!uri.isNullOrEmpty()) {
                if (imageRoot == null) {
                    // Start locked (passthrough) by default
                    isImageLocked = true
                    setupImageWindow()
                }

                // Apply transforms synchronously (cheap); decode the bitmap off the main thread.
                imageView?.alpha = 1.0f
                imageView?.translationX = translationX
                imageView?.translationY = translationY
                imageView?.scaleX = scale
                imageView?.scaleY = scale
                imageView?.rotation = rotationDeg

                try {
                    if (!imageAdded) {
                        windowManager.addView(imageRoot, imageParams)
                        imageAdded = true
                        Log.d(tag, "image window added")
                        // Same-type overlays stack in the order they are added; re-add the FAB so the image never covers it.
                        if (fabView.parent != null) { windowManager.removeView(fabView); windowManager.addView(fabView, fabParams) }
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "add image window failed: ${t.message}")
                }

                loadImageAsync(uri)

                // Sync panel
                controlsView.findViewById<SeekBar>(R.id.opacity_slider)?.progress = opacity
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(tag, "onDestroy")
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
                decodeSampledBitmap(uriString.toUri(), target)
            } catch (t: Throwable) {
                Log.w(tag, "Failed to decode bitmap: $uriString", t)
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
    private val imageTouchListener = ImageTouchListener()

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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageWindow() {
        imageRoot = FrameLayout(this)
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 1.0f
        }

        imageRoot?.addView(imageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val initialFlags = fullScreenFlags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        imageParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            initialFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            alpha = visualAlpha
        }

        imageRoot?.setOnTouchListener { _, ev ->
            val locked = isImageLocked
            val img = imageView
            if (locked || img == null) {
                return@setOnTouchListener false
            }
            val xIn = ev.x - img.translationX
            val yIn = ev.y - img.translationY
            val onImg = isTouchOnDrawable(img, xIn, yIn)
            logTouch("ROOT|locked=$locked|act=${ev.actionMasked}|onImg=$onImg|active=$imageGestureActive|xy=(${ev.x.toInt()},${ev.y.toInt()})|in=(${xIn.toInt()},${yIn.toInt()})|flags=${imageParams?.flags}|alpha=${imageParams?.alpha}")
            if (!onImg && !imageGestureActive) {
                return@setOnTouchListener false
            }
            val res = imageTouchListener.onTouch(img, ev)
            logTouch("ROOT->IMG|locked=$locked|act=${ev.actionMasked}|res=$res|active=$imageGestureActive")
            return@setOnTouchListener res
        }
        val rootForDebug = imageRoot
        if (BuildConfig.DEBUG && rootForDebug != null && debugTouchRectView == null) {
            val debugView = DebugTouchRectView(this)
            debugTouchRectView = debugView
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            debugView.isClickable = false
            debugView.isFocusable = false
            rootForDebug.addView(debugView, lp)
            debugView.update()
        }
        imageView?.setOnTouchListener(null)

        Log.d(tag, "setupImageWindow: locked=$isImageLocked flags=${imageParams?.flags} alpha=${imageParams?.alpha}")
    }

    private fun setupFab() {
        fabView = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        fabParams = WindowManager.LayoutParams(
            dp(48), dp(48),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(16)
        }
    }

    @SuppressLint("InflateParams")
    private fun setupControls() {
        // Use themed context for AppCompat components
        val ctx = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_FloatingGhostImage)
        val inflater = ctx.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        controlsView = inflater.inflate(R.layout.floating_controls, null)
        controlsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
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
                params.flags = fullScreenFlags or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                params.alpha = visualAlpha
                imageView?.alpha = 1.0f
                imageView?.setOnTouchListener(null)
                logTouch("MODE|to=LOCKED|flags=${params.flags}|alpha=${params.alpha}")
            } else {
                params.flags = fullScreenFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                val minTouchAlpha = 0.6f
                if (visualAlpha < minTouchAlpha) {
                    params.alpha = minTouchAlpha
                    imageView?.alpha = (visualAlpha / minTouchAlpha).coerceIn(0.01f, 1.0f)
                } else {
                    params.alpha = visualAlpha
                    imageView?.alpha = 1.0f
                }
                imageView?.setOnTouchListener(null)
                logTouch("MODE|to=EDIT|flags=${params.flags}|alpha=${params.alpha}|imgAlpha=${imageView?.alpha}")
            }

            try {
                windowManager.updateViewLayout(imageRoot, params)
            } catch (t: Throwable) {
                Log.e(tag, "Failed to update flags: ${t.message}")
            }
        }

        opacitySlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val a = sliderToAlpha(progress)
                visualAlpha = a
                lastImageAlpha = a
                val params = imageParams ?: return
                val minTouchAlpha = 0.6f
                if (!isImageLocked) {
                    if (a < minTouchAlpha) {
                        params.alpha = minTouchAlpha
                        imageView?.alpha = (a / minTouchAlpha).coerceIn(0.01f, 1.0f)
                    } else {
                        params.alpha = a
                        imageView?.alpha = 1.0f
                    }
                } else {
                    params.alpha = a
                    imageView?.alpha = 1.0f
                }
                if (fromUser) {
                    logTouch("ALPHA|p=$progress|a=$a|locked=$isImageLocked|flags=${params.flags}|winAlpha=${params.alpha}|imgAlpha=${imageView?.alpha}")
                }
                requestAlphaSync()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
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
                    it.visibility = if (it.isVisible) View.GONE else View.VISIBLE
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
        controlsView.findViewById<SeekBar>(R.id.opacity_slider)?.progress = alphaToSlider(visualAlpha)
    }

    private fun closeControls() {
        if (!isControlsVisible) return

        // Always switch to lock mode when closing the panel.
        if (!isImageLocked) {
            isImageLocked = true
            val params = imageParams
            if (params != null) {
                params.flags = fullScreenFlags or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                params.alpha = visualAlpha
                imageView?.setOnTouchListener(null)
                try {
                    windowManager.updateViewLayout(imageRoot, params)
                    Log.d(tag, "closeControls: Switched to LOCK mode")
                    logTouch("MODE|to=LOCKED|flags=${params.flags}|alpha=${params.alpha}|src=closeControls")
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
            if (BuildConfig.DEBUG) Log.d(touchTag, "IMG_TOUCH|act=${ev.actionMasked}|cnt=${ev.pointerCount}|drag=$dragging|scale=$scaling|locked=$isImageLocked")

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    imageGestureActive = true
                    dragging = true
                    scaling = false
                    startTX = img.translationX
                    startTY = img.translationY
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    debugTouchRectView?.update()
                    if (BuildConfig.DEBUG) Log.d(touchTag, "IMG_TOUCH|DOWN|start=($startTX,$startTY)|raw=($downRawX,$downRawY)")
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount >= 2) {
                        imageGestureActive = true
                        dragging = false
                        scaling = true
                        startDist = distance(ev)
                        startAngle = angle(ev)
                        startScale = scale
                        startRot = rotationDeg
                        debugTouchRectView?.update()
                        if (BuildConfig.DEBUG) Log.d(touchTag, "IMG_TOUCH|P_DOWN|dist=$startDist|ang=$startAngle")
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
                        debugTouchRectView?.update()
                    } else if (dragging) {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        translationX = startTX + dx
                        translationY = startTY + dy
                        img.translationX = translationX
                        img.translationY = translationY
                        debugTouchRectView?.update()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (BuildConfig.DEBUG) Log.d(touchTag, "IMG_TOUCH|P_UP|cnt=${ev.pointerCount}|idx=${ev.actionIndex}|drag=$dragging|scale=$scaling|active=$imageGestureActive")
                    if (scaling && ev.pointerCount == 2) {
                        val imgV = imageView
                        if (imgV != null) {
                            dragging = true
                            scaling = false
                            startTX = imgV.translationX
                            startTY = imgV.translationY
                            val remaining = if (ev.actionIndex == 0) 1 else 0
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                downRawX = ev.getRawX(remaining)
                                downRawY = ev.getRawY(remaining)
                            } else {
                                downRawX = ev.getX(remaining) + imgV.x
                                downRawY = ev.getY(remaining) + imgV.y
                            }
                            if (BuildConfig.DEBUG) Log.d(touchTag, "IMG_TOUCH|PROMOTE_DRAG|rem=$remaining|raw=($downRawX,$downRawY)")
                            return true
                        }
                    }
                    if (ev.pointerCount <= 1) {
                        dragging = false
                        scaling = false
                        imageGestureActive = false
                    } else {
                        scaling = false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (BuildConfig.DEBUG) Log.d(touchTag, "IMG_TOUCH|UP/CANCEL|act=${ev.actionMasked}|drag=$dragging|scale=$scaling|active=$imageGestureActive")
                    dragging = false
                    scaling = false
                    imageGestureActive = false
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

            // Extra 20 dp of tolerance around the image so it is easier to grab.
            val padding = dp(20).toFloat()
            return x >= (left - padding) && x <= (right + padding) &&
                   y >= (top - padding) && y <= (bottom + padding)
        }
    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()
}
