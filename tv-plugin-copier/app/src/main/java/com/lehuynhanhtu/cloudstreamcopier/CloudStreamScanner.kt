package com.lehuynhanhtu.cloudstreamcopier

object CloudStreamScanner {
    fun scan(adb: AdbClient): List<TvPlugin> {
        val found = mutableListOf<TvPlugin>()

        val localOut = adb.shell(
            "find /sdcard/Cloudstream3/plugins /storage/emulated/0/Cloudstream3/plugins " +
                "-maxdepth 1 -type f -name '*.cs3' -print 2>/dev/null | sort -u",
            45,
        )
        localOut.lineSequence()
            .map(String::trim)
            .filter { it.endsWith(".cs3", true) }
            .distinct()
            .forEach { path ->
                val size = adb.shell("wc -c < " + AdbClient.quote(path))
                    .trim()
                    .toLongOrNull() ?: 0L
                found += TvPlugin(
                    name = path.substringAfterLast('/'),
                    remotePath = path,
                    size = size,
                    online = false,
                )
            }

        adb.shell("logcat -c", 20)
        adb.shell(
            "am force-stop com.lagradost.cloudstream3; " +
                "monkey -p com.lagradost.cloudstream3 1 >/dev/null 2>&1; sleep 8",
            20,
        )
        val log = adb.shell("logcat -d -v raw | tail -n 8000", 60)

        val pluginData = Regex(
            """Loading plugin:\s*PluginData\(internalName=([^,]+),\s*url=([^,]*),\s*isOnline=(true|false),\s*filePath=([^,]*),\s*version=([^)]+)\)"""
        )
        pluginData.findAll(log).forEach { m ->
            val name = m.groupValues[1].trim()
            val rawUrl = m.groupValues[2].trim()
            val online = m.groupValues[3].toBooleanStrictOrNull() ?: false
            val path = m.groupValues[4].trim()
            val version = m.groupValues[5].trim().toIntOrNull()
            val url = rawUrl.takeUnless { it.isBlank() || it == "null" }

            if (online && url != null) {
                found += TvPlugin(
                    name = if (name.endsWith(".cs3", true)) name else name + ".cs3",
                    url = url,
                    version = version,
                    online = true,
                )
            } else if (!online && path.endsWith(".cs3", true)) {
                if (found.none { it.remotePath == path }) {
                    val size = adb.shell(
                        "wc -c < " + AdbClient.quote(path) + " 2>/dev/null"
                    ).trim().toLongOrNull() ?: 0L
                    found += TvPlugin(
                        name = path.substringAfterLast('/'),
                        remotePath = path,
                        size = size,
                        version = version,
                        online = false,
                    )
                }
            }
        }

        Regex("""Downloading plugin:\s+(https?://\S+)""")
            .findAll(log)
            .map { it.groupValues[1].trim() }
            .distinct()
            .forEach { url ->
                if (found.none { it.url == url }) {
                    val name = url.substringBefore('?').substringAfterLast('/')
                        .ifBlank { "plugin.cs3" }
                    found += TvPlugin(
                        name = name,
                        url = url,
                        online = true,
                    )
                }
            }

        return found
            .distinctBy { it.url ?: it.remotePath ?: it.name }
            .sortedWith(
                compareBy<TvPlugin> { !it.online }
                    .thenBy { it.name.lowercase() }
            )
    }

    fun repositoryUrls(log: String): List<String> {
        val fromLoading = Regex(
            """Loading plugin:\s*PluginData\([^)]*url=(https?://[^,]+),"""
        ).findAll(log).map { it.groupValues[1].trim() }

        val fromDownload = Regex(
            """Downloading plugin:\s+(https?://\S+)"""
        ).findAll(log).map { it.groupValues[1].trim() }

        return (fromLoading + fromDownload).distinct().toList()
    }
}
