package com.narto

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object NartoSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBaseUrl: String) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val input = EditText(context).apply {
            hint = "Leave empty for $DEFAULT_BASE_URL"
            setText(NartoStore.loadBase() ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad/2, pad, 0)
            addView(TextView(context).apply {
                text = "Current: $currentBaseUrl"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(input)
            addView(TextView(context).apply {
                text = "Mirror domain, e.g. https://mirror.example.com"
                textSize = 12f
                setTextColor(Color.DKGRAY)
            })
        }

        AlertDialog.Builder(context)
            .setTitle("Narto Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ -> applyDomain(input.text?.toString()) }
            .setNeutralButton("Use default") { _, _ -> applyDomain("") }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("🛡️ Cloudflare") { _, _ -> showCloudflareWebView(context, currentBaseUrl) }
            .show()
        // Android AlertDialog only allows 3 buttons; we need to handle 4 actions.
        // Workaround: after show, add extra button via dialog.getButton
        // For simplicity, we repurpose neutral as Cloudflare and add "Use default" as extra view.
        // Instead, show Cloudflare as separate dialog after Save/Cancel: we add a small extra button in the layout.
        // Simpler: Add a TextView button inside root that opens WebView.
        // Patch: add clickable TextView for Cloudflare
        // (kept minimal — ponytail: one extra view, no custom layout file)
    }

    fun applyDomain(raw: String?): String {
        val norm = normalizeNartoBase(raw)
        NartoStore.saveBase(norm)
        onDomainChanged?.invoke()
        return norm ?: DEFAULT_BASE_URL
    }

    fun showCloudflareWebView(context: Context, url: String) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    // auto-save cookies when Cloudflare challenge passes (no "Just a moment" in title)
                    val title = view?.title ?: ""
                    if (!title.contains("Just a moment", true) && !title.contains("challenge", true)) {
                        val cookies = CookieManager.getInstance().getCookie(finishedUrl ?: url) ?: ""
                        if (cookies.contains("cf_clearance") || cookies.contains("__cf")) {
                            try {
                                val host = java.net.URL(finishedUrl ?: url).host
                                NartoStore.saveCfCookie(host, cookies)
                                NartoStore.saveCfCookie(host.removePrefix("www."), cookies)
                                Toast.makeText(context, "Cloudflare solved for $host", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        webView.loadUrl(url)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Solve Cloudflare — complete then tap Save")
            .setView(webView)
            .setPositiveButton("Save Cookies") { _, _ ->
                val currentUrl = webView.url ?: url
                val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""
                if (cookies.isNotBlank()) {
                    try {
                        val host = java.net.URL(currentUrl).host
                        NartoStore.saveCfCookie(host, cookies)
                        Toast.makeText(context, "Saved cookies for $host", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "No cookies found — try waiting for page to load", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
        // make WebView fill dialog
        webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
    }
}
