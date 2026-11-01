package com.lehuynhanhtu.cloudstreamcopier

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

object NetworkScanner {
    fun phoneIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    suspend fun scanAdb5555(context: Context): List<String> = coroutineScope {
        val own = phoneIpv4(context) ?: error("Điện thoại chưa kết nối Wi-Fi")
        val prefix = own.substringBeforeLast('.', "")
        require(prefix.isNotBlank()) { "Không xác định được mạng Wi-Fi" }

        val sem = Semaphore(48)
        (1..254).map { i ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ip = prefix + "." + i
                    if (ip == own) return@withPermit null
                    val open = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(ip, 5555), 450)
                        }
                        true
                    }.getOrDefault(false)
                    ip.takeIf { open }
                }
            }
        }.awaitAll().filterNotNull().sorted()
    }
}
