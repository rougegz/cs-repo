package com.stremiouniversal.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.stremiouniversal.AddonDirectory
import com.stremiouniversal.DirectoryAddon
import com.stremiouniversal.StremioConstants
import com.stremiouniversal.StremioRepository
import com.stremiouniversal.addonBaseKey
import com.stremiouniversal.addonDisplayHost
import com.stremiouniversal.normalizeAddonUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddonBrowserFragment : BottomSheetDialogFragment() {
    var onChanged: (() -> Unit)? = null

    private lateinit var repository: StremioRepository
    private lateinit var rowsBox: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var searchInput: EditText
    private var all: List<DirectoryAddon> = emptyList()
    private var installedBases: Set<String> = emptySet()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = StremioRepository(
            requireContext().getSharedPreferences(StremioConstants.PREFS_NAME, Context.MODE_PRIVATE)
        )
        installedBases = repository.loadConfiguredAddons().map { addonBaseKey(it.manifestUrl) }.toSet()
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
        root.addView(TextView(ctx).apply {
            text = "Browse Addons"
            textSize = 18f
        })
        root.addView(TextView(ctx).apply {
            text = "Lightweight list — fits phones & TVs. Tap Install, nothing else to do."
            textSize = 12f
            setPadding(0, 2, 0, 6)
        })
        searchInput = EditText(ctx).apply {
            hint = "Search addons"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderRows(ctx, s?.toString().orEmpty())
                }
            })
        }
        root.addView(searchInput)
        statusView = TextView(ctx).apply {
            textSize = 12f
            setPadding(0, 8, 0, 4)
        }
        root.addView(statusView)
        rowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowsBox)
        root.addView(footerRow(ctx))
        load(ctx, force = false)
        return ScrollView(ctx).apply { addView(root) }
    }

    private fun footerRow(ctx: Context): LinearLayout {
        val fullSite = MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Full site"
            isAllCaps = false
            isFocusable = true
            setOnClickListener { AddonBrowserDialog.show(ctx) }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
            addView(fullSite.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
    }

    private fun load(ctx: Context, force: Boolean) {
        statusView.text = "Loading…"
        rowsBox.removeAllViews()
        uiScope.launch {
            val list = withContext(Dispatchers.IO) { AddonDirectory.load(force) }
            if (list == null) {
                statusView.text = "Couldn't load the list — check connection."
                rowsBox.addView(MaterialButton(ctx).apply {
                    text = "Retry"
                    isAllCaps = false
                    isFocusable = true
                    setOnClickListener { load(ctx, force = true) }
                })
                return@launch
            }
            all = list
            renderRows(ctx, searchInput.text?.toString().orEmpty())
        }
    }

    private fun refreshInstalled(ctx: Context) {
        installedBases = repository.loadConfiguredAddons().map { addonBaseKey(it.manifestUrl) }.toSet()
        renderRows(ctx, searchInput.text?.toString().orEmpty())
        onChanged?.invoke()
    }

    private fun renderRows(ctx: Context, query: String) {
        rowsBox.removeAllViews()
        val visible = AddonDirectory.filter(all, query).take(200)
        statusView.text = if (query.isBlank()) "${all.size} addons"
        else "${visible.size} of ${all.size} addons"
        if (visible.isEmpty() && all.isNotEmpty()) {
            rowsBox.addView(TextView(ctx).apply {
                text = "No matches for \"$query\"."
                textSize = 13f
            })
            return
        }
        visible.forEach { addon ->
            rowsBox.addView(directoryRow(ctx, addon))
            rowsBox.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (ctx.resources.displayMetrics.density).toInt()
                )
                setBackgroundColor(0x1F000000)
            })
        }
    }

    private fun directoryRow(ctx: Context, addon: DirectoryAddon): LinearLayout {
        val alreadyIn = installedBases.any { base ->
            base == addonBaseKey(addon.transportUrl)
        }
        val title = TextView(ctx).apply {
            text = addon.name
            textSize = 15f
        }
        val desc = TextView(ctx).apply {
            text = addon.description.ifEmpty { addonDisplayHost(addon.transportUrl) }
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val meta = TextView(ctx).apply {
            text = addonDisplayHost(addon.transportUrl)
            textSize = 11f
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(title)
            addView(desc)
            addView(meta)
        }
        val install = MaterialButton(ctx).apply {
            text = if (alreadyIn) "Added ✓" else "Install"
            isAllCaps = false
            isEnabled = !alreadyIn
            isFocusable = true
            setOnClickListener {
                val normalized = normalizeAddonUrl(addon.transportUrl)
                if (normalized == null) {
                    Toast.makeText(ctx, "Bad URL for ${addon.name}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!repository.addAddon(normalized)) {
                    Toast.makeText(ctx, "Already listed", Toast.LENGTH_SHORT).show()
                    isEnabled = false
                    text = "Added ✓"
                    return@setOnClickListener
                }
                installedBases = installedBases + addonBaseKey(normalized)
                Toast.makeText(ctx, "Added ${addon.name} ✓", Toast.LENGTH_SHORT).show()
                isEnabled = false
                text = "Added ✓"
                onChanged?.invoke()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
            addView(texts)
            addView(install)
        }
    }
}
