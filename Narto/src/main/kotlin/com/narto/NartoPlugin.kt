package com.narto

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NartoPlugin : Plugin() {
    private val provider = NartoProvider()

    override fun load(context: Context) {
        NartoStore.init(context)
        provider.mainUrl = normalizeNartoBase(NartoStore.loadBase()) ?: DEFAULT_BASE_URL
        registerMainAPI(provider)

        openSettings = { ctx ->
            NartoSettingsDialog.show(ctx, provider.mainUrl)
        }

        NartoSettingsDialog.onDomainChanged = {
            provider.mainUrl = normalizeNartoBase(NartoStore.loadBase()) ?: DEFAULT_BASE_URL
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
    }

    override fun beforeUnload() {}
}
