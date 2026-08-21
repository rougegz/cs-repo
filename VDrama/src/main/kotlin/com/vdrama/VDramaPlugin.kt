package com.vdrama

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VDramaPlugin : Plugin() {

    private val provider = VDramaProvider()

    override fun load(context: Context) {
        VdramaStore.init(context)

        // Persisted domain override wins on startup; default otherwise.
        provider.mainUrl = normalizeBaseUrl(VdramaStore.loadOverride()) ?: DEFAULT_BASE_URL

        registerMainAPI(provider)

        // Gear icon for this plugin inside CloudStream's plugin settings screen.
        openSettings = { ctx ->
            VdramaSettingsDialog.show(ctx, provider.mainUrl)
        }

        VdramaSettingsDialog.onDomainChanged = {
            provider.mainUrl = normalizeBaseUrl(VdramaStore.loadOverride()) ?: DEFAULT_BASE_URL
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
    }

    override fun beforeUnload() {}
}
