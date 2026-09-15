package com.stremiouniversal.ui
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
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.stremiouniversal.AddonConfig
import com.stremiouniversal.AddonUrlOpener
import com.stremiouniversal.StremioConstants
import com.stremiouniversal.StremioRepository
import com.stremiouniversal.addonDisplayHost
import com.stremiouniversal.normalizeAddonUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class AddonSettingsFragment : BottomSheetDialogFragment() {
    private lateinit var repository: StremioRepository
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rowsBox: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var searchInput: EditText
    private var filter: String = ""
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = requireContext().getSharedPreferences(
            StremioConstants.PREFS_NAME, Context.MODE_PRIVATE
        )
        repository = StremioRepository(prefs)
    }
    override fun onDestroyView() {
        uiScope.cancel()
        super.onDestroyView()
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
            setPadding(pad, pad, pad, pad * 2)
        }
        root.addView(header(ctx, "StremioCS settings", 20f))
        root.addView(hint(ctx, "Addons are fully yours — no defaults. Browse, paste a manifest URL, reorder, toggle."))
        root.addView(header(ctx, "Addons", 16f))
        root.addView(searchRow(ctx))
        root.addView(browseRow(ctx))
        emptyView = TextView(ctx).apply {
            text = "No addons yet.\nTap Browse Addons to discover one, then paste its manifest URL below."
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }
        root.addView(emptyView)
        rowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowsBox)
        root.addView(header(ctx, "Add new", 16f))
        val status = TextView(ctx).apply { textSize = 12f }
        root.addView(addRow(ctx, status))
        root.addView(status)
        root.addView(header(ctx, "General", 16f))
        root.addView(generalRow(ctx))
        root.addView(header(ctx, "Data", 16f))
        root.addView(dataRow(ctx))
        root.addView(hint(ctx, "Browse: ${StremioConstants.BROWSE_ADDONS_URL} • Order sets catalogue & stream priority."))
        rebuildRows(ctx)
        return ScrollView(ctx).apply { addView(root) }
    }
    private fun searchRow(ctx: Context): LinearLayout {
        searchInput = EditText(ctx).apply {
            hint = "Search addons (name, host, url)"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    filter = s?.toString()?.trim()?.lowercase().orEmpty()
                    rebuildRows(ctx)
                }
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 4)
            addView(searchInput)
        }
    }
    private fun browseRow(ctx: Context): LinearLayout {
        val browse = MaterialButton(ctx).apply {
            text = "Browse Addons"
            isAllCaps = false
            setOnClickListener { AddonUrlOpener.openBrowseAddons(ctx) }
        }
        val paste = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Paste"
            isAllCaps = false
            setOnClickListener {
                val clip = AddonUrlOpener.maybeClipboardManifest(ctx)
                if (clip == null) {
                    Toast.makeText(ctx, "Clipboard has no manifest URL", Toast.LENGTH_SHORT).show()
                } else {
                    searchInput.setText("")
                    tryAdd(ctx, clip, null)
                }
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            addView(browse.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val gap = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(12, 1)
            }
            addView(gap)
            addView(paste.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
    }
    private fun rebuildRows(ctx: Context) {
        rowsBox.removeAllViews()
        val configs = repository.loadConfiguredAddons().toMutableList()
        val visible = if (filter.isEmpty()) configs.withIndex().toList()
        else configs.withIndex().filter { (_, c) ->
            c.name.lowercase().contains(filter) ||
                c.manifestUrl.lowercase().contains(filter) ||
                addonDisplayHost(c.manifestUrl).lowercase().contains(filter)
        }
        emptyView.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        if (configs.isEmpty()) return
        if (visible.isEmpty()) {
            rowsBox.addView(hint(ctx, "No matches for \"$filter\"."))
            return
        }
        visible.forEach { (index, config) ->
            rowsBox.addView(
                addonRow(
                    ctx = ctx,
                    config = config,
                    position = index,
                    total = configs.size,
                    onToggle = { checked ->
                        repository.setAddonEnabled(index, checked)
                    },
                    onDelete = {
                        repository.removeAddonAt(index)
                        rebuildRows(ctx)
                    },
                    onMove = { delta ->
                        repository.moveAddon(index, index + delta)
                        rebuildRows(ctx)
                    }
                )
            )
            rowsBox.addView(divider(ctx))
        }
    }
    private fun addonRow(
        ctx: Context,
        config: AddonConfig,
        position: Int,
        total: Int,
        onToggle: (Boolean) -> Unit,
        onDelete: () -> Unit,
        onMove: (Int) -> Unit
    ): LinearLayout {
        val host = addonDisplayHost(config.manifestUrl)
        val title = LinearLayout(ctx).apply {
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
            addView(TextView(ctx).apply {
                text = "#${position + 1} • $host"
                textSize = 11f
            })
        }
        val toggle = SwitchMaterial(ctx).apply {
            isChecked = config.enabled
            contentDescription = "Enable addon"
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
        val up = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.arrow_up_float)
            background = null
            contentDescription = "Move up"
            isEnabled = position > 0
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { onMove(-1) }
        }
        val down = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            background = null
            contentDescription = "Move down"
            isEnabled = position < total - 1
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { onMove(1) }
        }
        val delete = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            background = null
            contentDescription = "Remove addon"
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Remove addon?")
                    .setMessage(config.name.ifEmpty { host } + "\n" + config.manifestUrl)
                    .setPositiveButton("Remove") { _, _ -> onDelete() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            addView(title)
            addView(up)
            addView(down)
            addView(toggle)
            addView(delete)
        }
    }
    private fun addRow(ctx: Context, status: TextView): LinearLayout {
        val input = EditText(ctx).apply {
            hint = "Paste manifest URL (https://… or stremio://…, https preferred)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    tryAdd(ctx, text.toString(), status)
                    true
                } else false
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 4)
            addView(input)
            addView(MaterialButton(ctx).apply {
                text = "Add"
                setOnClickListener {
                    tryAdd(ctx, input.text.toString(), status)
                    if (status.text.startsWith("Added")) input.setText("")
                }
            })
        }
    }
    private fun tryAdd(ctx: Context, raw: String, status: TextView?) {
        val normalized = normalizeAddonUrl(raw)
        if (normalized == null) {
            status?.text = "That is not a manifest URL (need http(s):// or stremio://)."
            Toast.makeText(ctx, "That is not a manifest URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (!repository.addAddon(normalized)) {
            status?.text = "Addon already listed."
            Toast.makeText(ctx, "Addon already listed", Toast.LENGTH_SHORT).show()
            return
        }
        status?.text = "Added ✓ — fetching manifest name…"
        rebuildRows(ctx)
        uiScope.launch {
            val preview = withContext(Dispatchers.IO) { repository.previewAddon(normalized) }
            if (preview != null) {
                status?.text = "Added ${preview.name} (${preview.catalogCount} catalogs) ✓"
                rebuildRows(ctx)
            } else {
                status?.text = "Added, but manifest did not load — check the URL."
            }
        }
    }
    private fun generalRow(ctx: Context): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 4)
        }
        box.addView(switchPref(ctx, "Subtitles enabled", StremioConstants.KEY_SUBS_ENABLED, StremioConstants.DEFAULT_SUBS_ENABLED))
        box.addView(switchPref(ctx, "Search fallback (scan catalogs when native search is thin)", StremioConstants.KEY_SEARCH_FALLBACK, StremioConstants.DEFAULT_SEARCH_FALLBACK))
        box.addView(hint(ctx, "Timeout ${prefs.getInt(StremioConstants.KEY_TIMEOUT_S, StremioConstants.DEFAULT_TIMEOUT_S)}s • Cache ${prefs.getInt(StremioConstants.KEY_CACHE_TTL_H, StremioConstants.DEFAULT_CACHE_TTL_H)}h — tap to adjust."))
        box.addView(numberRow(ctx, "Timeout", StremioConstants.KEY_TIMEOUT_S, StremioConstants.DEFAULT_TIMEOUT_S, 5, 120, "s"))
        box.addView(numberRow(ctx, "Manifest cache", StremioConstants.KEY_CACHE_TTL_H, StremioConstants.DEFAULT_CACHE_TTL_H, 0, 168, "h"))
        return box
    }
    private fun switchPref(ctx: Context, label: String, key: String, default: Boolean): LinearLayout {
        val sw = SwitchMaterial(ctx).apply {
            text = label
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            addView(sw)
        }
    }
    private fun numberRow(ctx: Context, label: String, key: String, default: Int, min: Int, max: Int, suffix: String): LinearLayout {
        val value = TextView(ctx).apply {
            text = "$label: ${prefs.getInt(key, default)}$suffix"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun cycle() {
            val cur = prefs.getInt(key, default)
            val next = when {
                cur >= max -> min
                key == StremioConstants.KEY_TIMEOUT_S -> (cur + 5).coerceAtMost(max)
                else -> (cur + 6).coerceAtMost(max)
            }
            prefs.edit().putInt(key, next).apply()
            value.text = "$label: ${next}$suffix"
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            addView(value)
            addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Change"
                setOnClickListener { cycle() }
            })
        }
    }
    private fun dataRow(ctx: Context): LinearLayout {
        val export = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Export"
            isAllCaps = false
            setOnClickListener {
                val json = repository.exportJson()
                if (json == "[]") {
                    Toast.makeText(ctx, "Nothing to export", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AddonUrlOpener.copyToClipboard(ctx, "StremioCS addons", json)
                Toast.makeText(ctx, "Exported — note: URLs may contain private ?tokens", Toast.LENGTH_LONG).show()
            }
        }
        val import = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Import"
            isAllCaps = false
            setOnClickListener { showImportDialog(ctx) }
        }
        val clear = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clear all"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Remove all addons?")
                    .setMessage("This clears every addon. You can re-add via Browse Addons.")
                    .setPositiveButton("Clear") { _, _ ->
                        repository.clearAll()
                        rebuildRows(ctx)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
            addView(export.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(import.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(clear.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        }
    }
    private fun showImportDialog(ctx: Context) {
        val input = EditText(ctx).apply {
            hint = "Paste JSON ([{name,url,enabled}]) or a manifest URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        AlertDialog.Builder(ctx)
            .setTitle("Import addons")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                if (raw.length > 256 * 1024) {
                    Toast.makeText(ctx, "Import too large — nothing imported", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                    val added = repository.importJson(raw)
                    Toast.makeText(
                        ctx,
                        when {
                            added < 0 -> "Invalid JSON — nothing imported"
                            added == 0 -> "Nothing new — already listed"
                            else -> "Imported $added addon(s)"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    rebuildRows(ctx)
                } else {
                    tryAdd(ctx, raw, null)
                    rebuildRows(ctx)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun header(ctx: Context, text: String, size: Float): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setPadding(0, 16, 0, 4)
        }
    private fun hint(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setPadding(0, 2, 0, 6)
        }
    private fun divider(ctx: Context): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(0x1F000000)
        }
}
