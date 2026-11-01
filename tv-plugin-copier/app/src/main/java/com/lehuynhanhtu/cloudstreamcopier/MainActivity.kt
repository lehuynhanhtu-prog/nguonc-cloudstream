package com.lehuynhanhtu.cloudstreamcopier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppUi() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppUi() {
        val adb = remember { AdbClient.get(this) }
        val store = remember { PluginStore(this) }
        val scope = rememberCoroutineScope()
        var addr by remember { mutableStateOf("192.168.1.20:5555") }
        var pairAddr by remember { mutableStateOf("192.168.1.20:37123") }
        var pairCode by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Chưa kết nối") }
        var busy by remember { mutableStateOf(false) }
        var scanResults by remember { mutableStateOf<List<String>>(emptyList()) }
        var tvPlugins by remember { mutableStateOf<List<TvPlugin>>(emptyList()) }
        var repoUrls by remember { mutableStateOf<List<String>>(emptyList()) }
        var backups by remember { mutableStateOf(store.listBackups()) }

        fun parse(s: String): Pair<String, Int> {
            val i = s.trim().lastIndexOf(':'); require(i > 0) { "Nhập IP:cổng" }
            return s.trim().substring(0, i) to (s.trim().substring(i + 1).toIntOrNull() ?: error("Cổng sai"))
        }
        fun job(label: String, block: suspend () -> Unit) {
            if (busy) return
            busy = true; status = label
            scope.launch { try { block() } catch (e: Throwable) { status = "Lỗi: ${e.message}" } finally { busy = false } }
        }
        fun refresh() { backups = store.listBackups() }

        Scaffold(topBar = { TopAppBar(title = { Text("CloudStream TV Plugin Copier") }) }) { pad ->
            Column(
                Modifier.padding(pad).padding(14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Sao lưu plugin từ TV nguồn → điện thoại → TV đích qua Wi‑Fi ADB.")
                AssistChip(onClick = {}, label = { Text(if (busy) "Đang xử lý…" else status) })

                BoxSection("1. Kết nối TV") {
                    Button(enabled = !busy, onClick = { job("Đang quét TV cùng Wi-Fi…") {
                        scanResults = NetworkScanner.scanAdb5555(this@MainActivity)
                        status = if (scanResults.isEmpty()) {
                            "Không thấy thiết bị mở ADB 5555. TV Android 11+ hãy dùng Ghép đôi Wireless debugging."
                        } else {
                            "Tìm thấy " + scanResults.size + " thiết bị ADB"
                        }
                    } }) { Text("Quét TV cùng mạng") }
                    scanResults.forEach { ip ->
                        ActionRow(ip, "ADB 5555", "Chọn") {
                            addr = ip + ":5555"
                            status = "Đã chọn " + addr
                        }
                    }
                    OutlinedTextField(addr, { addr = it }, label = { Text("IP:cổng ADB") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { job("Đang kết nối…") {
                            val (h,p)=parse(addr); check(withContext(Dispatchers.IO){adb.connectTo(h,p)}) { "Không kết nối được" }
                            val cs=withContext(Dispatchers.IO){adb.shell("pm path com.lagradost.cloudstream3").contains("package:")}
                            status="Đã kết nối $h:$p"+(if(cs) " • Có CloudStream" else " • Không thấy CloudStream")
                        } }) { Text("Kết nối") }
                        OutlinedButton(onClick = { runCatching { adb.disconnect() }; status="Đã ngắt" }) { Text("Ngắt") }
                    }
                    HorizontalDivider()
                    OutlinedTextField(pairAddr, { pairAddr=it }, label={Text("IP:cổng ghép đôi")}, modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(pairCode, { pairCode=it.filter(Char::isDigit).take(6) }, label={Text("Mã 6 số")}, modifier=Modifier.fillMaxWidth())
                    Button(enabled=!busy && pairCode.length==6, onClick={ job("Đang ghép đôi…") {
                        val (h,p)=parse(pairAddr); check(withContext(Dispatchers.IO){adb.pairAndConnect(this@MainActivity,h,p,pairCode)}) { "Ghép đôi thất bại; hãy dùng IP:cổng kết nối và nút Kết nối" }
                        status="Ghép đôi/kết nối thành công"
                    }}) { Text("Ghép đôi Wireless debugging") }
                }

                BoxSection("2. Quét plugin CloudStream") {
                    Text("Quét cả plugin local và plugin cài từ repository. App sẽ tự mở lại CloudStream để đọc tên, URL và version plugin từ log.")
                    Button(enabled=!busy,onClick={ job("Đang quét tất cả plugin…") {
                        tvPlugins = withContext(Dispatchers.IO) { CloudStreamScanner.scan(adb) }
                        val online = tvPlugins.count { it.online }
                        val local = tvPlugins.size - online
                        status = "Tìm thấy " + tvPlugins.size + " plugin • repository " + online + " • local " + local
                    }}) { Text("Quét tất cả plugin") }
                    if (tvPlugins.isEmpty()) {
                        Text("Nếu vẫn 0 plugin: hãy chắc chắn CloudStream đã cài extension, rồi bấm quét lại.", style=MaterialTheme.typography.bodySmall)
                    }
                    tvPlugins.forEach { p ->
                        val type = if (p.online) "Repository" else "Local"
                        val detail = buildString {
                            append(type)
                            p.version?.let { append(" • v" + it) }
                            if (p.size > 0) append(" • " + p.size + " byte")
                            p.url?.let { append("\n" + it.take(90)) }
                        }
                        ActionRow(p.name, detail, "Sao lưu") { job("Sao lưu " + p.name + "…") {
                            if (p.online && !p.url.isNullOrBlank()) {
                                withContext(Dispatchers.IO) { store.download(p.url!!) }
                            } else {
                                val path = p.remotePath ?: error("Không có đường dẫn plugin")
                                val data = withContext(Dispatchers.IO) { adb.readRemoteFile(path) }
                                store.save(p.name, data, "TV")
                            }
                            refresh()
                            status = "Đã sao lưu " + p.name
                        } }
                    }
                }
                BoxSection("3. Plugin repository") {
                    Text("Plugin repository nằm trong vùng private của CloudStream. App lấy lại URL .cs3 từ log khi CloudStream cập nhật/cài lại extension.")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=!busy,onClick={ job("Mở CloudStream…") {
                            withContext(Dispatchers.IO){adb.shell("logcat -c"); adb.shell("monkey -p com.lagradost.cloudstream3 1 >/dev/null 2>&1")}
                            status="Trong CloudStream hãy cập nhật/cài lại plugin, rồi quay lại bấm Đọc log"
                        }}) { Text("Bắt đầu") }
                        OutlinedButton(enabled=!busy,onClick={ job("Đang đọc log…") {
                            val log=withContext(Dispatchers.IO){adb.shell("logcat -d -v raw | tail -n 4000",60)}
                            repoUrls=CloudStreamScanner.repositoryUrls(log)
                            status="Tìm thấy ${repoUrls.size} URL"
                        }}) { Text("Đọc log") }
                    }
                    repoUrls.forEach { u -> ActionRow(u.substringAfterLast('/').take(40),u.take(70),"Tải") { job("Đang tải…") {
                        withContext(Dispatchers.IO){store.download(u)}; refresh(); status="Đã tải plugin"
                    } } }
                    OutlinedTextField(url,{url=it},label={Text("URL .cs3 thủ công")},modifier=Modifier.fillMaxWidth())
                    Button(enabled=!busy && url.startsWith("http"),onClick={job("Đang tải URL…"){
                        withContext(Dispatchers.IO){store.download(url.trim())};refresh();status="Đã lưu plugin"
                    }}) { Text("Tải URL") }
                }

                BoxSection("4. Bản sao trên điện thoại") {
                    if(backups.isEmpty()) Text("Chưa có bản sao.")
                    backups.forEach { b -> ActionRow(b.name,"${b.size} byte • ${b.sha256.take(12)}…","Cài lên TV") { job("Cài ${b.name}…") {
                        val bytes=withContext(Dispatchers.IO){b.file.readBytes()}
                        withContext(Dispatchers.IO){adb.writeRemoteFile("/sdcard/Cloudstream3/plugins/${b.name}",bytes);adb.shell("am force-stop com.lagradost.cloudstream3; monkey -p com.lagradost.cloudstream3 1 >/dev/null 2>&1")}
                        status="Đã cài ${b.name} và mở lại CloudStream"
                    } } }
                    if(backups.isNotEmpty()) OutlinedButton(enabled=!busy,onClick={job("Cài tất cả…"){
                        withContext(Dispatchers.IO){backups.forEach{adb.writeRemoteFile("/sdcard/Cloudstream3/plugins/${it.name}",it.file.readBytes())};adb.shell("am force-stop com.lagradost.cloudstream3; monkey -p com.lagradost.cloudstream3 1 >/dev/null 2>&1")}
                        status="Đã cài ${backups.size} plugin"
                    }}) { Text("Cài tất cả lên TV đang kết nối") }
                }

                Text("Cách dùng: Quét TV cùng mạng hoặc nhập IP → Kết nối → Quét tất cả plugin → Sao lưu → ngắt TV nguồn → kết nối TV đích → cài bản sao. TV Android 11+ dùng cổng ADB ngẫu nhiên nên nếu quét 5555 không thấy, dùng Ghép đôi Wireless debugging.", style=MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable private fun BoxSection(title:String, content:@Composable ColumnScope.()->Unit){
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Text(title,fontWeight=FontWeight.Bold);content()}}
    }
    @Composable private fun ActionRow(title:String,sub:String,button:String,action:()->Unit){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text(sub,style=MaterialTheme.typography.bodySmall)};Button(onClick=action){Text(button)}}
    }
}
