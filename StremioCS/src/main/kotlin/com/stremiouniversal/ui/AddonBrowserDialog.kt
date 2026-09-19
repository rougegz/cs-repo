package com.stremiouniversal.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.stremiouniversal.StremioConstants

object AddonBrowserDialog {

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    fun show(ctx: Context) {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val statusText = TextView(ctx).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
            max = 100
            visibility = android.view.View.GONE
        }
        val errorView = TextView(ctx).apply {
            text = "Couldn't load — check connection, then retry."
            textSize = 13f
            visibility = android.view.View.GONE
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        var pendingSsl: SslErrorHandler? = null
        val unsafeButton = MaterialButton(ctx).apply {
            text = "Load anyway (unsafe)"
            isAllCaps = false
            isFocusable = true
            visibility = android.view.View.GONE
            setOnClickListener {
                pendingSsl?.proceed()
                pendingSsl = null
                visibility = android.view.View.GONE
                errorView.visibility = android.view.View.GONE
            }
        }
        val container = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        lateinit var web: WebView
        web = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                textZoom = 100
                userAgentString = MOBILE_UA
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                loadsImagesAutomatically = true
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, progress: Int) {
                    progressBar.progress = progress
                    progressBar.visibility =
                        if (progress < 100) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    statusText.text = url
                    errorView.visibility = android.view.View.GONE
                }
                override fun onPageFinished(view: WebView, url: String) {
                    statusText.text = url
                    view.evaluateJavascript(
                        "(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');document.getElementsByTagName('head')[0].appendChild(m);}m.setAttribute('content','width=device-width,initial-scale=1,maximum-scale=5');var c=document.createElement('style');c.type='text/css';c.appendChild(document.createTextNode('img,svg,video,canvas{max-width:100%!important;height:auto!important}body{overflow-x:hidden;max-width:100vw}'));document.getElementsByTagName('head')[0].appendChild(c);})()"
                    ) {}
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame) {
                        errorView.visibility = android.view.View.VISIBLE
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    pendingSsl = handler
                    errorView.text = "Secure connection failed — this TV trusts too few certificates. Reload will not help."
                    errorView.visibility = android.view.View.VISIBLE
                    unsafeButton.visibility = android.view.View.VISIBLE
                }
                @Suppress("DEPRECATION")
                override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                    errorView.visibility = android.view.View.VISIBLE
                }
            }
        }
        container.addView(web)
        val tvCursor = TvTouchCursor(ctx, container, web)
        val cursorBtn = MaterialButton(ctx).apply {
            text = "TV Cursor"
            isAllCaps = false
            isFocusable = true
            setOnClickListener {
                tvCursor.toggle()
                Toast.makeText(
                    ctx,
                    if (tvCursor.isActive) "Touch cursor ON — D-pad to move, OK to tap" else "Touch cursor OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        val reload = MaterialButton(ctx).apply {
            text = "Reload"
            isAllCaps = false
            isFocusable = true
            setOnClickListener {
                pendingSsl = null
                unsafeButton.visibility = android.view.View.GONE
                web.reload()
            }
        }
        val close = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            contentDescription = "Close browser"
        }
        val toolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            addView(TextView(ctx).apply {
                text = "Browse Addons"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(cursorBtn)
            addView(reload)
            addView(close)
        }
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
            addView(statusText)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(progressBar)
            addView(container)
            addView(errorView)
            addView(unsafeButton)
            addView(statusRow)
        }
        val dialog = object : Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (tvCursor.isActive) {
                        tvCursor.hide()
                        return true
                    }
                    if (web.canGoBack()) {
                        web.goBack()
                        return true
                    }
                }
                if (tvCursor.onKeyEvent(event)) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCancelable(true)
            setOnDismissListener {
                tvCursor.destroy()
                runCatching {
                    web.stopLoading()
                    web.destroy()
                }
            }
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            show()
        }
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        web.clearCache(true)
        web.clearHistory()
        if (isOldWebView(ctx)) {
            errorView.text = "System browser too old — the full site may not load."
            errorView.visibility = android.view.View.VISIBLE
            Toast.makeText(ctx, "Full site needs a newer system browser", Toast.LENGTH_LONG).show()
        }
        web.loadUrl(StremioConstants.BROWSE_ADDONS_URL)
        web.requestFocus()
    }

    private fun isOldWebView(ctx: Context): Boolean {
        try {
            val pm = ctx.packageManager
            for (pkg in listOf("com.google.android.webview", "com.android.webview")) {
                val major = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0).versionName
                        ?.substringBefore(".")?.toIntOrNull()
                }.getOrNull()
                if (major != null) return major < 90
            }
        } catch (_: Exception) {
        }
        return android.os.Build.VERSION.SDK_INT <= 25
    }
}
