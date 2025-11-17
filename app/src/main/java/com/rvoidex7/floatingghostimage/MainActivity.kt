package com.rvoidex7.floatingghostimage

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartFloatingService: Button
    private lateinit var btnDocumentation: ImageButton
    private lateinit var btnGithub: ImageButton
    private lateinit var btnPlayStore: ImageButton
    private lateinit var btnOverlaySettings: ImageButton
    private lateinit var imgPreview: ImageView
    private lateinit var imgAppIcon: ImageView
    private lateinit var txtVersion: TextView

    private var selectedImageUri: Uri? = null
    private var shouldStartServiceAfterImagePick = false

    companion object {
        var isFloatingServiceRunning = false
    }

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Persist permission for future access
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}
            selectedImageUri = uri
            updatePreview()
            Toast.makeText(this, "Image selected.", Toast.LENGTH_SHORT).show()

            if (shouldStartServiceAfterImagePick) {
                shouldStartServiceAfterImagePick = false
                toggleFloatingService()
            }
        } else {
            // User cancelled selection - reset flag
            shouldStartServiceAfterImagePick = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // Initialize views
        btnStartFloatingService = findViewById(R.id.btnStartFloatingService)
        btnDocumentation = findViewById(R.id.btnDocumentation)
        btnGithub = findViewById(R.id.btnGithub)
        btnPlayStore = findViewById(R.id.btnPlayStore)
        btnOverlaySettings = findViewById(R.id.btnOverlaySettings)
        imgPreview = findViewById(R.id.imgPreview)
        imgAppIcon = findViewById(R.id.imgAppIcon)
        txtVersion = findViewById(R.id.txtVersion)

        // Apply window insets for edge-to-edge
        val root = findViewById<android.view.View>(R.id.root)
        val appPanel = findViewById<LinearLayout>(R.id.appPanel)

        // Dynamic corner-to-corner gradient based on screen ratio
        root.post {
            val width = root.width.toFloat()
            val height = root.height.toFloat()

            val gradientDrawable = ShapeDrawable(RectShape()).apply {
                shaderFactory = object : ShapeDrawable.ShaderFactory() {
                    override fun resize(width: Int, height: Int): Shader {
                        return LinearGradient(
                            0f, 0f,                    // Top-left corner
                            width.toFloat(), height.toFloat(), // Bottom-right corner - full diagonal
                            intArrayOf(
                                Color.parseColor("#00659B"),  // Start: Blue
                                Color.parseColor("#404D7E"), // Middle: Purple-Blue
                                Color.parseColor("#604170")  // End: Purple
                            ),
                            floatArrayOf(0f, 0.5f, 1f),      // Color positions
                            Shader.TileMode.CLAMP
                        )
                    }
                }
            }
            root.background = gradientDrawable
        }

        // Apply custom inverse rounded drawable for top corners
        val inverseDrawable = InverseRoundedDrawable(
            backgroundColor = Color.parseColor("#80000000"), // Semi-transparent black
            cornerRadius = 60f // Corner radius in pixels
        )
        appPanel.background = inverseDrawable

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appPanel?.updatePadding(bottom = nb.bottom)
            insets
        }

        txtVersion.text = getAppVersion()

        // Match button width to app title width and apply custom style
        val txtAppName = findViewById<TextView>(R.id.txtAppName)
        txtAppName.post {
            val params = btnStartFloatingService.layoutParams
            params.width = txtAppName.width
            btnStartFloatingService.layoutParams = params
        }

        // Apply Arcade Button retro style (3D effect)
        val buttonDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // Round only bottom corners - matches panel radius (60f)
            cornerRadii = floatArrayOf(
                0f, 0f,   // Top-left - no rounding
                0f, 0f,   // Top-right - no rounding
                60f, 60f, // Bottom-right - matches panel
                60f, 60f  // Bottom-left - matches panel
            )
            // 3D gradient effect: light on top, dark on bottom
            colors = intArrayOf(
                Color.parseColor("#A88060"), // Top: Light brown/bronze
                Color.parseColor("#8d6c4c"), // Middle: Target color
                Color.parseColor("#73523C")  // Bottom: Dark brown/bronze
            )
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(5, Color.parseColor("#5A3A24")) // Very dark brown border
        }
        btnStartFloatingService.background = buttonDrawable
        btnStartFloatingService.setTextColor(Color.WHITE) // White text - high contrast
        btnStartFloatingService.elevation = 8f // Subtle elevation effect

        // Set icon button colors
        val iconColor = ColorStateList.valueOf(Color.parseColor("#a9a7b2"))
        btnDocumentation.imageTintList = iconColor
        btnGithub.imageTintList = iconColor
        btnPlayStore.imageTintList = iconColor
        // btnOverlaySettings color is set based on permission status
        updateOverlayIconColor()

        // Image preview - click to select image
        imgPreview.setOnClickListener { pickImage() }
        updatePreview()

        // Root area (outside appPanel) - click to select image
        root.setOnClickListener { pickImage() }

        // Button click listeners
        btnStartFloatingService.setOnClickListener { toggleFloatingService() }
        btnDocumentation.setOnClickListener { showDocumentation() }
        btnGithub.setOnClickListener { openGithub() }
        btnPlayStore.setOnClickListener { openPlayStore() }
        btnOverlaySettings.setOnClickListener { openOverlayPermissionSettings() }
        imgAppIcon.setOnClickListener { openAppSettings() }

        // Handle image sharing intent
        handleShareIntentIfAny(intent)
    }

    override fun onResume() {
        super.onResume()
        updateButtonText()
        updateOverlayIconColor()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntentIfAny(intent)
    }

    private fun handleShareIntentIfAny(incoming: Intent?) {
        val action = incoming?.action
        val type = incoming?.type
        if (Intent.ACTION_SEND == action && type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                selectedImageUri = uri
                updatePreview()

                // Check overlay permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Please enable overlay permission.", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                    return
                }

                // Start overlay directly with shared image
                val serviceIntent = Intent(this, FloatingImageService::class.java).apply {
                    putExtra("imageUri", uri.toString())
                    putExtra("opacity", 60)
                }
                startService(serviceIntent)
                isFloatingServiceRunning = true
                updateButtonText()
                Toast.makeText(this, "Overlay started with shared image.", Toast.LENGTH_SHORT).show()
                // Close MainActivity and return to home screen
                finish()
            }
        }
    }

    private fun pickImage() {
        openDocumentLauncher.launch(arrayOf("image/*"))
    }

    private fun toggleFloatingService() {
        if (isFloatingServiceRunning) {
            // Stop FloatingImageService
            stopService(Intent(this, FloatingImageService::class.java))
            isFloatingServiceRunning = false
            Toast.makeText(this, "Overlay stopped.", Toast.LENGTH_SHORT).show()
        } else {
            // Check overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please enable overlay permission.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
                return
            }
            if (selectedImageUri == null) {
                shouldStartServiceAfterImagePick = true
                pickImage()
                return
            }
            // Start FloatingImageService
            val intent = Intent(this, FloatingImageService::class.java).apply {
                putExtra("imageUri", selectedImageUri.toString())
                putExtra("opacity", 60)
            }
            startService(intent)
            isFloatingServiceRunning = true
            Toast.makeText(this, "Overlay started. Long press icon to edit.", Toast.LENGTH_SHORT).show()
            // Close MainActivity and return to home screen
            finish()
        }
        updateButtonText()
    }

    private fun updateButtonText() {
        btnStartFloatingService.text = if (isFloatingServiceRunning) "STOP" else "START"
    }

    private fun updatePreview() {
        val uri = selectedImageUri
        if (uri == null) {
            imgPreview.setImageResource(R.drawable.add_circle)
            imgPreview.imageTintList = ColorStateList.valueOf(Color.WHITE)
            // 50% opacity (semi-transparent)
            imgPreview.alpha = 0.5f
        } else {
            imgPreview.setImageURI(uri)
            imgPreview.imageTintList = null
            // Full opacity for actual image
            imgPreview.alpha = 1f
        }
    }

    private fun updateOverlayIconColor() {
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        // If permission granted: default Android color, otherwise: orange-red warning color
        if (hasOverlayPermission) {
            btnOverlaySettings.imageTintList = null // Default Android icon color
        } else {
            btnOverlaySettings.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF6B35"))
        }
    }

    private fun getAppVersion(): String {
        return try {
            val p = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            "v" + p.versionName
        } catch (t: Throwable) {
            "v1.0"
        }
    }

    private fun showDocumentation() {
        val topics = arrayOf(
            "What is this app?",
            "How to start overlay",
            "How to edit overlay",
            "How to use with image sharing",
            "Controls explanation"
        )

        AlertDialog.Builder(this)
            .setTitle("Documentation")
            .setItems(topics) { _, which ->
                showDocumentationDetail(which)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDocumentationDetail(index: Int) {
        val title: String
        val message: String

        when (index) {
            0 -> {
                title = "What is this app?"
                message = "Floating Ghost Image allows you to display a semi-transparent reference image on top of other apps. " +
                        "This is useful for drawing, tracing, or comparing images side by side."
            }
            1 -> {
                title = "How to start overlay"
                message = "1. Tap the image preview area to select an image from your device\n" +
                        "2. Tap the START button\n" +
                        "3. Grant overlay permission when prompted\n" +
                        "4. The app will minimize and show a small floating icon\n" +
                        "5. Tap the icon to show/hide the image"
            }
            2 -> {
                title = "How to edit overlay"
                message = "1. Long press the floating icon to open controls\n" +
                        "2. Drag the icon to reposition it\n" +
                        "3. Use the opacity slider to adjust transparency\n" +
                        "4. Toggle the lock switch:\n" +
                        "   • ON (locked): Image is passthrough, touches go through to apps below\n" +
                        "   • OFF (unlocked): You can drag, zoom, and rotate the image\n" +
                        "5. Tap the back button to return to icon-only mode\n" +
                        "6. Tap the close button to stop the overlay"
            }
            3 -> {
                title = "How to use with image sharing"
                message = "1. Open any app with an image (Gallery, Browser, etc.)\n" +
                        "2. Tap the Share button\n" +
                        "3. Select 'Floating Ghost Image' from the share menu\n" +
                        "4. The overlay will start automatically with your image"
            }
            4 -> {
                title = "Controls explanation"
                message = "• Floating Icon: Tap to show/hide image, long press to edit, drag to move\n" +
                        "• Opacity Slider: Adjust image transparency (0-100%)\n" +
                        "• Lock Switch: Enable/disable passthrough mode\n" +
                        "• Back Button: Return to icon-only mode\n" +
                        "• Close Button: Stop the overlay completely"
            }
            else -> {
                title = "Unknown"
                message = "No documentation available."
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGithub() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rvoidex7/floating-ghost-image"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open GitHub.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPlayStore() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            try {
                startActivity(browserIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open Play Store.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        } else {
            Toast.makeText(this, "Overlay permission is only required for Android 6.0 and above.", Toast.LENGTH_SHORT).show()
        }
    }
}
