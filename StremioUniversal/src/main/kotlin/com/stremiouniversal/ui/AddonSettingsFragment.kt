package com.stremiouniversal

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class AddonSettingsFragment : BottomSheetDialogFragment() {
    private lateinit var repository: StremioRepository
    private lateinit var rowsBox: LinearLayout

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
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(ctx).apply {
            text = "Stremio addons"
            textSize = 18f
        })
        root.addView(TextView(ctx).apply {
            text = "Order sets catalogue order. Names come from each addon."
            textSize = 13f
        })
        rowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowsBox)
        root.addView(addRow(ctx))
        root.addView(sectionLabel(ctx, "Options"))
        root.addView(switchRow(ctx, "External subtitles", repository.subtitlesEnabled()) { enabled ->
            repository.setSubtitlesEnabled(enabled)
        })
        rebuildRows(ctx)
        return ScrollView(ctx).apply { addView(root) }
    }

    private fun rebuildRows(ctx: Context) {
        rowsBox.removeAllViews()
        val configs = repository.loadConfiguredAddons().toMutableList()
        val builtIns = BUILT_IN_ADDONS.map { it.manifestUrl }.toSet()
        configs.forEachIndexed { index, config ->
            rowsBox.addView(addonRow(ctx, config, index, config.manifestUrl !in builtIns) { updated ->
                configs[index] = updated
                repository.saveAddons(configs)
            } {
                repository.saveAddons(configs.filterIndexed { i, _ -> i != index })
                rebuildRows(ctx)
            })
            rowsBox.addView(divider(ctx))
        }
    }

    private fun addonRow(
        ctx: Context,
        config: AddonConfig,
        index: Int,
        deletable: Boolean,
        onToggle: (AddonConfig) -> Unit,
        onDelete: () -> Unit
    ): LinearLayout {
        val host = config.manifestUrl.substringAfter("://").substringBefore("/")
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = config.name.ifEmpty { host }
                    textSize = 16f
                })
                addView(TextView(ctx).apply {
                    text = config.manifestUrl
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(SwitchMaterial(ctx).apply {
                isChecked = config.enabled
                setOnCheckedChangeListener { _, checked -> onToggle(config.copy(enabled = checked)) }
            })
            if (deletable) {
                addView(ImageButton(ctx).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = null
                    contentDescription = "Remove addon"
                    setOnClickListener { onDelete() }
                })
            }
        }
    }

    private fun addRow(ctx: Context): LinearLayout {
        val input = EditText(ctx).apply {
            hint = "Paste addon manifest URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 4)
            addView(input)
            addView(MaterialButton(ctx).apply {
                text = "Add"
                setOnClickListener {
                    val url = parseAddonUrl(input.text.toString())
                    if (url == null) {
                        Toast.makeText(ctx, "That is not a manifest URL", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val current = repository.loadConfiguredAddons()
                    if (current.any { it.manifestUrl == url }) {
                        Toast.makeText(ctx, "Addon already listed", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    repository.saveAddons(current + AddonConfig("", url))
                    input.setText("")
                    rebuildRows(ctx)
                }
            })
        }
    }

    private fun switchRow(ctx: Context, label: String, checked: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            addView(TextView(ctx).apply {
                text = label
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(SwitchMaterial(ctx).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
            })
        }
    }

    private fun sectionLabel(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
    }

    private fun divider(ctx: Context): View {
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(0x1F000000)
        }
    }
}
