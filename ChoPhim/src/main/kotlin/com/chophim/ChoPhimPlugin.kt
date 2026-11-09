package com.chophim

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ChoPhimPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ChoPhimProvider())
    }
}
