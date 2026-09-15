package com.stremiouniversal
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
object AddonUrlOpener {
    fun openBrowseAddons(context: Context) {
        openUrl(context, StremioConstants.BROWSE_ADDONS_URL)
    }
    private fun openUrl(context: Context, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || (uri.scheme?.lowercase() != "http" && uri.scheme?.lowercase() != "https")) {
            Toast.makeText(context, "Invalid link: $url", Toast.LENGTH_SHORT).show()
            return
        }
        if (tryCustomTab(context, uri)) return
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No browser found to open $url", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open $url", Toast.LENGTH_LONG).show()
        }
    }
    private fun tryCustomTab(context: Context, uri: Uri): Boolean = try {
        val customTabsIntent = CustomTabsIntent.Builder().build()
        if (context !is Activity) customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
    fun maybeClipboardManifest(context: Context): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            .orEmpty()
        if (text.isEmpty()) return null
        normalizeAddonUrl(text)
    } catch (_: Exception) {
        null
    }
    fun copyToClipboard(context: Context, label: String, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Copy failed", Toast.LENGTH_SHORT).show()
        }
    }
}
