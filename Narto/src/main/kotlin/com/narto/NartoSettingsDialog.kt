package com.narto

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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

object NartoSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBaseUrl: String) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val domains = NartoStore.getDomains()
        val active = NartoStore.getActiveIndex()

        val inputs = List(3) { idx ->
            EditText(context).apply {
                hint = when(idx){
                    0->"Domain 1 (default: $DEFAULT_BASE_URL)"
                    1->"Domain 2 (mirror)"
                    else->"Domain 3 (custom)"
                }
                setText(domains[idx])
                isSingleLine = true
                setSelectAllOnFocus(true)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 }
            }
        }

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            for (i in 0..2) {
                addView(RadioButton(context).apply {
                    id = 4000 + i
                    text = "Use Domain ${i+1}${if(i==active) " (active)" else ""}"
                    isChecked = i==active
                })
            }
            check(4000 + active)
        }

        val cfStatus = TextView(context).apply {
            val hasCf = NartoStore.loadCfCookie() != null
            text = if (hasCf) "✓ Cloudflare cookies saved" else "No Cloudflare cookies"
            setTextColor(if(hasCf) Color.parseColor("#2E7D32") else Color.GRAY)
            textSize = 12f
        }

        val cfBtn = Button(context).apply {
            text = "🛡️ Solve Cloudflare"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
            setOnClickListener {
                val idx = radioGroup.checkedRadioButtonId - 4000
                val chosen = inputs.getOrNull(idx)?.text?.toString()?.takeIf { it.isNotBlank() }?.let { normalizeNartoBase(it) } ?: domains[idx].takeIf { it.isNotBlank() } ?: currentBaseUrl
                showCloudflareWebView(context, chosen)
            }
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad/2, pad, 0)
            addView(TextView(context).apply {
                text = "Current: $currentBaseUrl"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            addView(TextView(context).apply { text = "Choose active domain:"; setTextColor(Color.DKGRAY); textSize = 12f; setPadding(0,12,0,4) })
            addView(radioGroup)
            for (i in 0..2) {
                addView(TextView(context).apply { text = "Domain ${i+1}"; setTextColor(Color.DKGRAY); textSize = 11f })
                addView(inputs[i])
            }
            addView(cfStatus)
            addView(cfBtn)
        }

        val scroll = ScrollView(context).apply {
            addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        val root = FrameLayout(context).apply { addView(scroll) }

        AlertDialog.Builder(context)
            .setTitle("Narto Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                for (i in 0..2) NartoStore.saveDomain(i, inputs[i].text?.toString())
                NartoStore.setActiveIndex((radioGroup.checkedRadioButtonId - 4000).coerceIn(0,2))
                onDomainChanged?.invoke()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset") { _, _ ->
                for (i in 0..2) NartoStore.saveDomain(i, null)
                NartoStore.setActiveIndex(0)
                onDomainChanged?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyDomain(raw: String?) {
        NartoStore.saveDomain(NartoStore.getActiveIndex(), raw)
        onDomainChanged?.invoke()
    }

    private fun showCloudflareWebView(context: Context, target: String) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            webViewClient = WebViewClient()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * context.resources.displayMetrics.density).toInt()
            )
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(webView)
            addView(TextView(context).apply {
                text = "Complete the Cloudflare challenge, then tap Save Cookies."
                setPadding(20, 10, 20, 10)
                setTextColor(Color.parseColor("#616161"))
                textSize = 12f
            })
        }

        AlertDialog.Builder(context)
            .setTitle("Cloudflare — $target")
            .setView(container)
            .setPositiveButton("Save Cookies") { _, _ ->
                val current = webView.url ?: target
                val ck = CookieManager.getInstance().getCookie(current)
                    ?: CookieManager.getInstance().getCookie(target)
                if (!ck.isNullOrBlank()) {
                    NartoStore.saveCfCookie(current, ck)
                    Toast.makeText(context, "Cookies saved (${ck.length} chars)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No cookies found — wait longer", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Close", null)
            .create().also { d ->
                webView.loadUrl(target)
                d.show()
            }
    }
}
