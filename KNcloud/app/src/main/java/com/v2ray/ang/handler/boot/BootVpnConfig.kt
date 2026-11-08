package com.v2ray.ang.handler.boot

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.BuildConfig

/**
 * Xray config for the temporary "login bootstrap" VPN (see V2RayServiceManager.startBootVService).
 *
 * Only the official domains (kncloud.top and its subdomains) go through the built-in boot node
 * (Shadowsocks); everything else goes DIRECT. The config is generated on the fly and never stored
 * as a server profile, so it never shows up in the server list and is never the selected server.
 *
 * DNS: the VPN's DNS server (1.1.1.1:53 through the TUN) is answered by the core's DNS module:
 * kncloud.top names get a fake IP (fakedns), which sniffing maps back to the domain, so the node
 * receives the domain name (it resolves it itself) even if local DNS for it is poisoned / blocked.
 * TLS / HTTP sniffing also recovers the domain when an app connects to a real IP it resolved
 * before the VPN came up. All other names are resolved with the domestic DNS, directly.
 */
object BootVpnConfig {

    const val TAG_BOOT = "boot"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_DNS_OUT = "dns-out"
    const val TAG_DNS_MODULE = "boot-dns"
    const val TAG_SOCKS_IN = "socks"
    const val BOOT_DOMAIN = "domain:kncloud.top"
    const val FAKE_IP_POOL = "198.18.0.0/15"

    data class Node(val address: String, val port: Int, val method: String, val password: String)

    /** The boot node from build config, the IPv6 literal preferred (no DNS needed to reach it). */
    fun node(): Node? {
        val password = BuildConfig.BOOT_NODE_PASSWORD
        val port = BuildConfig.BOOT_NODE_PORT
        if (password.isBlank() || port <= 0) return null
        val address = BuildConfig.BOOT_NODE_IPV6.ifBlank { BuildConfig.BOOT_NODE_HOST }
        if (address.isBlank()) return null
        return Node(address.removePrefix("[").removeSuffix("]"), port, BuildConfig.BOOT_NODE_METHOD, password)
    }

    private fun arr(vararg items: String) = JsonArray().apply { items.forEach { add(it) } }

    private fun rule(outbound: String, block: JsonObject.() -> Unit) = JsonObject().apply {
        addProperty("type", "field")
        block()
        addProperty("outboundTag", outbound)
    }

    fun build(node: Node, socksPort: Int, domesticDns: List<String>, logLevel: String = "warning"): String {
        val root = JsonObject()
        root.add("log", JsonObject().apply { addProperty("loglevel", logLevel) })

        // Inbound used by hev-socks5-tunnel (TUN) and, as an HTTP proxy (Xray's socks inbound also
        // accepts HTTP), by the app's own panel / subscription requests.
        root.add("inbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", TAG_SOCKS_IN)
                addProperty("listen", "127.0.0.1")
                addProperty("port", socksPort)
                addProperty("protocol", "socks")
                add("settings", JsonObject().apply {
                    addProperty("auth", "noauth")
                    addProperty("udp", true)
                    addProperty("userLevel", 8)
                })
                add("sniffing", JsonObject().apply {
                    addProperty("enabled", true)
                    add("destOverride", arr("http", "tls", "fakedns"))
                    addProperty("routeOnly", false)
                })
            })
        })

        root.add("outbounds", JsonArray().apply {
            // first outbound = default route
            add(JsonObject().apply {
                addProperty("tag", TAG_DIRECT)
                addProperty("protocol", "freedom")
                add("settings", JsonObject().apply { addProperty("domainStrategy", "UseIP") })
            })
            add(JsonObject().apply {
                addProperty("tag", TAG_BOOT)
                addProperty("protocol", "shadowsocks")
                add("settings", JsonObject().apply {
                    add("servers", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("address", node.address)
                            addProperty("port", node.port)
                            addProperty("method", node.method)
                            addProperty("password", node.password)
                            addProperty("level", 8)
                        })
                    })
                })
                add("streamSettings", JsonObject().apply { addProperty("network", "tcp") })
            })
            add(JsonObject().apply {
                addProperty("tag", TAG_BLOCK)
                addProperty("protocol", "blackhole")
            })
            add(JsonObject().apply {
                addProperty("tag", TAG_DNS_OUT)
                addProperty("protocol", "dns")
            })
        })

        root.add("dns", JsonObject().apply {
            addProperty("tag", TAG_DNS_MODULE)
            addProperty("queryStrategy", "UseIP")
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", "fakedns")
                    add("domains", arr(BOOT_DOMAIN))
                    addProperty("skipFallback", true)
                })
                val plain = (domesticDns.filter { it.isNotBlank() } + listOf("223.5.5.5", "119.29.29.29", "1.1.1.1")).distinct()
                plain.forEach { add(it) }
            })
        })
        root.add("fakedns", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("ipPool", FAKE_IP_POOL)
                addProperty("poolSize", 65535)
            })
        })

        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", JsonArray().apply {
                // DNS queries coming through the TUN -> the core's DNS module
                add(rule(TAG_DNS_OUT) { add("inboundTag", arr(TAG_SOCKS_IN)); addProperty("port", "53") })
                // the DNS module's own upstream queries go direct
                add(rule(TAG_DIRECT) { add("inboundTag", arr(TAG_DNS_MODULE)) })
                // the node only relays TCP; refuse QUIC etc. at once so browsers fall back to TCP
                add(rule(TAG_BLOCK) { add("domain", arr(BOOT_DOMAIN)); addProperty("network", "udp") })
                add(rule(TAG_BOOT) { add("domain", arr(BOOT_DOMAIN)); addProperty("network", "tcp") })
                // a fake IP whose domain could not be recovered: nowhere to send it
                add(rule(TAG_BLOCK) { add("ip", arr(FAKE_IP_POOL)) })
                add(rule(TAG_DIRECT) { addProperty("network", "tcp,udp") })
            })
        })

        root.add("policy", JsonObject().apply {
            add("levels", JsonObject().apply {
                add("8", JsonObject().apply {
                    addProperty("handshake", 4)
                    addProperty("connIdle", 300)
                    addProperty("uplinkOnly", 1)
                    addProperty("downlinkOnly", 1)
                })
            })
        })
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }
}
