package com.v2ray.ang.handler.boot

import com.v2ray.ang.BuildConfig

/**
 * The built-in login bootstrap node. Values come from build config (app/build.gradle.kts
 * buildConfigField BOOT_NODE_*), nowhere else. Empty password = feature off.
 */
object BootNodeConfig {
    fun config(): BootProxy.Config? {
        val password = BuildConfig.BOOT_NODE_PASSWORD
        val port = BuildConfig.BOOT_NODE_PORT
        if (password.isBlank() || port <= 0 || BuildConfig.BOOT_NODE_METHOD != "chacha20-ietf-poly1305") return null
        val ups = ArrayList<Pair<String, Int>>()
        if (BuildConfig.BOOT_NODE_HOST.isNotBlank()) ups += BuildConfig.BOOT_NODE_HOST to port
        if (BuildConfig.BOOT_NODE_IPV6.isNotBlank()) ups += BuildConfig.BOOT_NODE_IPV6 to port
        if (ups.isEmpty()) return null
        return BootProxy.Config(upstreams = ups, password = password)
    }

    val enabled: Boolean get() = config() != null
}
