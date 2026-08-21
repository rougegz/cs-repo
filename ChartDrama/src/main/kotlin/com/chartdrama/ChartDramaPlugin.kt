package com.chartdrama

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ChartDramaPlugin : Plugin() {
    private val provider = ChartDramaProvider()

    override fun load(context: Context) {
        ChartStore.init(context)
        provider.mainUrl = ChartStore.activeBase() ?: DEFAULT_BASE_URL
        registerMainAPI(provider)

        openSettings = { ctx ->
            ChartDramaSettingsDialog.show(ctx, provider.mainUrl)
        }

        ChartDramaSettingsDialog.onDomainChanged = {
            provider.mainUrl = ChartStore.activeBase() ?: DEFAULT_BASE_URL
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
    }

    override fun beforeUnload() {}
}
