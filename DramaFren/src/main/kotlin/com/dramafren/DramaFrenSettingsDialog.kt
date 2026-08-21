package com.dramafren

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object DramaFrenSettingsDialog {

    var onDomainChanged: (() -> Unit)? = null
    var onCloudflareSolved: (() -> Unit)? = null

    fun show(context: Context, currentBaseUrl: String) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()

        val input = EditText(context).apply {
            hint = "Leave empty for $DEFAULT_BASE_URL"
            setText(DramaFrenStore.loadOverride() ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
        }

        val status = TextView(context).apply {
            val cf = DramaFrenStore.loadCfCookie(currentBaseUrl) ?: DramaFrenStore.loadCfCookie(DEFAULT_BASE_URL)
            text = if (cf != null) "✓ Cloudflare cookies saved" else "No Cloudflare cookies saved"
            textSize = 12f
            setTextColor(if (cf != null) Color.parseColor("#2E7D32") else Color.GRAY)
        }

        val cfButton = TextView(context).apply {
            text = "🛡️ Solve Cloudflare Challenge — tap to open WebView"
            textSize = 14f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(pad, 12, pad, 12)
            setOnClickListener {
                showCloudflareWebView(context, currentBaseUrl)
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply {
                text = "Current: $currentBaseUrl"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(input)
            addView(TextView(context).apply {
                text = "Enter a mirror domain, e.g. https://mirror.example.com"
                textSize = 12f
                setTextColor(Color.DKGRAY)
            })
            addView(status)
            addView(cfButton)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("DramaFren Settings")
            .setView(root)
            .setPositiveButton("Save Domain") { _, _ -> applyDomain(input.text?.toString()) }
            .setNeutralButton("Use default") { _, _ -> applyDomain("") }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    fun applyDomain(raw: String?): String {
        val normalized = normalizeBaseUrl(raw)
        DramaFrenStore.saveOverride(normalized)
        onDomainChanged?.invoke()
        return normalized ?: DEFAULT_BASE_URL
    }

    private fun showCloudflareWebView(context: Context, baseUrl: String) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = browserHeaders()["User-Agent"]
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { html ->
                        if (html != null && !html.contains("Just a moment") && !html.contains("challenge-platform")) {
                            val cookies = CookieManager.getInstance().getCookie(url ?: baseUrl)
                            if (!cookies.isNullOrBlank()) {
                                val domain = try { java.net.URL(url ?: baseUrl).host } catch (_: Exception) { baseUrl }
                                DramaFrenStore.saveCfCookie(domain, cookies)
                                DramaFrenStore.saveCfCookie(baseUrl, cookies)
                                Toast.makeText(context, "✓ Cloudflare solved — cookies saved", Toast.LENGTH_SHORT).show()
                                onCloudflareSolved?.invoke()
                            }
                        }
                    }
                }
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Solve Cloudflare — wait for the site to load, then tap Save"
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(20, 20, 20, 10)
            })
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Cloudflare Check — $baseUrl")
            .setView(container)
            .setPositiveButton("Save Cookies") { _, _ ->
                val cookies = CookieManager.getInstance().getCookie(baseUrl)
                if (!cookies.isNullOrBlank()) {
                    val domain = try { java.net.URL(baseUrl).host } catch (_: Exception) { baseUrl }
                    DramaFrenStore.saveCfCookie(domain, cookies)
                    DramaFrenStore.saveCfCookie(baseUrl, cookies)
                    Toast.makeText(context, "✓ Cookies saved", Toast.LENGTH_SHORT).show()
                    onCloudflareSolved?.invoke()
                } else {
                    Toast.makeText(context, "No cookies found — try waiting longer", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear Cookies") { _, _ ->
                val domain = try { java.net.URL(baseUrl).host } catch (_: Exception) { baseUrl }
                DramaFrenStore.clearCfCookie(domain)
                DramaFrenStore.clearCfCookie(baseUrl)
                CookieManager.getInstance().removeAllCookies(null)
                Toast.makeText(context, "Cookies cleared", Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.show()
        webView.loadUrl(baseUrl)
    }
}
