package com.stremiouniversal

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StremioPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("StremioUniversal", Context.MODE_PRIVATE)
        registerMainAPI(StremioProvider(StremioRepository(prefs)))
        openSettings = {
            val activity = context as? AppCompatActivity
            if (activity != null) {
                AddonSettingsFragment().show(activity.supportFragmentManager, "StremioAddons")
            } else {
                Toast.makeText(context, "Open settings from an activity to edit Stremio addons", Toast.LENGTH_LONG).show()
            }
        }
    }
}
