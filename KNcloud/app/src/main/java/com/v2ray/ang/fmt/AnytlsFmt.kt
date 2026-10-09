package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.Utils
import java.net.URI

/**
 * AnyTLS 分享链接：anytls://password@host:port?sni=xx&insecure=1&fp=chrome#name
 *
 * Xray 不支持 AnyTLS，连接时由 libanytls.so 插件（见 anytls-plugin/）在本地
 * 提供 socks5，Xray 把流量转给它；与 KNcloud-WIN 的进程内桥行为一致。
 */
object AnytlsFmt : FmtBase() {
    fun parse(str: String): ProfileItem? {
        val allowInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_ALLOW_INSECURE, false)
        val config = ProfileItem.create(EConfigType.ANYTLS)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.idnHost.isEmpty() || uri.port <= 0) return null
        config.remarks = Utils.urlDecode(uri.fragment.orEmpty()).let { if (it.isEmpty()) "none" else it }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo.orEmpty()
        config.insecure = allowInsecure

        if (!uri.rawQuery.isNullOrEmpty()) {
            val queryParam = getQueryParam(uri)
            getItemFormQuery(config, queryParam, allowInsecure)
            if (config.sni.isNullOrEmpty()) config.sni = queryParam["peer"]
        }
        // AnyTLS 本身就是 TLS over TCP，其它传输字段没有意义
        config.security = AppConfig.TLS
        config.network = "tcp"
        return config
    }

    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()
        config.sni.let { if (it.isNotNullEmpty()) dicQuery["sni"] = it.orEmpty() }
        config.fingerPrint.let { if (it.isNotNullEmpty()) dicQuery["fp"] = it.orEmpty() }
        dicQuery["insecure"] = if (config.insecure == true) "1" else "0"
        return toUri(config, config.password, dicQuery)
    }

    /** 生成 libanytls.so 的配置（字段与 anytls-plugin/main.go 的 Config 对应）。 */
    fun toNativeConfig(config: ProfileItem, socksPort: Int): Map<String, Any>? {
        val port = config.serverPort?.toIntOrNull() ?: return null
        if (config.server.isNullOrEmpty() || config.password.isNullOrEmpty()) return null
        val map = linkedMapOf<String, Any>(
            "listen" to "$LOOPBACK:$socksPort",
            "server" to config.server.orEmpty(),
            "server_port" to port,
            "password" to config.password.orEmpty(),
            "insecure" to (config.insecure == true),
        )
        config.sni?.takeIf { it.isNotBlank() }?.let { map["sni"] = it.trim() }
        config.fingerPrint?.takeIf { it.isNotBlank() }?.let { map["fingerprint"] = it.trim() }
        return map
    }

    fun toOutbound(profileItem: ProfileItem): OutboundBean? {
        return V2rayConfigManager.createInitOutbound(EConfigType.ANYTLS)
    }
}
