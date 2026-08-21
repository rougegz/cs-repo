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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object DramaFrenSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBase: String) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val domains = DramaFrenStore.getDomains()
        val active = DramaFrenStore.getActiveIndex()

        val inputs = List(3) { idx ->
            EditText(context).apply {
                hint = when(idx) {
                    0 -> "Domain 1 (default: $DEFAULT_API_BASE)"
                    1 -> "Domain 2 (default: $DEFAULT_REEL_BASE)"
                    else -> "Domain 3 (custom, optional)"
                }
                setText(when(idx){
                    0 -> DramaFrenStore.loadApiOverride() ?: ""
                    1 -> DramaFrenStore.loadReelOverride() ?: ""
                    else -> {
                        val d3 = domains[2]
                        if (d3.isNotBlank() && d3 != DEFAULT_API_BASE && d3 != DEFAULT_REEL_BASE) d3 else ""
                    }
                })
                isSingleLine = true
                setSelectAllOnFocus(true)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 }
            }
        }

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            for (i in 0..2) {
                addView(RadioButton(context).apply {
                    id = 1000 + i
                    text = "Use Domain ${i+1}${if (i==active) " (active)" else ""}"
                    isChecked = i == active
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
            check(1000 + active)
        }

        val cfStatus = TextView(context).apply {
            val hasCf = DramaFrenStore.getCfCookieForUrl(currentBase) != null
            text = if (hasCf) "✓ Cloudflare cookies saved for $currentBase" else "No Cloudflare cookies — tap Solve if you see 403"
            textSize = 12f
            setTextColor(if (hasCf) Color.parseColor("#2E7D32") else Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
        }

        val cfButton = Button(context).apply {
            text = "🛡️ Solve Cloudflare (WebView)"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
            setOnClickListener {
                val idx = radioGroup.checkedRadioButtonId - 1000
                val chosen = inputs.getOrNull(idx)?.text?.toString()?.takeIf { it.isNotBlank() }?.let { normalizeBaseUrl(it) } ?: domains[idx].takeIf { it.isNotBlank() } ?: currentBase
                showCfWebView(context, chosen)
            }
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad/2, pad, 0)
            addView(TextView(context).apply {
                text = "Current: $currentBase"
                textSize = 13f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(context).apply {
                text = "Choose active domain (paste 2-3 mirrors):"
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(0, 12, 0, 4)
            })
            addView(radioGroup)
            for (i in 0..2) {
                addView(TextView(context).apply {
                    text = "Domain ${i+1}"
                    textSize = 11f
                    setTextColor(Color.DKGRAY)
                })
                addView(inputs[i])
            }
            addView(cfStatus)
            addView(cfButton)
            addView(TextView(context).apply {
                text = "Tip: Paste a working mirror in Domain 3 and select it. Use Solve Cloudflare after changing domain if you see Just a moment / 403."
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 8, 0, 0)
            })
        }

        val scroll = ScrollView(context).apply {
            addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        val root = FrameLayout(context).apply {
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(context)
            .setTitle("DramaFren Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                for (i in 0..2) DramaFrenStore.saveDomain(i, inputs[i].text?.toString())
                val chosenIdx = radioGroup.checkedRadioButtonId - 1000
                DramaFrenStore.setActiveIndex(chosenIdx.coerceIn(0,2))
                onDomainChanged?.invoke()
                Toast.makeText(context, "Saved — reloading…", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset") { _, _ ->
                for (i in 0..2) DramaFrenStore.saveDomain(i, null)
                DramaFrenStore.setActiveIndex(0)
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 900)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl(url)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(webView)
            addView(TextView(context).apply {
                text = "Solve the Cloudflare challenge, then tap Save Cookies"
                setPadding(20, 10, 20, 10)
                setTextColor(Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
                    Toast.makeText(context, "Cookies saved for ${java.net.URL(url).host}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No cookies found — wait for page to finish", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Open Reel") { _, _ -> showCfWebView(context, DramaFrenStore.reelBase()) }
            .show()
    }
}
