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

        // Active domain (of 3) wins on startup; default otherwise.
        provider.mainUrl = VdramaStore.base()

        registerMainAPI(provider)

        openSettings = { ctx ->
            VdramaSettingsDialog.show(ctx, provider.mainUrl)
        }

        VdramaSettingsDialog.onDomainChanged = {
            provider.mainUrl = VdramaStore.base()
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
    }

    override fun beforeUnload() {}
}
