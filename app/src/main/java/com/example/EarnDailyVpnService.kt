package com.example

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

class EarnDailyVpnService : VpnService() {

  companion object {
    const val TAG = "EarnDaily_VPN"
    const val ACTION_CONNECT = "com.earndaily.vpn.CONNECT"
    const val ACTION_DISCONNECT = "com.earndaily.vpn.DISCONNECT"
    const val EXTRA_SERVER = "extra_server"

    @Volatile
    var isConnected: Boolean = false
      private set

    @Volatile
    var currentServer: String = "US - New York"
      private set

    @Volatile
    var currentIp: String = "198.51.100.45"
      private set

    fun getIpForServer(server: String): String {
      return when {
        server.contains("Los Angeles", ignoreCase = true) -> "198.51.100.82"
        server.contains("Chicago", ignoreCase = true) -> "198.51.100.104"
        server.contains("Miami", ignoreCase = true) -> "198.51.100.155"
        server.contains("Dallas", ignoreCase = true) -> "198.51.100.201"
        else -> "198.51.100.45"
      }
    }
  }

  private var vpnInterface: ParcelFileDescriptor? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CONNECT -> {
        val server = intent.getStringExtra(EXTRA_SERVER) ?: "US - New York"
        connectVpn(server)
      }
      ACTION_DISCONNECT -> {
        disconnectVpn()
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  private fun connectVpn(server: String) {
    try {
      vpnInterface?.close()
      currentServer = server
      currentIp = getIpForServer(server)

      val builder = Builder()
        .setSession("EarnDaily Free US VPN ($server)")
        .addAddress("10.8.0.2", 24)
        .addDnsServer("8.8.8.8")
        .addDnsServer("1.1.1.1")

      // Allow the app to bypass VPN routing to ensure WebView, Unity Ads, and Firebase work reliably
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          builder.addDisallowedApplication(packageName)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Disallowed application configuration: ${e.message}")
      }

      builder.addRoute("10.8.0.0", 24)

      vpnInterface = builder.establish()
      isConnected = true
      Log.i(TAG, "US VPN established: $server ($currentIp)")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to establish US VPN: ${e.message}", e)
      isConnected = false
    }
  }

  private fun disconnectVpn() {
    try {
      vpnInterface?.close()
    } catch (e: Exception) {
      Log.w(TAG, "Error closing VPN interface: ${e.message}")
    } finally {
      vpnInterface = null
      isConnected = false
    }
    Log.i(TAG, "US VPN disconnected")
  }

  override fun onDestroy() {
    disconnectVpn()
    super.onDestroy()
  }
}
