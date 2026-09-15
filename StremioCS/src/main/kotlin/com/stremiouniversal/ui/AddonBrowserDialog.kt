package com.stremiouniversal.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.stremiouniversal.AddonUrlOpener
import com.stremiouniversal.StremioConstants
import com.stremiouniversal.normalizeAddonUrl

/**
 * In-app mini-browser locked to [StremioConstants.BROWSE_ADDONS_URL].
 *
 * - No new Activity / manifest entry required (AlertDialog + WebView, works over BottomSheet).
 * - Allowlist: `stremio-addons.net` + `www.stremio-addons.net`, https only. Outside hosts are
 *   blocked with a Toast (user can use "External" to open them via [AddonUrlOpener]).
 * - Never scrapes: we never fetch/parse HTML, we only read [WebView.getUrl] for Copy Link.
 * - Copy Link → [normalizeAddonUrl] → clipboard + [onPickUrl] (caller decides to add).
 */
object AddonBrowserDialog {

    fun isAllowedBrowseUrl(url: String?): Boolean = runCatching {
        val uri = Uri.parse(url.orEmpty()) ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase().orEmpty()
        host == "stremio-addons.net" || host == "www.stremio-addons.net"
    }.getOrDefault(false)

    fun show(ctx: Context, onPickUrl: (String) -> Unit) {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val errorView = TextView(ctx).apply {
            text = "Couldn't load — check connection, then Retry or open Externally."
            textSize = 13f
            visibility = android.view.View.GONE
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        lateinit var web: WebView
        web = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            isLongClickable = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url?.toString()
                    return if (isAllowedBrowseUrl(target)) {
                        errorView.visibility = android.view.View.GONE
                        false
                    } else {
                        Toast.makeText(ctx, "Blocked — browser stays in stremio-addons.net", Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return if (isAllowedBrowseUrl(url)) {
                        errorView.visibility = android.view.View.GONE
                        false
                    } else {
                        Toast.makeText(ctx, "Blocked — browser stays in stremio-addons.net", Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    errorView.visibility = android.view.View.GONE
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame) {
                        errorView.visibility = android.view.View.VISIBLE
                    }
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                    errorView.visibility = android.view.View.VISIBLE
                }
            }
            loadUrl(StremioConstants.BROWSE_ADDONS_URL)
        }

        val back = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            background = null
            contentDescription = "Back"
            setOnClickListener { if (web.canGoBack()) web.goBack() }
        }
        val reload = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.stat_notify_sync)
            background = null
            contentDescription = "Reload"
            setOnClickListener { web.reload() }
        }
        val copyLink = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Copy Link"
            isAllCaps = false
            setOnClickListener {
                val current = web.url
                val normalized = normalizeAddonUrl(current)
                if (normalized == null) {
                    Toast.makeText(ctx, "Not a manifest URL yet — keep browsing, then Copy Link", Toast.LENGTH_SHORT).show()
                } else {
                    AddonUrlOpener.copyToClipboard(ctx, "StremioCS link", normalized)
                    onPickUrl(normalized)
                }
            }
        }
        val external = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "External"
            isAllCaps = false
            setOnClickListener {
                AddonUrlOpener.openExternalUrl(ctx, web.url ?: StremioConstants.BROWSE_ADDONS_URL)
            }
        }
        val close = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            contentDescription = "Close browser"
        }

        val toolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
            addView(back)
            addView(reload)
            addView(copyLink.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(external)
            addView(close)
        }
        val hint = TextView(ctx).apply {
            text = "Browse ${StremioConstants.BROWSE_ADDONS_URL} → open an addon → Copy Link → it fills Add below. Long-press also copies."
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(toolbar)
            addView(hint)
            addView(errorView)
            addView(web.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (280 * ctx.resources.displayMetrics.density).toInt()
                )
            })
        }

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && web.canGoBack()) {
                web.goBack()
                true
            } else false
        }
        dialog.setOnDismissListener {
            runCatching {
                web.stopLoading()
                web.destroy()
            }
        }
        dialog.show()
    }
}
