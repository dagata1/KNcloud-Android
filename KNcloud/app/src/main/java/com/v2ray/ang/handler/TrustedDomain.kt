package com.v2ray.ang.handler

import java.net.URI

/**
 * Decides which hosts the app trusts for login, API calls, WebView pages and
 * one-click deep links. Pure Kotlin so it can be unit tested.
 *
 * Trusted = the built-in KNcloud domain (and its subdomains), plus any host the
 * official domain-discovery endpoint handed out (passed in as [extraHosts]).
 */
object TrustedDomain {
    private val BUILTIN_SUFFIXES = listOf("kncloud.top")

    fun hostOf(url: String?): String? = try {
        URI(url?.trim().orEmpty()).host?.lowercase()?.trimEnd('.')
    } catch (_: Exception) {
        null
    }

    fun isBuiltinHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        if (h.isEmpty()) return false
        return BUILTIN_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    fun isTrustedHost(host: String?, extraHosts: Collection<String> = emptyList()): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        if (h.isEmpty()) return false
        return isBuiltinHost(h) || extraHosts.any { it.isNotBlank() && it.lowercase().trimEnd('.') == h }
    }

    /** https only, no user-info, trusted host. */
    fun isTrustedUrl(url: String?, extraHosts: Collection<String> = emptyList()): Boolean {
        val uri = try {
            URI(url?.trim().orEmpty())
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.rawUserInfo != null) return false
        return isTrustedHost(uri.host, extraHosts)
    }

    /**
     * Normalizes a domain coming from outside (deep link, web page):
     * adds https:// when missing, strips the trailing slash and returns it only
     * when it is trusted. Plain http is rejected.
     */
    fun normalizeTrustedDomain(raw: String?, extraHosts: Collection<String> = emptyList()): String? {
        var domain = raw?.trim().orEmpty()
        if (domain.isEmpty()) return null
        if (!domain.contains("://")) domain = "https://$domain"
        domain = domain.trimEnd('/')
        if (!isTrustedUrl(domain, extraHosts)) return null
        val uri = URI(domain)
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
        return domain
    }
}
