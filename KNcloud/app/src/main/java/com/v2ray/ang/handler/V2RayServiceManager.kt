package com.v2ray.ang.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.handler.boot.BootVpnConfig
import com.v2ray.ang.extension.toast
import com.v2ray.ang.service.ServiceControl
import com.v2ray.ang.service.V2RayProxyOnlyService
import com.v2ray.ang.service.V2RayVpnService
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.handler.PluginServiceManager
import com.v2ray.ang.util.Utils
import go.Seq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.lang.ref.SoftReference

object V2RayServiceManager {

    private val coreController: CoreController = Libv2ray.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null

    // The core is stopped asynchronously; a start that arrives while the previous
    // core is still shutting down must wait for it, otherwise startCoreLoop() sees
    // isRunning == true and silently does nothing (node switch "doesn't take").
    @Volatile
    private var stopJob: Job? = null

    private const val CORE_STOP_WAIT_MS = 3000L

    /**
     * :vpnCore process: true while the core runs (or is about to run) the temporary login
     * bootstrap config ([BootVpnConfig]): only kncloud.top through the built-in boot node,
     * everything else direct. It is never a stored profile, so the server list and the selected
     * server are untouched. Set from the service start intent ([AppConfig.EXTRA_BOOT_VPN]).
     */
    @Volatile
    private var bootMode = false
    private var bootTtlJob: Job? = null

    /**
     * UI process: a bootstrap VPN was requested from here and not stopped yet. (The core runs in
     * the :vpnCore process, so [isRunning] / [bootMode] are not meaningful in the UI process.)
     */
    @Volatile
    private var bootRequested = false

    /** Upper bound for an abandoned login (e.g. the user never comes back from the browser). */
    private const val BOOT_VPN_MAX_LIFETIME_MS = 20 * 60 * 1000L

    /** Only meaningful in the :vpnCore process (V2RayVpnService). */
    fun isBootMode(): Boolean = bootMode

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            Seq.setContext(value?.get()?.getService()?.applicationContext)
            Libv2ray.initCoreEnv(Utils.userAssetPath(value?.get()?.getService()), Utils.getDeviceIdForXUDPBaseKey())
        }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        if (needsVpnPermission(context)) {
            // 尚未授予 VPN 权限：打开 App 弹出系统授权，授权后自动连接
            try {
                context.startActivity(vpnPermissionIntent(context))
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "Cannot open app for VPN permission", e)
                context.toast(R.string.toast_vpn_permission_required)
            }
            return false
        }
        startContextService(context)
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    /**
     * VPN 模式下系统尚未授予（或已被其他 VPN 应用收回）VPN 权限。
     */
    fun needsVpnPermission(context: Context): Boolean {
        val isVpnMode = (MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: AppConfig.VPN) == AppConfig.VPN
        return isVpnMode && VpnService.prepare(context) != null
    }

    /**
     * 打开主界面请求 VPN 权限，授权成功后自动连接。
     */
    fun vpnPermissionIntent(context: Context): Intent =
        Intent(context.applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AppConfig.EXTRA_CONNECT_AFTER_VPN_GRANT, true)

    fun startVService(context: Context, guid: String? = null) {
        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }
        startContextService(context)
    }

    /**
     * UI process. Starts the temporary login bootstrap VPN (always VpnService mode). The caller
     * must have the VPN permission already (VpnService.prepare == null).
     * @return false if not started (no boot node configured, no permission).
     */
    fun startBootVService(context: Context): Boolean {
        if (BootVpnConfig.node() == null) return false
        if (VpnService.prepare(context) != null) {
            Log.w(AppConfig.TAG, "Boot VPN: VPN permission not granted")
            return false
        }
        Log.w(AppConfig.TAG, "Starting login bootstrap VPN")
        val intent = Intent(context.applicationContext, V2RayVpnService::class.java)
            .putExtra(AppConfig.EXTRA_BOOT_VPN, true)
        return try {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            bootRequested = true
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start login bootstrap VPN", e)
            false
        }
    }

    /** UI process: whether a VPN network is what this app's traffic currently uses. */
    fun isVpnNetworkActive(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (e: Exception) {
        false
    }

    /** UI process: the bootstrap VPN requested from this process is up. */
    fun isBootVpnUp(context: Context): Boolean = bootRequested && isVpnNetworkActive(context)

    /**
     * Any process. Stops the login bootstrap VPN if (and only if) it is what the core runs; a
     * normal connection is never touched (the :vpnCore side checks its own mode).
     */
    fun stopBootVService(context: Context) {
        bootRequested = false
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP_BOOT, "")
    }

    /**
     * UI process, background thread: stops the bootstrap VPN and waits (bounded) until its VPN
     * network is gone, so the next screen does not start while it is still up.
     */
    fun stopBootVServiceAndWait(context: Context, timeoutMs: Long = 4000L) {
        val wasRequested = bootRequested
        stopBootVService(context)
        val deadline = System.currentTimeMillis() + if (wasRequested) timeoutMs else 1500L
        Thread.sleep(200)
        while (isVpnNetworkActive(context) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
    }

    /**
     * :vpnCore process, from V2RayVpnService.onStartCommand: picks the mode of the core about to
     * be started. A start for an already running core does not change the mode.
     */
    fun onVpnServiceStartCommand(intent: Intent?) {
        pendingBootMode = intent?.getBooleanExtra(AppConfig.EXTRA_BOOT_VPN, false) == true
    }

    /** :vpnCore process: mode requested by the latest VpnService start, consumed by [startCoreLoop]. */
    @Volatile
    private var pendingBootMode = false

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     */
    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            return
        }
        val guid = MmkvManager.getSelectServer() ?: return
        val config = MmkvManager.decodeServerConfig(guid) ?: return
        if (config.configType != EConfigType.CUSTOM
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) return
//        val result = V2rayConfigUtil.getV2rayConfig(context, guid)
//        if (!result.status) return

        if (needsVpnPermission(context)) {
            // 没有 VPN 权限时不启动服务（开机自启 / Tasker 等后台入口无法弹授权）
            Log.w(AppConfig.TAG, "VPN permission not granted, skip starting service")
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) == true) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        }
        val intent = if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: AppConfig.VPN) == AppConfig.VPN) {
            Intent(context.applicationContext, V2RayVpnService::class.java)
        } else {
            Intent(context.applicationContext, V2RayProxyOnlyService::class.java)
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(): Boolean {
        if (coreController.isRunning) {
            return false
        }

        val service = getService() ?: return false
        bootMode = pendingBootMode
        pendingBootMode = false
        val boot = bootMode
        val config: ProfileItem
        val result: ConfigResult
        if (boot) {
            val node = BootVpnConfig.node() ?: return false
            config = ProfileItem.create(EConfigType.SHADOWSOCKS).apply {
                remarks = service.getString(R.string.boot_vpn_name)
                server = node.address
                serverPort = node.port.toString()
            }
            result = ConfigResult(
                true, null,
                BootVpnConfig.build(node, SettingsManager.getSocksPort(), SettingsManager.getDomesticDnsServers())
            )
        } else {
            val guid = MmkvManager.getSelectServer() ?: return false
            config = MmkvManager.decodeServerConfig(guid) ?: return false
            result = V2rayConfigManager.getV2rayConfig(service, guid)
            if (!result.status)
                return false
        }

        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to register broadcast receiver", e)
            return false
        }

        currentConfig = config

        try {
            coreController.startLoop(result.content, 0)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start Core loop", e)
            return false
        }

        if (coreController.isRunning == false) {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            NotificationManager.cancelNotification()
            return false
        }

        try {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.showNotification(currentConfig)
            NotificationManager.startSpeedNotification(currentConfig)

            if (!boot) PluginServiceManager.runPlugin(service, config, result.socksPort)
            if (boot) {
                Log.w(AppConfig.TAG, "Login bootstrap VPN started")
                bootTtlJob?.cancel()
                bootTtlJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(BOOT_VPN_MAX_LIFETIME_MS)
                    if (bootMode && coreController.isRunning) {
                        Log.w(AppConfig.TAG, "Login bootstrap VPN lifetime reached, stopping")
                        serviceControl?.get()?.stopService()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to startup service", e)
            return false
        }
        return true
    }

    /**
     * Starts the core once any previous core has finished stopping.
     * Called from a service's onStartCommand. If the core cannot be started,
     * the UI is told (instead of being left in "connecting") and the service stops.
     * @param onStarted Invoked on the main thread after the core started.
     */
    fun startCoreLoopWhenReady(onStarted: () -> Unit = {}) {
        CoroutineScope(Dispatchers.Main).launch {
            withTimeoutOrNull(CORE_STOP_WAIT_MS) {
                stopJob?.join()
                while (coreController.isRunning) delay(50)
            }
            if (coreController.isRunning) {
                // Still running: either a duplicate start for a live core, or the old
                // core hung while stopping. Either way leave it as it is.
                Log.w(AppConfig.TAG, "Core still running, start request ignored")
                // A start via startForegroundService() must still reach startForeground().
                currentConfig?.let { NotificationManager.showNotification(it) }
                return@launch
            }
            if (startCoreLoop()) {
                onStarted()
            } else if (!coreController.isRunning) {
                val service = getService() ?: return@launch
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
                serviceControl?.get()?.stopService()
            }
        }
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        if (coreController.isRunning) {
            stopJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to stop V2Ray loop", e)
                }
            }
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to unregister broadcast receiver", e)
        }
        PluginServiceManager.stopPlugin()

        if (bootMode) {
            bootMode = false
            bootTtlJob?.cancel()
            bootTtlJob = null
        }
        return true
    }

    /**
     * Queries all outbound traffic statistics.
     * @return A map of Pair(tag, direction) to byte count.
     */
    fun queryAllOutboundTrafficStats(): Map<Pair<String, String>, Long> {
        val statsStr = coreController.queryAllOutboundTrafficStats()
        if (statsStr.isNullOrEmpty()) return emptyMap()
        val result = mutableMapOf<Pair<String, String>, Long>()
        statsStr.split(";").forEach { item ->
            val parts = item.split(",")
            if (parts.size == 3) {
                val tag = parts[0]
                val direction = parts[1]
                val value = parts[2].toLongOrNull() ?: 0L
                result[Pair(tag, direction)] = value
            }
        }
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to measure delay with primary URL", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to measure delay with alternative URL", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val selectServer = MmkvManager.getSelectServer().orEmpty()
            if (selectServer.isNotEmpty()) {
                MmkvManager.encodeServerTestDelayMillis(selectServer, time)
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to stop service in callback", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    Log.i(AppConfig.TAG, "Stop Service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_STOP_BOOT -> {
                    if (bootMode) {
                        Log.w(AppConfig.TAG, "Stop login bootstrap VPN")
                        serviceControl.stopService()
                    }
                }

                AppConfig.MSG_STATE_RESTART -> {
                    Log.i(AppConfig.TAG, "Restart Service")
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    startVService(serviceControl.getService())
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(AppConfig.TAG, "SCREEN_OFF, stop querying stats")
                    NotificationManager.stopSpeedNotification(currentConfig)
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(AppConfig.TAG, "SCREEN_ON, start querying stats")
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }
}