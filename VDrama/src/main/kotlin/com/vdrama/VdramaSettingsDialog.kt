package com.vdrama

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.URLUtil
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

object VdramaSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBaseUrl: String) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val smallPad = (8 * density).toInt()

        val domains = VdramaStore.getDomains()
        val active = VdramaStore.getActiveIndex().coerceIn(0, 2)

        val inputs = List(3) { idx ->
            EditText(context).apply {
                hint = when (idx) {
                    0 -> "Domain 1 (default: $DEFAULT_BASE_URL)"
                    1 -> "Domain 2 (mirror)"
                    else -> "Domain 3 (mirror)"
                }
                setText(
                    when (idx) {
                        0 -> VdramaStore.loadDomain(0) ?: ""
                        1 -> VdramaStore.loadDomain(1) ?: ""
                        else -> VdramaStore.loadDomain(2) ?: ""
                    }
                )
                isSingleLine = true
                setSelectAllOnFocus(true)
                setHintTextColor(Color.parseColor("#9E9E9E"))
                setTextColor(Color.parseColor("#212121"))
                setPadding(smallPad, smallPad, smallPad, smallPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
            }
        }

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = smallPad }
            for (i in 0..2) {
                addView(RadioButton(context).apply {
                    id = 2000 + i
                    text = "Use Domain ${i + 1}" + if (i == active) "  (active)" else ""
                    isChecked = i == active
                    setTextColor(Color.parseColor("#424242"))
                    textSize = 13f
                })
            }
            check(2000 + active)
        }

        val savedCf = VdramaStore.getCfCookieForUrl(currentBaseUrl) ?: VdramaStore.loadCfCookie()
        val cfStatus = TextView(context).apply {
            text = if (!savedCf.isNullOrBlank()) {
                val preview = if (savedCf.length > 64) savedCf.take(64) + "…" else savedCf
                "Cloudflare: ✓ saved (${savedCf.length} chars) — $preview"
            } else {
                "Cloudflare: no cookies saved — tap Solve if you see Just a moment / 403"
            }
            setTextColor(if (!savedCf.isNullOrBlank()) Color.parseColor("#2E7D32") else Color.parseColor("#757575"))
            textSize = 12f
            setPadding(0, smallPad, 0, smallPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val cfBtn = Button(context).apply {
            text = "Solve Cloudflare"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E88E5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = smallPad }
            setOnClickListener {
                val idx = (radioGroup.checkedRadioButtonId - 2000).coerceIn(0, 2)
                val raw = inputs.getOrNull(idx)?.text?.toString()
                val normalized = normalizeBaseUrl(raw)
                val chosen = when {
                    normalized != null && URLUtil.isValidUrl(normalized) -> normalized
                    else -> domains.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: currentBaseUrl
                }
                showCfWebView(context, chosen)
            }
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(context).apply {
                text = "Current: $currentBaseUrl"
                textSize = 13f
                setTextColor(Color.parseColor("#616161"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(TextView(context).apply {
                text = "Choose active domain:"
                setTextColor(Color.parseColor("#424242"))
                textSize = 12f
                setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(radioGroup)
            for (i in 0..2) {
                addView(TextView(context).apply {
                    text = "Domain ${i + 1}"
                    setTextColor(Color.parseColor("#616161"))
                    textSize = 11f
                    setPadding(0, smallPad, 0, 2)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
                addView(inputs[i])
            }
            addView(cfStatus)
            addView(cfBtn)
            addView(TextView(context).apply {
                text = "Paste up to 3 mirrors, select the active one and Save. If you see Cloudflare challenge or 403, tap Solve Cloudflare and complete the check, then Save Cookies."
                textSize = 11f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, smallPad, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(inner)
        }
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(scroll)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("VDrama Settings")
            .setView(root)
            .setPositiveButton("Save", null)
            .setNeutralButton("Reset") { _, _ ->
                for (i in 0..2) VdramaStore.saveDomain(i, null)
                VdramaStore.saveActiveIndex(0)
                onDomainChanged?.invoke()
                Toast.makeText(context, "Reset to default", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                var hasError = false
                for (i in 0..2) {
                    val raw = inputs[i].text?.toString() ?: ""
                    if (raw.isNotBlank()) {
                        val normalized = normalizeBaseUrl(raw)
                        if (normalized == null || !URLUtil.isValidUrl(normalized)) {
                            Toast.makeText(context, "Invalid URL for Domain ${i + 1}", Toast.LENGTH_SHORT).show()
                            hasError = true
                            break
                        }
                    }
                }
                if (hasError) return@setOnClickListener
                for (i in 0..2) VdramaStore.saveDomain(i, inputs[i].text?.toString())
                val selected = (radioGroup.checkedRadioButtonId - 2000).coerceIn(0, 2)
                VdramaStore.saveActiveIndex(selected)
                onDomainChanged?.invoke()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showCfWebView(context: Context, url: String) {
        val target = normalizeBaseUrl(url) ?: VdramaStore.activeBase()
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = browserHeaders()["User-Agent"]
            webViewClient = WebViewClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Exception) {}
        wv.loadUrl(target)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(wv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * context.resources.displayMetrics.density).toInt()
            ))
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
                val current = wv.url ?: target
                val ck = CookieManager.getInstance().getCookie(current)
                    ?: CookieManager.getInstance().getCookie(target)
                if (!ck.isNullOrBlank()) {
                    VdramaStore.saveCfCookie(current, ck)
                    VdramaStore.saveCfCookie(ck)
                    Toast.makeText(context, "Cloudflare cookies saved (${ck.length} chars)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No cookies found — wait for page to finish loading", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
