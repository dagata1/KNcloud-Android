package com.v2ray.ang.handler

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.fmt.AnytlsFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.service.ProcessService
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.io.File

/**
 * 管理 Xray 无法直连的协议所需的本地插件进程（Hysteria2、AnyTLS）。
 * 插件在 127.0.0.1:socksPort 提供 socks5，Xray 的出站指向它。
 */
object PluginServiceManager {
    private const val HYSTERIA2 = "libhysteria2.so"
    private const val ANYTLS = "libanytls.so"

    private val procService: ProcessService by lazy {
        ProcessService()
    }

    /** 当前运行中插件的配置文件（含密码），停止时删除。 */
    @Volatile
    private var runningConfigFile: File? = null

    /**
     * Run the plugin based on the provided configuration.
     *
     * @param context The context to use.
     * @param config The profile configuration.
     * @param socksPort The port information.
     */
    fun runPlugin(context: Context, config: ProfileItem?, socksPort: Int?) {
        if (config == null) {
            Log.w(AppConfig.TAG, "Cannot run plugin: config is null")
            return
        }
        if (!config.configType.needsPlugin) {
            return
        }
        if (socksPort == null) {
            Log.w(AppConfig.TAG, "Cannot run plugin: socksPort is null")
            return
        }

        try {
            Log.i(AppConfig.TAG, "Running ${config.configType} plugin")
            val (cmd, configFile) = genCmd(context, config, socksPort) ?: return
            runningConfigFile = configFile
            procService.runProcess(context, cmd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error running plugin", e)
        }
    }

    /**
     * Stop the running plugin.
     */
    fun stopPlugin() {
        try {
            Log.i(AppConfig.TAG, "plugin destroy")
            procService.stopProcess()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop plugin process", e)
        }
        runningConfigFile?.delete()
        runningConfigFile = null
    }

    /**
     * Real ping through a temporary plugin process.
     *
     * @return The ping delay in milliseconds, or -1 if it fails.
     */
    fun realPingPlugin(context: Context, config: ProfileItem?): Long {
        val retFailure = -1L
        if (config == null || !config.configType.needsPlugin) {
            return retFailure
        }

        val socksPort = Utils.findFreePort(listOf(0))
        val (cmd, configFile) = genCmd(context, config, socksPort) ?: return retFailure

        val proc = ProcessService()
        proc.runProcess(context, cmd)
        try {
            Thread.sleep(1000L)
            return SpeedtestManager.testConnection(context, socksPort).first
        } finally {
            proc.stopProcess()
            configFile.delete()
        }
    }

    private fun genCmd(context: Context, config: ProfileItem, socksPort: Int): Pair<MutableList<String>, File>? {
        return when (config.configType) {
            EConfigType.HYSTERIA2 -> {
                val hy2Config = Hysteria2Fmt.toNativeConfig(config, socksPort) ?: return null
                val configFile = writeConfig(context, "hy2", JsonUtil.toJson(hy2Config))
                mutableListOf(
                    File(context.applicationInfo.nativeLibraryDir, HYSTERIA2).absolutePath,
                    "--disable-update-check",
                    "--config",
                    configFile.absolutePath,
                    "--log-level",
                    "warn",
                    "client"
                ) to configFile
            }

            EConfigType.ANYTLS -> {
                val anytlsConfig = AnytlsFmt.toNativeConfig(config, socksPort) ?: return null
                val configFile = writeConfig(context, "anytls", JsonUtil.toJson(anytlsConfig))
                mutableListOf(
                    File(context.applicationInfo.nativeLibraryDir, ANYTLS).absolutePath,
                    "-c",
                    configFile.absolutePath
                ) to configFile
            }

            else -> null
        }
    }

    /** 配置里含节点密码，只写到 noBackupFilesDir，且不打印内容；用完即删。 */
    private fun writeConfig(context: Context, prefix: String, content: String): File {
        val dir = context.noBackupFilesDir
        dir.mkdirs()
        val configFile = File.createTempFile("${prefix}_${SystemClock.elapsedRealtime()}_", ".json", dir)
        configFile.writeText(content)
        Log.i(AppConfig.TAG, "plugin config ${configFile.absolutePath}")
        return configFile
    }
}
