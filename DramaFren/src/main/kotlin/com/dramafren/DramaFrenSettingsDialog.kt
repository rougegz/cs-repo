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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Settings UI (shared design across all 4 providers):
 *   Current domain -> edit -> Save. Plus Solve Cloudflare (API + Reel hosts).
 */
object DramaFrenSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBase: String) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val status = TextView(context).apply {
            val hasCf = DramaFrenStore.getCfCookieForUrl(currentBase) != null
            text = if (hasCf) "✓ Cloudflare cookies saved" else "No Cloudflare cookies"
            textSize = 12f
            setTextColor(if (hasCf) Color.parseColor("#2E7D32") else Color.GRAY)
        }

        val input = EditText(context).apply {
            hint = "New domain (current: ${currentBase})"
            setText("")
            isSingleLine = true
            setSelectAllOnFocus(true)
        }

        fun save(): Boolean {
            val raw = input.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) { toast(context, "Unchanged — current domain kept"); return true }
            val normalized = normalizeBaseUrl(raw) ?: return false.also { toast(context, "Invalid URL") }
            DramaFrenStore.saveDomain(0, normalized)
            DramaFrenStore.setActiveIndex(0)
            onDomainChanged?.invoke()
            toast(context, "Saved ✓")
            return true
        }

        val cfButton = Button(context).apply {
            text = "🛡️ Solve Cloudflare"
            layoutParams = rowParams()
            setOnClickListener {
                val target = normalizeBaseUrl(input.text?.toString()) ?: currentBase
                showCfWebView(context, target) { status.updateCf(target) }
            }
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label(context, "Current domain"))
            addView(TextView(context).apply {
                text = currentBase
                textSize = 14f
                setTextColor(Color.parseColor("#1A73E8"))
                // make it look & act like a link
                paint.isUnderlineText = true
                setOnClickListener { v ->
                    runCatching {
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentBase)).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            v.context.startActivity(this)
                        }
                    }
                }
            })
            addView(label(context, "New domain (leave blank to keep current)"))
            addView(input)
            addView(cfButton)
            addView(status)
        }

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(ScrollView(context).apply {
                addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            })
        }

        AlertDialog.Builder(context)
            .setTitle("DramaFren Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ -> save() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 }

    private fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.GRAY)
        setPadding(0, 10, 0, 2)
    }

    private fun TextView.updateCf(url: String) {
        val has = DramaFrenStore.getCfCookieForUrl(url) != null
        text = if (has) "✓ Cloudflare cookies saved" else "No Cloudflare cookies"
        setTextColor(if (has) Color.parseColor("#2E7D32") else Color.GRAY)
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun showCfWebView(context: Context, url: String, onSaved: () -> Unit) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = browserHeaders()["User-Agent"]
            webViewClient = WebViewClient()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * context.resources.displayMetrics.density).toInt())
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl(url)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(webView)
            addView(TextView(context).apply {
                text = "Complete the challenge, then tap Save Cookies."
                setPadding(20, 10, 20, 10)
                setTextColor(Color.DKGRAY)
                textSize = 12f
            })
        }

        AlertDialog.Builder(context)
            .setTitle("Cloudflare — $url")
            .setView(container)
            .setPositiveButton("💾 Save Cookies") { _, _ ->
                val current = webView.url ?: url
                val ck = CookieManager.getInstance().getCookie(current)
                    ?: CookieManager.getInstance().getCookie(url)
                if (!ck.isNullOrBlank() && (ck.contains("cf_clearance") || ck.contains("__cf"))) {
                    DramaFrenStore.saveCfCookie(current, ck)
                    // mirror host shares the clearance in practice — save for both
                    val other = if (current.contains("reelfren")) DramaFrenStore.apiBase() else DramaFrenStore.reelBase()
                    DramaFrenStore.saveCfCookie(other, ck)
                    onSaved()
                    Toast.makeText(context, "Cookies saved ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No cf_clearance found — finish the challenge first", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Open Reel") { _, _ ->
                showCfWebView(context, DramaFrenStore.reelBase(), onSaved)
            }
            .show()
    }
}
