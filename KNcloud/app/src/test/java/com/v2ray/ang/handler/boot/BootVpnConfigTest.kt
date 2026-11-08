package com.v2ray.ang.handler.boot

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootVpnConfigTest {

    private val node = BootVpnConfig.Node("2001:db8::1", 3456, "chacha20-ietf-poly1305", "pw")

    @Test
    fun routesOnlyOfficialDomainThroughBootNode() {
        val json = JsonParser.parseString(BootVpnConfig.build(node, 10808, listOf("223.5.5.5"))).asJsonObject

        val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject }
        // default route (first outbound) is direct
        assertEquals("direct", outbounds[0].get("tag").asString)
        val boot = outbounds.first { it.get("tag").asString == "boot" }
        assertEquals("shadowsocks", boot.get("protocol").asString)
        val server = boot.getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject
        assertEquals("2001:db8::1", server.get("address").asString)
        assertEquals(3456, server.get("port").asInt)
        assertEquals("chacha20-ietf-poly1305", server.get("method").asString)

        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val bootRules = rules.filter { it.get("outboundTag").asString == "boot" }
        assertEquals(1, bootRules.size)
        assertEquals("domain:kncloud.top", bootRules[0].getAsJsonArray("domain")[0].asString)
        assertEquals("tcp", bootRules[0].get("network").asString)
        // DNS through the TUN goes to the DNS module, before any domain rule
        assertEquals("dns-out", rules[0].get("outboundTag").asString)
        assertEquals("53", rules[0].get("port").asString)
        // catch-all is direct
        assertEquals("direct", rules.last().get("outboundTag").asString)

        val inbound = json.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals(10808, inbound.get("port").asInt)
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        val sniff = inbound.getAsJsonObject("sniffing")
        assertTrue(sniff.get("enabled").asBoolean)
        assertFalse(sniff.get("routeOnly").asBoolean)
        assertTrue(sniff.getAsJsonArray("destOverride").any { it.asString == "fakedns" })

        val dnsServers = json.getAsJsonObject("dns").getAsJsonArray("servers")
        val fake = dnsServers[0].asJsonObject
        assertEquals("fakedns", fake.get("address").asString)
        assertEquals("domain:kncloud.top", fake.getAsJsonArray("domains")[0].asString)
        assertTrue(dnsServers.any { it.isJsonPrimitive && it.asString == "223.5.5.5" })
        assertNotNull(json.getAsJsonArray("fakedns"))
    }

    @Test
    fun nodeFromBuildConfigPrefersIpv6Literal() {
        val n = BootVpnConfig.node() ?: return // feature off in this build
        assertFalse(n.address.startsWith("["))
        assertTrue(n.port > 0)
    }
}
