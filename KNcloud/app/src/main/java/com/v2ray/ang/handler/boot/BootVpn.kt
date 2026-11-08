package com.v2ray.ang.handler.boot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager

/**
 * Login bootstrap VPN, built on the app's normal node connection: the built-in boot node is stored
 * as a HIDDEN custom profile (full Xray JSON from [BootVpnConfig]: kncloud.top -> boot node,
 * everything else direct) that is not in the server list, selected and started with the very same
 * [V2RayServiceManager.startVService] / [V2RayServiceManager.stopVService] as any user node (same
 * VpnService, same exclusion of the app itself, same core process). Once the login finished (or the
 * login screen is left) the VPN is stopped, the hidden profile deleted and the previous selection
 * restored.
 *
 * Runs in the UI process; the core state lives in the :vpnCore process, so "is it up" is read from
 * the selected server + the system's VPN network state.
 */
object BootVpn {
    const val GUID = "kncloud-login-boot"
    private const val KEY_PREV_SELECTED = "pref_boot_vpn_prev_selected"

    /** Safety net for an abandoned login (user never returns from the browser): stop after this. */
    private const val MAX_LIFETIME_MS = 10 * 60 * 1000L
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var ttl: Runnable? = null

    val available: Boolean get() = BootVpnConfig.node() != null

    /** The boot profile is the selected server (i.e. what a running core runs). */
    fun isSelected(): Boolean = MmkvManager.getSelectServer() == GUID

    /** Whether any VPN network is up (the app itself is outside its VPN, so check all networks). */
    @Suppress("DEPRECATION")
    fun isVpnNetworkUp(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (e: Exception) {
        false
    }

    /** The boot VPN is selected and a VPN network is up. */
    fun isUp(context: Context): Boolean = isSelected() && isVpnNetworkUp(context)

    /**
     * Writes the hidden boot profile and starts it through the normal connect path. The caller
     * must hold the VPN permission (VpnService.prepare == null).
     */
    fun start(context: Context): Boolean {
        val node = BootVpnConfig.node() ?: return false
        return try {
            val profile = ProfileItem.create(EConfigType.CUSTOM).apply {
                remarks = context.getString(R.string.boot_vpn_name)
                server = node.address
                serverPort = node.port.toString()
            }
            val raw = BootVpnConfig.build(node, SettingsManager.getSocksPort(), SettingsManager.getDomesticDnsServers())
            MmkvManager.encodeHiddenServerConfig(GUID, profile, raw)
            val prev = MmkvManager.getSelectServer()
            if (prev != GUID) {
                MmkvManager.encodeSettings(KEY_PREV_SELECTED, prev.orEmpty())
            }
            Log.w(AppConfig.TAG, "Starting login bootstrap VPN (hidden profile)")
            V2RayServiceManager.startVService(context, GUID)
            val app = context.applicationContext
            ttl?.let { handler.removeCallbacks(it) }
            ttl = Runnable {
                Log.w(AppConfig.TAG, "Login bootstrap VPN lifetime reached")
                Thread { stopAndCleanup(app) }.start()
            }.also { handler.postDelayed(it, MAX_LIFETIME_MS) }
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start login bootstrap VPN", e)
            cleanupProfile()
            false
        }
    }

    /**
     * Stops the boot VPN if it is what runs (a user node is never touched), waits (bounded) for
     * its VPN network to go away, then removes the hidden profile and restores the selection.
     * Blocking: call from a background thread.
     */
    fun stopAndCleanup(context: Context, timeoutMs: Long = 4000L) {
        ttl?.let { handler.removeCallbacks(it) }
        ttl = null
        if (isSelected()) {
            val wasUp = isVpnNetworkUp(context)
            Log.w(AppConfig.TAG, "Stopping login bootstrap VPN")
            V2RayServiceManager.stopVService(context)
            if (wasUp) {
                val deadline = System.currentTimeMillis() + timeoutMs
                Thread.sleep(200)
                while (isVpnNetworkUp(context) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            }
        }
        cleanupProfile()
    }

    /** Non-blocking variant (e.g. from onDestroy): only stops the core if the boot profile runs. */
    fun stopAsync(context: Context) {
        if (isSelected()) {
            V2RayServiceManager.stopVService(context)
        }
    }

    private fun cleanupProfile() {
        val wasSelected = isSelected()
        MmkvManager.removeHiddenServer(GUID)
        if (wasSelected) {
            val prev = MmkvManager.decodeSettingsString(KEY_PREV_SELECTED).orEmpty()
            val servers = MmkvManager.decodeServerList()
            when {
                prev.isNotEmpty() && servers.contains(prev) -> MmkvManager.setSelectServer(prev)
                servers.isNotEmpty() -> MmkvManager.setSelectServer(servers[0])
            }
        }
        MmkvManager.encodeSettings(KEY_PREV_SELECTED, "")
    }
}
