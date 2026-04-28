package us.leaf3stones.hy2droid.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import org.turnbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.turnbox.app.data.repository.HysteriaConfigRepository
import org.turnbox.app.vpn.data.KEY_IS_VPN_CONFIG_READY
import org.turnbox.app.vpn.data.KEY_VPN_CONFIG_PATH
import org.turnbox.app.vpn.data.vpnPrefDataStore
import java.io.File
import java.net.InetAddress
import kotlin.coroutines.coroutineContext
import kotlin.concurrent.thread

class Hysteria2VpnService : VpnService() {

    // --- JNI ФУНКЦИИ ДЛЯ TUN2SOCKS ---
    private external fun startTun2socks(configPath: String, fd: Int)
    private external fun stopTun2socks()
    private external fun getTun2socksStats(): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val startMutex = Mutex()

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null
    private var lastConfigPath: String? = null
    private var lastMigrationTime: Long = 0L
    private var isRunning = false
    @Volatile
    private var isStarting = false

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var isCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("❌ Network LOST detected")
            val active = connectivityManager.activeNetwork
            if (active != null && active != network) {
                handleNetworkChange(active, "Fallback after LOSS")
            } else {
                addLog("⚠️ No active network after loss — waiting...")
                scope.launch {
                    delay(1500)
                    val a = connectivityManager.activeNetwork
                    if (a != null) handleNetworkChange(a, "Delayed fallback")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (currentNetwork == network) handleNetworkChange(network, "Capabilities changed")
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return

            val isNewNetwork = currentNetwork != network
            if (!isNewNetwork) return

            val netName = getNetName(caps)
            addLog("🔄 $reason: $netName")
            updateUnderlyingNetwork(network)

            if (!isRunning) return

            val now = System.currentTimeMillis()
            if (now - lastMigrationTime < MIGRATION_DEBOUNCE_MS) return
            lastMigrationTime = now

            lastConfigPath?.let { startVpnChecked(true, it, isMigration = true) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Turnbox::VpnWakeLock")

        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                return this@Hysteria2VpnService.protect(fd.toInt())
            }
        })
        Mobile.setProviders()
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                addLog("rtc: ${msg.trimEnd()}")
                Log.v("olcrtc", msg.trimEnd())
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VPN) {
            addLog("🛑 Stop VPN requested from notification")
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        val isStart = intent?.action == ACTION_START_VPN
        if (!isStart) {
            cleanup(); stopSelf(); return START_NOT_STICKY
        }

        if (isRunning || isStarting) return START_STICKY

        startForeground()
        wakeLock?.acquire(24 * 60 * 60 * 1000L)

        scope.launch {
            val pref = vpnPrefDataStore.data.first()
            val ready = pref[KEY_IS_VPN_CONFIG_READY] ?: false
            val path = pref[KEY_VPN_CONFIG_PATH] ?: ""

            updateUnderlyingNetwork(findActiveUpstreamNetwork())
            registerNetworkMonitor()
            startVpnChecked(ready, path, isMigration = false)
        }
        return START_STICKY
    }

    private fun registerNetworkMonitor() {
        if (isCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isCallbackRegistered = true
            addLog("📡 Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Monitor error", e)
        }
    }

    private fun getAppPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel("CHANNEL_ID", "Turnbox VPN", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                channel
            )
        }
        val stopIntent =
            Intent(this, Hysteria2VpnService::class.java).apply { action = ACTION_STOP_VPN }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, "CHANNEL_ID")
            .setContentTitle("Turnbox VPN")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this, 100, notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun updateNotification(status: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val stopIntent =
            Intent(this, Hysteria2VpnService::class.java).apply { action = ACTION_STOP_VPN }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, "CHANNEL_ID")
            .setContentTitle("Turnbox VPN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(100, notif)
    }

    private fun startVpnChecked(
        isConfigReady: Boolean,
        configPath: String,
        isMigration: Boolean = false
    ) {
        if (!isConfigReady) return
        lastConfigPath = configPath

        startupJob?.cancel()
        watchdogJob?.cancel()
        retryJob?.cancel()
        isStarting = true

        startupJob = scope.launch {
            try {
                startMutex.withLock {
                    addLog(if (isMigration) "🔄 Reconnecting tunnel..." else "🚀 Starting VPN...")
                    updateNotification("Connecting...")
                    isRunning = false
                    _isConnected.value = false

                    stopTransportProcesses()
                    delay(START_RETRY_GRACE_MS)
                    coroutineContext.ensureActive()

                    val repo = configRepository
                    if (repo == null) {
                        addLog("❌ VPN config repository is not initialized")
                        updateNotification("Configuration error")
                        return@withLock
                    }

                    val selectedHysteriaId = repo.getSelectedHysteriaId()
                    val hysteriaConfig = repo.loadHysteriaConfig(selectedHysteriaId)

                    val provider = hysteriaConfig.bypassProvider
                    val roomId = hysteriaConfig.id
                    val keyHex = hysteriaConfig.key
                    val socksPort = LOCAL_SOCKS_PORT

                    if (provider.isBlank() || roomId.isBlank() || keyHex.isBlank()) {
                        addLog("❌ Provider, room ID or key is missing in location config")
                        updateNotification("Configuration incomplete")
                        return@withLock
                    }

                    val upstreamNetwork = findActiveUpstreamNetwork()
                    if (upstreamNetwork != null) {
                        updateUnderlyingNetwork(upstreamNetwork)
                        bindProcessToNetwork(upstreamNetwork, "✅ Bound to ${getNetName(upstreamNetwork)}")
                    } else {
                        addLog("⚠️ No active upstream network, trying with current process binding")
                    }

                    addLog("🚀 Starting olcRTC for provider: $provider, Room: $roomId")

                    try {
                        Mobile.setProtector(object : SocketProtector {
                            override fun protect(fd: Long): Boolean =
                                this@Hysteria2VpnService.protect(fd.toInt())
                        })
                        Mobile.start(provider, roomId, keyHex, socksPort.toLong(), "", "")
                        addLog("⏳ Waiting for WebRTC connection...")
                        Mobile.waitReady(MOBILE_READY_TIMEOUT_MS)
                        addLog("✅ WebRTC Ready & SOCKS5 Listening at $socksPort")
                    } catch (e: Exception) {
                        addLog("❌ Connection Error: ${e.message}")
                        updateNotification("Connection failed")
                        Mobile.stop()
                        unbindProcessFromNetwork()
                        scheduleRetry(configPath)
                        return@withLock
                    }

                    coroutineContext.ensureActive()
                    delay(TUNNEL_HANDOFF_DELAY_MS)
                    unbindProcessFromNetwork()

                    val pfd = establishSystemVpnTunnel()
                    if (pfd == null) {
                        addLog("❌ Failed to establish Android VPN tunnel")
                        updateNotification("VPN tunnel error")
                        unbindProcessFromNetwork()
                        stopTransportProcesses()
                        scheduleRetry(configPath)
                        return@withLock
                    }

                    vpnInterface = pfd
                    val fd = pfd.fd

                    try {
                        val tun2socksConf = writeTun2socksConfig(socksPort)
                        addLog(
                            "tun2socks config: ipv4-only, socks=127.0.0.1:$socksPort, " +
                                "udp=tcp, mapdns=$MAPDNS_ADDRESS"
                        )
                        tun2socksThread = thread(name = "Tun2SocksJNI", isDaemon = true) {
                            try {
                                startTun2socks(tun2socksConf.absolutePath, fd)
                                addLog("ℹ️ tun2socks JNI entry returned")
                            } catch (t: Throwable) {
                                addLog("❌ tun2socks crashed: ${t.message ?: t::class.java.simpleName}")
                                Log.e(TAG, "tun2socks crashed", t)
                            }
                        }

                        isRunning = true
                        _isConnected.value = true
                        addLog("✅ VPN Tunnel established (JNI tun2socks running)")
                        updateNotification("VPN Connected")
                        unbindProcessFromNetwork()
                        startWatchdog()
                    } catch (e: Exception) {
                        addLog("❌ Tun2Socks JNI start error: ${e.message}")
                        updateNotification("Connection Error")
                        unbindProcessFromNetwork()
                        stopTransportProcesses()
                        scheduleRetry(configPath)
                    }
                }
            } finally {
                if (startupJob === coroutineContext[Job]) {
                    isStarting = false
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && isRunning) {
                delay(5000)
                if (!Mobile.isRunning()) {
                    addLog("⚠️ Watchdog: olcRTC background process died!")
                    addLog("🔄 Watchdog triggers VPN restart...")
                    lastConfigPath?.let { startVpnChecked(true, it, isMigration = true) }
                    break
                }
            }
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Turnbox VPN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPDNS_ADDRESS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.excludeRoute(
                    IpPrefix(InetAddress.getByName("194.1.214.97"), 32)
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            }

            builder.establish()
        } catch (e: Exception) {
            addLog("❌ VPN Address configuration rejected: ${e.message}")
            null
        }
    }


    private fun stopTransportProcesses() {
        addLog("🛑 Stopping olcRTC and tun2socks...")
        try {
            Mobile.stop()
        } catch (_: Exception) {
        }
        try {
            stopTun2socks()
        } catch (_: Exception) {
        }

        tun2socksThread?.interrupt()
        tun2socksThread = null
        cleanupVpnInterface()
    }

    private fun cleanupVpnInterface() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    private fun cleanup() {
        isRunning = false
        isStarting = false
        _isConnected.value = false
        startupJob?.cancel()
        watchdogJob?.cancel()
        retryJob?.cancel()

        wakeLock?.let { if (it.isHeld) it.release() }

        scope.launch {
            startMutex.withLock { stopTransportProcesses() }
        }
        if (isCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
            isCallbackRegistered = false
        }
        updateUnderlyingNetwork(null)
        unbindProcessFromNetwork()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onRevoke() {
        addLog("🛑 VPN permission revoked by system")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun writeTun2socksConfig(socksPort: Int): File {
        val tun2socksConf = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)
        tun2socksConf.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              multi-queue: false
              ipv4: $TUN_IPV4_ADDRESS

            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'tcp'
              pipeline: false

            mapdns:
              address: $MAPDNS_ADDRESS
              port: 53
              network: $MAPDNS_NETWORK
              netmask: $MAPDNS_NETMASK
              cache-size: 10000

            misc:
              log-file: stderr
              log-level: info
            """.trimIndent()
        )
        return tun2socksConf
    }

    private fun scheduleRetry(configPath: String) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RESTART_DELAY_MS)
            startVpnChecked(true, configPath, isMigration = true)
        }
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return@mapNotNull null
            }
            network to caps
        }

        val active = connectivityManager.activeNetwork
        candidates.firstOrNull { (network, caps) ->
            network == active && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.let { return it.first }
        candidates.firstOrNull { (network, _) -> network == active }?.let { return it.first }
        candidates.firstOrNull { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.let { return it.first }
        candidates.firstOrNull { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.let { return it.first }
        candidates.firstOrNull { (_, caps) ->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.let { return it.first }
        return candidates.firstOrNull()?.first
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        }
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) {
                addLog(successLog)
            }
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    companion object {
        init {
            try {
                System.loadLibrary("tun2socks")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libtun2socks.so", e)
            }
        }

        const val ACTION_START_VPN =
            "us.leaf3stones.hy2droid.proxy.Hysteria2VpnService.ACTION_START_VPN"
        const val ACTION_STOP_VPN =
            "us.leaf3stones.hy2droid.proxy.Hysteria2VpnService.ACTION_STOP_VPN"

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs = _logs.asStateFlow()

        private val _isConnected = MutableStateFlow(false)
        val isConnected = _isConnected.asStateFlow()

        var configRepository: HysteriaConfigRepository? = null

        private const val LOCAL_SOCKS_PORT = 10808
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        private const val START_RETRY_GRACE_MS = 500L
        private const val TUNNEL_HANDOFF_DELAY_MS = 500L
        private const val RESTART_DELAY_MS = 3_000L
        private const val MIGRATION_DEBOUNCE_MS = 3_000L
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val IPV4_PREFIX_LENGTH = 24
        private const val MAPDNS_ADDRESS = "1.1.1.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"

        fun addLog(msg: String) {
            Log.d(TAG, msg)
            _logs.update { (it + msg).takeLast(120) }
        }

        private const val TAG = "Hysteria2VpnService"
    }
}
