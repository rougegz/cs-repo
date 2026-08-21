package com.dramafren

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object DramaFrenSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBase: String) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()

        val apiInput = EditText(context).apply {
            hint = "API base (empty = $DEFAULT_API_BASE)"
            setText(DramaFrenStore.loadApiOverride() ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val reelInput = EditText(context).apply {
            hint = "Reel base (empty = $DEFAULT_REEL_BASE)"
            setText(DramaFrenStore.loadReelOverride() ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
        }

        val cfButton = Button(context).apply {
            text = "🛡️ Solve Cloudflare"
            setOnClickListener { showCfWebView(context, DramaFrenStore.apiBase()) }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad/2, pad, 0)
            addView(TextView(context).apply {
                text = "Current: $currentBase"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(TextView(context).apply { text = "API Domain"; setTextColor(Color.DKGRAY); textSize = 12f })
            addView(apiInput)
            addView(TextView(context).apply { text = "Reel Domain (Cloudflare)"; setTextColor(Color.DKGRAY); textSize = 12f })
            addView(reelInput)
            addView(cfButton)
            addView(TextView(context).apply {
                text = "Change domain if site moves. Tap Cloudflare if you see 403 / Just a moment."
                textSize = 11f
                setTextColor(Color.GRAY)
            })
        }

        AlertDialog.Builder(context)
            .setTitle("DramaFren Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                DramaFrenStore.saveApiBase(apiInput.text?.toString())
                DramaFrenStore.saveReelBase(reelInput.text?.toString())
                onDomainChanged?.invoke()
                Toast.makeText(context, "Saved — reloading…", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Use defaults") { _, _ ->
                DramaFrenStore.saveApiBase(null)
                DramaFrenStore.saveReelBase(null)
                onDomainChanged?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCfWebView(context: Context, url: String) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = browserHeaders()["User-Agent"]
            webViewClient = WebViewClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl(url)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 900))
            addView(TextView(context).apply {
                text = "Solve the Cloudflare challenge, then tap Save Cookies"
                setPadding(20, 10, 20, 10)
                setTextColor(Color.DKGRAY)
            })
        }

        AlertDialog.Builder(context)
            .setTitle("Cloudflare — $url")
            .setView(container)
            .setPositiveButton("💾 Save Cookies") { _, _ ->
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    DramaFrenStore.saveCfCookie(url, cookies)
                    val other = if (url.contains("reelfren")) DramaFrenStore.apiBase() else DramaFrenStore.reelBase()
                    DramaFrenStore.saveCfCookie(other, cookies)
                    Toast.makeText(context, "Cookies saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No cookies found — wait for page to finish loading", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Open Reel") { _, _ -> showCfWebView(context, DramaFrenStore.reelBase()) }
            .show()
    }
}
