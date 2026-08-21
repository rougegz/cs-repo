package com.chartdrama

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

object ChartDramaSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBase: String) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val input = EditText(context).apply {
            hint = "Leave empty for $DEFAULT_BASE_URL"
            setText(ChartStore.loadBase() ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad/2, pad, 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(context).apply {
                text = "Current: $currentBase"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(input)
            addView(TextView(context).apply {
                text = "Enter mirror domain, e.g. https://mirror.example.com"
                textSize = 12f
                setTextColor(Color.DKGRAY)
            })
        }

        val useDefault = TextView(context).apply {
            text = "↺ Use default domain"
            textSize = 12f
            setTextColor(Color.BLUE)
            setPadding(0, 12, 0, 12)
            setOnClickListener { input.setText("") }
        }
        root.addView(useDefault)

        AlertDialog.Builder(context)
            .setTitle("ChartDrama Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val norm = normalizeBaseUrl(input.text?.toString())
                ChartStore.saveBase(norm)
                onDomainChanged?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Cloudflare") { _, _ ->
                val norm = normalizeBaseUrl(input.text?.toString())
                if (norm != null || input.text.toString().isBlank()) {
                    ChartStore.saveBase(norm)
                    onDomainChanged?.invoke()
                }
                showCfWebView(context)
            }
            .show()
    }

    private fun showCfWebView(context: Context) {
        val base = ChartStore.loadBase()?.let { normalizeBaseUrl(it) } ?: DEFAULT_BASE_URL
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            webViewClient = WebViewClient()
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Solve Cloudflare — complete the challenge, then tap Save")
            .setView(webView)
            .setPositiveButton("Save Cookies") { _, _ ->
                val cookies = CookieManager.getInstance().getCookie(base) ?: ""
                if (cookies.contains("cf_clearance") || cookies.contains("__cf")) {
                    ChartStore.saveCfCookie(base.substringAfter("://").substringBefore('/'), cookies)
                    Toast.makeText(context, "Cloudflare cookies saved", Toast.LENGTH_SHORT).show()
                } else {
                    // Try to get cookies for the base host without www
                    val host = base.removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
                    val cookies2 = CookieManager.getInstance().getCookie("https://$host") ?: ""
                    if (cookies2.isNotEmpty()) {
                        ChartStore.saveCfCookie(host, cookies2)
                        Toast.makeText(context, "Cookies saved for $host", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No Cloudflare cookies found — try again after challenge passes", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .create()
        webView.loadUrl(base)
        dialog.show()
        // Make WebView fill dialog
        webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
    }
}
