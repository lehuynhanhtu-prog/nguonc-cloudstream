package com.lehuynhanhtu.cloudstreamcopier

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class CopierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
        }
    }
}
