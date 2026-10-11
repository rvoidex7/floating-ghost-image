package com.rvoidex7.floatingghostimage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import java.io.File

/**
 * Gate shown when the app is picked from another app's share sheet.
 *
 * The window is fully transparent (Theme.FloatingGhostImage.Transparent), so
 * only the custom card appears over the sharing app — the main app UI never
 * opens. Confirming stores the image in history and starts the overlay;
 * rejecting, back, or an outside tap closes the gate.
 */
class ShareGateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val uri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            finish()
            return
        }

        val dialog = ImageCardDialog.show(
            context = this,
            uri = uri,
            showBrand = true,
            positiveLabel = "Add",
            negativeLabel = "Cancel",
            onPositive = { confirm(uri) },
            onNegative = { finish() }
        )
        // Outside tap / back dismisses like a native dialog and closes the gate.
        dialog.setOnDismissListener { if (!isFinishing) finish() }
    }

    private fun confirm(uri: Uri) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable overlay permission.", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
            finish()
            return
        }
        // The share grant is temporary: copy the image into the app's own
        // storage so history and the overlay keep working after the sharing
        // app's URI permission is gone.
        val localUri = persistSharedImage(uri)
        addImageToHistory(localUri)
        startService(Intent(this, FloatingImageService::class.java).apply {
            putExtra("imageUri", localUri.toString())
            putExtra("opacity", 60)
        })
        FloatingServiceState.setRunning(this, true)
        Toast.makeText(this, "Overlay started with shared image.", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Copies the shared image into filesDir/shared/ and returns the local
     * file URI. If copying fails, it falls back to persisting the URI
     * permission and keeps the original URI.
     */
    private fun persistSharedImage(uri: Uri): Uri {
        return try {
            val dir = File(filesDir, "shared").apply { mkdirs() }
            val displayName = ImageCardDialog.queryDisplayName(this, uri)
            val ext = displayName?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
                ?.let { ".$it" }
                ?: ".img"
            val dest = File(dir, "shared_" + System.currentTimeMillis() + ext)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return uri
            Uri.fromFile(dest)
        } catch (_: Throwable) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {}
            uri
        }
    }

    /** Newest first: removes any duplicate, then inserts at the top. */
    private fun addImageToHistory(uri: Uri) {
        val prefs = getSharedPreferences("fgi_history", MODE_PRIVATE)
        val s = uri.toString()
        val arr = JSONArray()
        prefs.getString("images", null)?.let { raw ->
            try {
                val old = JSONArray(raw)
                for (i in 0 until old.length()) {
                    val v = old.getString(i)
                    if (v != s) arr.put(v)
                }
            } catch (_: Throwable) {}
        }
        arr.put(0, s)
        prefs.edit { putString("images", arr.toString()) }
    }
}