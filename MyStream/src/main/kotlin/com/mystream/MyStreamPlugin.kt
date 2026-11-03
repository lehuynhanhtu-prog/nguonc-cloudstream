package com.mystream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MyStreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MyStreamProvider())
    }
}
