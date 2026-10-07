package com.v2ray.ang

import com.v2ray.ang.handler.TrustedDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDomainTest {

    @Test
    fun builtinDomainAndSubdomainsAreTrusted() {
        assertTrue(TrustedDomain.isTrustedUrl("https://www.kncloud.top"))
        assertTrue(TrustedDomain.isTrustedUrl("https://kncloud.top/#/register"))
        assertTrue(TrustedDomain.isTrustedUrl("https://aws.kncloud.top/api/domain/cloud"))
    }

    @Test
    fun lookalikeHostsAreRejected() {
        assertFalse(TrustedDomain.isTrustedUrl("https://evilkncloud.top"))
        assertFalse(TrustedDomain.isTrustedUrl("https://kncloud.top.evil.com"))
        assertFalse(TrustedDomain.isTrustedUrl("https://kncloud.top@evil.com"))
        assertFalse(TrustedDomain.isTrustedUrl("https://user@www.kncloud.top"))
    }

    @Test
    fun plainHttpIsRejected() {
        assertFalse(TrustedDomain.isTrustedUrl("http://www.kncloud.top"))
        assertNull(TrustedDomain.normalizeTrustedDomain("http://www.kncloud.top"))
    }

    @Test
    fun extraHostFromDomainDiscoveryIsTrusted() {
        val extra = listOf("panel.example.net")
        assertTrue(TrustedDomain.isTrustedUrl("https://panel.example.net", extra))
        assertFalse(TrustedDomain.isTrustedUrl("https://sub.panel.example.net", extra))
        assertFalse(TrustedDomain.isTrustedUrl("https://panel.example.net"))
    }

    @Test
    fun normalizeAddsSchemeAndStripsSlash() {
        assertEquals("https://www.kncloud.top", TrustedDomain.normalizeTrustedDomain("www.kncloud.top/"))
        assertEquals("https://www.kncloud.top", TrustedDomain.normalizeTrustedDomain(" https://www.kncloud.top "))
        assertNull(TrustedDomain.normalizeTrustedDomain("https://www.kncloud.top/path"))
        assertNull(TrustedDomain.normalizeTrustedDomain("evil.com"))
        assertNull(TrustedDomain.normalizeTrustedDomain(""))
        assertNull(TrustedDomain.normalizeTrustedDomain(null))
    }
}
