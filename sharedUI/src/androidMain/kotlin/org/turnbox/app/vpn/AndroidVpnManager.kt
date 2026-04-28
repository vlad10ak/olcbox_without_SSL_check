package org.turnbox.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mobile.Mobile
import org.turnbox.app.data.model.HysteriaConfig
import us.leaf3stones.hy2droid.proxy.Hysteria2VpnService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class AndroidVpnManager(private val context: Context) : VpnManager {
    override val logs: StateFlow<List<String>> = Hysteria2VpnService.logs
    override val isConnected: StateFlow<Boolean> = Hysteria2VpnService.isConnected

    override fun needsPermission(): Boolean = VpnService.prepare(context) != null

    override fun startVpn() {
        val intent = Intent(context, Hysteria2VpnService::class.java).apply {
            action = Hysteria2VpnService.ACTION_START_VPN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stopVpn() {
        val intent = Intent(context, Hysteria2VpnService::class.java).apply {
            action = Hysteria2VpnService.ACTION_STOP_VPN
        }
        context.startService(intent)
    }

    override suspend fun ping(hysteriaConfig: HysteriaConfig): Long? {
        return checkConnection(hysteriaConfig)
    }

    override suspend fun checkConnection(hysteriaConfig: HysteriaConfig): Long? =
        withContext(Dispatchers.IO) {
            connectionCheckMutex.withLock {
                val config = hysteriaConfig.normalized()
                if (!config.isComplete()) return@withLock null

                if (Hysteria2VpnService.isConnected.value || Mobile.isRunning()) {
                    return@withLock 1L
                }

                val socksPort = (20001..30000).random()
                val startedAt = System.currentTimeMillis()

                try {
                    Mobile.setProviders()
                    Mobile.start(
                        config.bypassProvider,
                        config.id,
                        config.key,
                        socksPort.toLong(),
                        "",
                        ""
                    )
                    Mobile.waitReady(CONNECTION_CHECK_TIMEOUT_MS)
                    if (probeSocksConnect(socksPort)) {
                        System.currentTimeMillis() - startedAt
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { Mobile.stop() }
                }
            }
        }

    private fun probeSocksConnect(socksPort: Int): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), SOCKS_PROBE_TIMEOUT_MS)
            socket.soTimeout = SOCKS_PROBE_TIMEOUT_MS

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()

            if (input.readUnsignedByte() != 0x05) return false
            if (input.readUnsignedByte() == 0xFF) return false

            val host = SOCKS_PROBE_HOST.encodeToByteArray()
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()))
            output.write(host)
            output.writeShort(SOCKS_PROBE_PORT)
            output.flush()

            if (input.readUnsignedByte() != 0x05) return false
            if (input.readUnsignedByte() != 0x00) return false
            input.readUnsignedByte()

            when (input.readUnsignedByte()) {
                0x01 -> input.skipFully(4)
                0x03 -> input.skipFully(input.readUnsignedByte())
                0x04 -> input.skipFully(16)
                else -> return false
            }
            input.skipFully(2)
            return true
        }
    }

    private fun DataInputStream.skipFully(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) throw java.io.EOFException()
            remaining -= skipped
        }
    }

    private companion object {
        const val CONNECTION_CHECK_TIMEOUT_MS = 8_000L
        const val SOCKS_PROBE_TIMEOUT_MS = 5_000
        const val SOCKS_PROBE_HOST = "example.com"
        const val SOCKS_PROBE_PORT = 443
        val connectionCheckMutex = Mutex()
    }
}
