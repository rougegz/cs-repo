package com.chartdrama

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
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

object ChartDramaSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBase: String) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val density = context.resources.displayMetrics.density

        // 3 EditTexts for 3 mirrors — persisted via chartdrama_domain_1/2/3
        val input1 = EditText(context).apply {
            hint = "Domain 1 (e.g. https://www.chartdrama.com)"
            setText(ChartStore.loadDomain(1) ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
            setPadding(pad/2, pad/2, pad/2, pad/2)
            setBackgroundColor(Color.parseColor("#F8F9FA"))
        }
        val input2 = EditText(context).apply {
            hint = "Domain 2 (mirror 2)"
            setText(ChartStore.loadDomain(2) ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
            setPadding(pad/2, pad/2, pad/2, pad/2)
            setBackgroundColor(Color.parseColor("#F8F9FA"))
        }
        val input3 = EditText(context).apply {
            hint = "Domain 3 (mirror 3)"
            setText(ChartStore.loadDomain(3) ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
            setPadding(pad/2, pad/2, pad/2, pad/2)
            setBackgroundColor(Color.parseColor("#F8F9FA"))
        }

        // RadioGroup for active selection — persisted via chartdrama_active
        val active = ChartStore.loadActive()
        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 8, 0, 8)
            addView(RadioButton(context).apply {
                text = "Use Domain 1"
                id = 1
                isChecked = active == 1
                setTextColor(Color.parseColor("#212529"))
                textSize = 13f
            })
            addView(RadioButton(context).apply {
                text = "Use Domain 2"
                id = 2
                isChecked = active == 2
                setTextColor(Color.parseColor("#212529"))
                textSize = 13f
            })
            addView(RadioButton(context).apply {
                text = "Use Domain 3"
                id = 3
                isChecked = active == 3
                setTextColor(Color.parseColor("#212529"))
                textSize = 13f
            })
        }

        // Cloudflare status TextView + WebView button — saves to cf_cookie_chartdrama.com
        val hasCookie = ChartStore.hasCfCookie() || ChartStore.loadCfCookie("chartdrama.com") != null
        val cfStatus = TextView(context).apply {
            text = if (hasCookie) "✅ Cloudflare: Solved (cf_cookie_chartdrama.com present)" else "⚠️ Cloudflare: Not solved — tap Solve if you see Just a moment / 403"
            textSize = 12f
            setTextColor(if (hasCookie) Color.parseColor("#198754") else Color.parseColor("#6C757D"))
            setPadding(0, 12, 0, 8)
        }
        val cfButton = Button(context).apply {
            text = "🛡️ Solve Cloudflare (WebView)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0D6EFD"))
            setOnClickListener { showCfWebView(context) }
        }

        val header = TextView(context).apply {
            text = "ChartDrama Mirrors — pick active domain"
            textSize = 14f
            setTextColor(Color.parseColor("#0D6EFD"))
            setPadding(0, 0, 0, 6)
        }
        val currentTv = TextView(context).apply {
            text = "Current: " + currentBase
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 10)
        }
        val helpTv = TextView(context).apply {
            text = "Leave empty to use default. Domains saved as chartdrama_domain_1/2/3, active as chartdrama_active. Cloudflare cookies saved as cf_cookie_chartdrama.com and injected via headers."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 12, 0, 0)
        }
        val divider1 = View(context).apply {
            setBackgroundColor(Color.parseColor("#DEE2E6"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, 12, 0, 12) }
        }
        val divider2 = View(context).apply {
            setBackgroundColor(Color.parseColor("#DEE2E6"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, 12, 0, 12) }
        }

        // Inner LinearLayout — FIX: must use FrameLayout.LayoutParams as dialog root (AlertDialog wraps view in FrameLayout)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad/2, pad, pad)
            // AlertDialog crash fix: root LinearLayout must use FrameLayout.LayoutParams (not ViewGroup.LayoutParams)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(header)
            addView(currentTv)
            addView(TextView(context).apply { text = "Domain 1"; setTextColor(Color.DKGRAY); textSize = 12f; setPadding(0, 8, 0, 2) })
            addView(input1)
            addView(TextView(context).apply { text = "Domain 2"; setTextColor(Color.DKGRAY); textSize = 12f; setPadding(0, 8, 0, 2) })
            addView(input2)
            addView(TextView(context).apply { text = "Domain 3"; setTextColor(Color.DKGRAY); textSize = 12f; setPadding(0, 8, 0, 2) })
            addView(input3)
            addView(TextView(context).apply { text = "Active Domain"; setTextColor(Color.parseColor("#495057")); textSize = 12f; setPadding(0, 12, 0, 4) })
            addView(radioGroup)
            addView(divider1)
            addView(cfStatus)
            addView(cfButton)
            addView(divider2)
            addView(helpTv)
        }

        // Best UI: ScrollView with padding, colors — ensures no overflow on small screens
        val scrollView = ScrollView(context).apply {
            // ScrollView is the direct child of AlertDialog's FrameLayout — must carry FrameLayout.LayoutParams
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            isFillViewport = true
            addView(inner)
            setBackgroundColor(Color.WHITE)
        }

        AlertDialog.Builder(context)
            .setTitle("ChartDrama Settings")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                ChartStore.saveDomain(1, input1.text?.toString())
                ChartStore.saveDomain(2, input2.text?.toString())
                ChartStore.saveDomain(3, input3.text?.toString())
                val checked = radioGroup.checkedRadioButtonId
                if (checked in 1..3) ChartStore.saveActive(checked)
                onDomainChanged?.invoke()
                Toast.makeText(context, "Saved — reloading…", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Use defaults") { _, _ ->
                ChartStore.saveDomain(1, null)
                ChartStore.saveDomain(2, null)
                ChartStore.saveDomain(3, null)
                ChartStore.saveActive(1)
                onDomainChanged?.invoke()
                Toast.makeText(context, "Reset to defaults", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCfWebView(context: Context) {
        val base = ChartStore.activeBase() ?: ChartStore.loadBase()?.let { normalizeBaseUrl(it) } ?: DEFAULT_BASE_URL
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            webViewClient = WebViewClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) } catch (_: Exception) {}
        webView.loadUrl(base)

        // Container must use FrameLayout.LayoutParams as AlertDialog root
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.WHITE)
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (320 * context.resources.displayMetrics.density).toInt()))
            addView(TextView(context).apply {
                text = "Complete the Cloudflare challenge, then tap Save Cookies. Cookies will be saved as cf_cookie_chartdrama.com and injected on next request."
                textSize = 11f
                setTextColor(Color.DKGRAY)
                setPadding(16, 10, 16, 10)
            })
        }

        AlertDialog.Builder(context)
            .setTitle("Solve Cloudflare — " + base)
            .setView(container)
            .setPositiveButton("💾 Save Cookies") { _, _ ->
                val currentUrl = webView.url ?: base
                val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: CookieManager.getInstance().getCookie(base) ?: ""
                if (!cookies.isNullOrBlank()) {
                    val host = try { java.net.URL(currentUrl).host } catch (_: Exception) { base.substringAfter("://").substringBefore('/') }
                    ChartStore.saveCfCookie(host, cookies)
                    ChartStore.saveCfCookie("chartdrama.com", cookies)
                    // save as cf_cookie_chartdrama.com explicitly
                    Toast.makeText(context, "Cloudflare cookies saved (cf_cookie_chartdrama.com)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No cookies found — wait for page to finish loading", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Open Default") { _, _ -> showCfWebView(context) }
            .show()
    }
}
