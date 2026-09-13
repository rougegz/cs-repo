package com.stremiouniversal

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AddonSettingsFragment : BottomSheetDialogFragment() {
    private lateinit var repository: StremioRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = requireContext().getSharedPreferences("StremioUniversal", Context.MODE_PRIVATE)
        repository = StremioRepository(prefs)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        box.addView(TextView(ctx).apply {
            text = "Stremio addons — one per line as Name|https://…/manifest.json. Order sets catalogue order."
            textSize = 14f
        })
        val editor = EditText(ctx).apply {
            minLines = 6
            hint = "Cinemeta|https://v3-cinemeta.strem.io/manifest.json"
            setText(repository.loadConfiguredAddons().joinToString("\n") { "${it.name}|${it.manifestUrl}" })
        }
        box.addView(editor)
        val subtitles = CheckBox(ctx).apply {
            text = "External subtitles"
            isChecked = repository.subtitlesEnabled()
        }
        box.addView(subtitles)
        box.addView(Button(ctx).apply {
            text = "Save"
            setOnClickListener {
                val configs = editor.text.toString().lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { line ->
                        val url = line.substringAfter("|", line).trim()
                        if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
                        val name = line.substringBefore("|").trim().takeIf { it != url }.orEmpty()
                        AddonConfig(name.ifEmpty { url }, url)
                    }
                if (configs.isEmpty()) {
                    Toast.makeText(ctx, "No valid addon URLs — list unchanged", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                repository.saveAddons(configs)
                repository.setSubtitlesEnabled(subtitles.isChecked)
                Toast.makeText(ctx, "Saved ${configs.size} addons", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        })
        return ScrollView(ctx).apply { addView(box) }
    }
}
