package com.dramafren

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaFrenPlugin : Plugin() {
    private val provider = DramaFrenProvider()

    override fun load(context: Context) {
        DramaFrenStore.init(context)
        provider.mainUrl = DramaFrenStore.base()
        registerMainAPI(provider)

        openSettings = { ctx ->
            DramaFrenSettingsDialog.show(ctx, provider.mainUrl)
        }

        DramaFrenSettingsDialog.onDomainChanged = {
            provider.mainUrl = DramaFrenStore.base()
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
    }

    override fun beforeUnload() {}
}
