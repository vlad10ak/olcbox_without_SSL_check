package org.turnbox.app.vpn

import kotlinx.coroutines.flow.StateFlow
import org.turnbox.app.data.model.HysteriaConfig

interface VpnManager {
    val logs: StateFlow<List<String>>
    val isConnected: StateFlow<Boolean>
    fun needsPermission(): Boolean
    fun startVpn()
    fun stopVpn()
    suspend fun ping(hysteriaConfig: HysteriaConfig): Long?
    suspend fun checkConnection(hysteriaConfig: HysteriaConfig): Long?
}
