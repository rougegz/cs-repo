package com.vdrama

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object VdramaSettingsDialog {
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBaseUrl: String) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()

        val input = EditText(context).apply {
            hint = "Leave empty for $DEFAULT_BASE_URL"
            setText(VdramaStore.loadOverride() ?: "")
            isSingleLine = true
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(context).apply {
                text = "Current: $currentBaseUrl"
                textSize = 13f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(input)
            addView(TextView(context).apply {
                text = "Enter a mirror domain, e.g. https://mirror.example.com"
                textSize = 12f
                setTextColor(Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }

        AlertDialog.Builder(context)
            .setTitle("VDrama Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ -> applyDomain(input.text?.toString()) }
            .setNeutralButton("Use default") { _, _ -> applyDomain("") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun applyDomain(raw: String?): String {
        val normalized = normalizeBaseUrl(raw)
        VdramaStore.saveOverride(normalized)
        onDomainChanged?.invoke()
        return normalized ?: DEFAULT_BASE_URL
    }
}
