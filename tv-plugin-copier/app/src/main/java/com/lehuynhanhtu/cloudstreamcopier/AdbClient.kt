package com.lehuynhanhtu.cloudstreamcopier

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

class AdbClient private constructor(
    private val privateKey: PrivateKey,
    private val certificate: Certificate,
) : AbsAdbConnectionManager() {

    @Volatile var endpoint: Pair<String, Int>? = null
        private set

    init { setTimeout(25, TimeUnit.SECONDS) }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "CloudStreamPluginCopier"

    fun connectTo(host: String, port: Int): Boolean {
        runCatching { disconnect() }
        val ok = connect(host, port)
        if (ok) endpoint = host to port
        return ok
    }

    fun pairAndConnect(context: Context, host: String, pairingPort: Int, code: String): Boolean {
        if (!pair(host, pairingPort, code)) return false
        runCatching { disconnect() }
        val tls = runCatching { connectTls(context, 20_000) }.getOrDefault(false)
        if (tls) endpoint = host to (endpoint?.second ?: pairingPort)
        return tls
    }

    fun shell(command: String, timeoutSeconds: Int = 45): String {
        val marker = "__CSPC_${System.nanoTime().toString(16)}__"
        val stream = openStream("shell:$command; echo $marker")
        val text = readTextUntil(stream, timeoutSeconds) { it.contains(marker) }
        return text.substringBefore(marker).replace("\r", "")
    }

    fun readRemoteFile(path: String): ByteArray {
        val q = quote(path)
        val size = shell("wc -c < $q").trim().toLongOrNull()
            ?: error("Không đọc được kích thước file: $path")
        require(size in 0..100_000_000) { "File quá lớn hoặc kích thước không hợp lệ" }
        val stream = openStream("exec:dd if=$q bs=65536 2>/dev/null")
        val input = stream.openInputStream()
        val out = ByteArrayOutputStream(size.toInt().coerceAtLeast(1024))
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        runCatching { stream.close() }
        if (out.size().toLong() != size) error("Tivi trả thiếu dữ liệu (${out.size()}/$size byte)")
        return out.toByteArray()
    }

    fun writeRemoteFile(path: String, bytes: ByteArray) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotBlank()) shell("mkdir -p ${quote(parent)}")
        val q = quote(path)
        val marker = "__WRITE_${System.nanoTime().toString(16)}__"
        val stream = openStream("exec:sh -c 'dd of=${shellSingle(path)} bs=1 count=${bytes.size} 2>/dev/null; echo $marker'")
        val output = stream.openOutputStream()
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(32 * 1024, bytes.size - offset)
            output.write(bytes, offset, n)
            offset += n
        }
        output.flush()
        val result = readTextUntil(stream, 120) { it.contains(marker) }
        if (!result.contains(marker)) error("Tivi không xác nhận ghi file")
        val remoteSize = shell("wc -c < $q").trim().toIntOrNull()
        if (remoteSize != bytes.size) error("Sai kích thước sau khi ghi: $remoteSize/${bytes.size}")
    }

    private fun readTextUntil(stream: AdbStream, seconds: Int, done: (String) -> Boolean): String {
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + seconds * 1000L
        val worker = Thread {
            runCatching {
                val input = stream.openInputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(out) { out.write(buffer, 0, n) }
                    if (done(synchronized(out) { out.toString(Charsets.UTF_8.name()) })) break
                }
            }
        }.apply { isDaemon = true; start() }
        while (worker.isAlive && System.currentTimeMillis() < deadline) worker.join(150)
        runCatching { stream.close() }
        if (worker.isAlive) {
            worker.interrupt()
            error("Tivi không trả lời sau $seconds giây")
        }
        return synchronized(out) { out.toString(Charsets.UTF_8.name()) }
    }

    companion object {
        @Volatile private var instance: AdbClient? = null

        fun get(context: Context): AdbClient = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): AdbClient {
            val dir = File(context.filesDir, "adb-key").apply { mkdirs() }
            val keyFile = File(dir, "private.der")
            val certFile = File(dir, "cert.der")
            if (keyFile.isFile && certFile.isFile) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(certFile.inputStream())
                    return AdbClient(key, cert)
                }
            }

            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val pair = generator.generateKeyPair()
            val now = Date()
            val until = Date(now.time + 10L * 365 * 24 * 3600 * 1000)
            val subject = X500Name("CN=CloudStream TV Plugin Copier")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now.time),
                now,
                until,
                subject,
                pair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(cert.encoded)
            return AdbClient(pair.private, cert)
        }

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        private fun shellSingle(value: String): String = value.replace("'", "'\\''")
    }
}
