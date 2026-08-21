package com.vdrama

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal programmatic settings UI (no resources needed).
 * Lets the user point the provider at a different domain, e.g. a mirror.
 * Blank input = use the default site.
 */
object VdramaSettingsDialog {

    /** Invoked after a successful change; the plugin wires it to reload home. */
    var onDomainChanged: (() -> Unit)? = null

    fun show(context: Context, currentBaseUrl: String) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()

        val input = EditText(context).apply {
            hint = "Leave empty for $DEFAULT_BASE_URL"
            setText(VdramaStore.loadOverride() ?: "")
            singleLine = true
            setSelectAllOnFocus(true)
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
        }

        AlertDialog.Builder(context)
            .setTitle("VDrama Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ -> applyDomain(input.text?.toString()) }
            .setNeutralButton("Use default") { _, _ -> applyDomain("") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Normalize + persist + notify. Returns the effective base url. */
    fun applyDomain(raw: String?): String {
        val normalized = normalizeBaseUrl(raw)
        VdramaStore.saveOverride(normalized)
        onDomainChanged?.invoke()
        return normalized ?: DEFAULT_BASE_URL
    }
}
