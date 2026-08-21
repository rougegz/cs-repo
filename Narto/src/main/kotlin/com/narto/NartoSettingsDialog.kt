package com.narto

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
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

        val cfButton = TextView(context).apply {
            text = "🛡️ Solve Cloudflare for this domain"
            setTextColor(Color.parseColor("#0d6efd"))
            textSize = 14f
            setPadding(0, pad/2, 0, pad/2)
            setOnClickListener { showCloudflareWebView(context, currentBaseUrl) }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad/2, pad, 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
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
            addView(cfButton)
        }

        AlertDialog.Builder(context)
            .setTitle("Narto Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ -> applyDomain(input.text?.toString()) }
            .setNeutralButton("Use default") { _, _ -> applyDomain("") }
            .setNegativeButton("Cancel", null)
            .show()
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
            .setTitle("Solve Cloudflare — complete then Save")
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
                    Toast.makeText(context, "No cookies found — wait for page to load", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
        webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 900)
    }
}
