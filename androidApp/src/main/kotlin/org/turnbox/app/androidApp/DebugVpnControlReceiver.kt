package org.turnbox.app.androidApp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.ContextCompat
import us.leaf3stones.hy2droid.proxy.Hysteria2VpnService

class DebugVpnControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.isDebuggableApp()) return

        val serviceAction = when (intent.action) {
            ACTION_DEBUG_START_VPN -> Hysteria2VpnService.ACTION_START_VPN
            ACTION_DEBUG_STOP_VPN -> Hysteria2VpnService.ACTION_STOP_VPN
            else -> return
        }

        val serviceIntent = Intent(context, Hysteria2VpnService::class.java).apply {
            action = serviceAction
        }

        if (serviceAction == Hysteria2VpnService.ACTION_STOP_VPN) {
            context.stopService(serviceIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun Context.isDebuggableApp(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    companion object {
        const val ACTION_DEBUG_START_VPN = "org.turnbox.app.androidApp.DEBUG_START_VPN"
        const val ACTION_DEBUG_STOP_VPN = "org.turnbox.app.androidApp.DEBUG_STOP_VPN"
    }
}
