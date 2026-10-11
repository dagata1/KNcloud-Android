package com.v2ray.ang.handler

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.SubscriptionInfo
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object KNcloudAuthService {

    /** Status code and body text of a panel API response (HTTP errors included). */
    private data class PanelResponse(val code: Int, val text: String)

    /**
     * Performs one panel API request. The app's own package is excluded from its VPN, so a plain
     * HttpURLConnection always goes direct; on networks where the panel domain is slow/blocked
     * directly that times out. When the core is running we therefore go through the local HTTP
     * proxy first and fall back to a direct connection once; when it is not running we go direct
     * with one retry. Only connection-level failures (IOException: timeout, refused, reset...)
     * trigger the next attempt; any HTTP response, including 401/403/5xx, is returned as is.
     * Throws the last IOException if every attempt fails.
     */
    private fun panelRequest(
        urlStr: String,
        method: String,
        timeout: Int,
        headers: Map<String, String>,
        body: ByteArray? = null
    ): PanelResponse {
        val proxies: List<Proxy?> = if (V2RayServiceManager.isRunning()) {
            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, SettingsManager.getHttpPort())), null)
        } else {
            listOf(null, null)
        }
        var lastError: IOException? = null
        for (proxy in proxies) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                val c = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
                conn = c
                c.requestMethod = method
                c.connectTimeout = timeout
                c.readTimeout = timeout
                c.doInput = true
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { os ->
                        os.write(body)
                        os.flush()
                    }
                }
                val responseCode = c.responseCode
                val responseStream: InputStream? = if (responseCode in 200..299) c.inputStream else c.errorStream
                val responseText = responseStream?.use { it.bufferedReader().readText() }.orEmpty()
                return PanelResponse(responseCode, responseText)
            } catch (e: IOException) {
                lastError = e
                Log.w(AppConfig.TAG, "Panel request ${if (proxy != null) "via proxy" else "direct"} failed: $urlStr: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        throw lastError ?: IOException("Panel request failed: $urlStr")
    }

    private fun panelHeaders(token: String? = null, json: Boolean = false): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        if (json) h["Content-Type"] = "application/json; charset=UTF-8"
        if (token != null) h["Authorization"] = token
        h["Accept"] = "application/json"
        h["User-Agent"] = "KNcloud/${BuildConfig.VERSION_NAME}"
        return h
    }

    data class LoginResult(
        val success: Boolean,
        val token: String? = null,
        val message: String? = null,
        val domain: String = AppConfig.DEFAULT_WEB_DOMAIN
    )

    data class SubscribeResult(
        val success: Boolean,
        val subscribeUrl: String? = null,
        val message: String? = null,
        val planName: String? = null,
        val email: String? = null,
        val u: Long = 0L,
        val d: Long = 0L,
        val transferEnable: Long = 0L,
        val expiredAt: Long? = null,
        val resetDay: Int? = null
    )

    /**
     * Extracts email from JWT token payload if present.
     */
    fun extractEmailFromJwt(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payload = parts[1]
                val decodedBytes = Base64.decode(
                    payload,
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )
                val jsonStr = String(decodedBytes, Charsets.UTF_8)
                val json = JsonUtil.parseString(jsonStr)
                if (json != null) {
                    if (json.has("email") && !json.get("email").isJsonNull) {
                        val em = json.get("email").asString.trim()
                        if (em.isNotEmpty()) return em
                    }
                    if (json.has("user") && json.get("user").isJsonObject) {
                        val userObj = json.getAsJsonObject("user")
                        if (userObj.has("email") && !userObj.get("email").isJsonNull) {
                            val em = userObj.get("email").asString.trim()
                            if (em.isNotEmpty()) return em
                        }
                    }
                    if (json.has("sub") && !json.get("sub").isJsonNull) {
                        val sub = json.get("sub").asString.trim()
                        if (sub.contains("@")) return sub
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches the dynamic domain from the remote API.
     * Falls back to stored domain or default domain if fetch fails.
     */
    fun fetchDynamicDomain(): String {
        try {
            val (responseCode, responseText) = panelRequest(
                AppConfig.API_DOMAIN_QUERY_URL, "GET", 8000, panelHeaders()
            )

            if (responseCode in 200..299 && responseText.isNotEmpty()) {
                val json = JsonUtil.parseString(responseText)
                if (json != null && json.has("data")) {
                    val dataObj = json.getAsJsonObject("data")
                    if (dataObj.has("domain")) {
                        var domain = dataObj.get("domain").asString.trim()
                        if (domain.endsWith("/")) {
                            domain = domain.dropLast(1)
                        }
                        if (domain.isNotEmpty() && domain.startsWith("https://")) {
                            MmkvManager.encodeSettings(AppConfig.PREF_DISCOVERED_DOMAIN, domain)
                            MmkvManager.encodeSettings(AppConfig.PREF_API_DOMAIN, domain)
                            return domain
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to fetch dynamic domain: ${e.message}")
        }

        return MmkvManager.getApiDomain()
    }

    /**
     * Performs login request to the KNcloud panel API.
     */
    fun login(email: String, password: String): LoginResult {
        val domain = fetchDynamicDomain()
        val loginEndpoint = "$domain/api/v1/passport/auth/login"

        try {
            val requestJson = JsonObject().apply {
                addProperty("email", email)
                addProperty("password", password)
            }

            val (responseCode, responseText) = panelRequest(
                loginEndpoint, "POST", 15000, panelHeaders(json = true),
                requestJson.toString().toByteArray(Charsets.UTF_8)
            )

            val json = JsonUtil.parseString(responseText)

            if (responseCode in 200..299) {
                if (json != null && json.has("data")) {
                    val dataObj = json.getAsJsonObject("data")
                    val token = if (dataObj.has("auth_data")) {
                        dataObj.get("auth_data").asString
                    } else if (dataObj.has("token")) {
                        dataObj.get("token").asString
                    } else {
                        null
                    }

                    if (!token.isNullOrBlank()) {
                        val effectiveEmail = email.ifBlank { extractEmailFromJwt(token).orEmpty() }
                        MmkvManager.saveUserLogin(effectiveEmail, token, domain)
                        return LoginResult(success = true, token = token, domain = domain)
                    }
                }
            }

            // Extract error message from API response
            val errorMsg = json?.get("message")?.asString ?: "登录失败 (HTTP $responseCode)"
            return LoginResult(success = false, message = errorMsg, domain = domain)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Login exception", e)
            return LoginResult(success = false, message = e.localizedMessage ?: "网络请求失败，请检查网络连接", domain = domain)
        }
    }

    /**
     * Retrieves user subscription URL and metadata from the KNcloud panel API.
     */
    fun getSubscribeUrl(domain: String, token: String): SubscribeResult {
        val subscribeEndpoint = "$domain/api/v1/user/getSubscribe"

        try {
            val (responseCode, responseText) = panelRequest(
                subscribeEndpoint, "GET", 15000, panelHeaders(token = token)
            )

            val json = JsonUtil.parseString(responseText)

            if (responseCode in 200..299) {
                if (json != null && json.has("data")) {
                    val dataObj = json.getAsJsonObject("data")
                    val subUrl = if (dataObj.has("subscribe_url") && !dataObj.get("subscribe_url").isJsonNull) {
                        dataObj.get("subscribe_url").asString
                    } else {
                        null
                    }

                    // Extract user email
                    val email = if (dataObj.has("email") && !dataObj.get("email").isJsonNull) {
                        dataObj.get("email").asString.trim()
                    } else {
                        extractEmailFromJwt(token)
                    }

                    if (!email.isNullOrBlank()) {
                        val currentEmail = MmkvManager.getUserEmail().orEmpty()
                        if (currentEmail.isBlank() || currentEmail == "kncloud@gmail.com") {
                            MmkvManager.encodeSettings(AppConfig.PREF_USER_EMAIL, email)
                        }
                    }

                    // Extract plan / subscription package name
                    var planName: String? = null
                    if (dataObj.has("plan") && dataObj.get("plan").isJsonObject) {
                        val planObj = dataObj.getAsJsonObject("plan")
                        if (planObj.has("name") && !planObj.get("name").isJsonNull) {
                            planName = planObj.get("name").asString.trim()
                        }
                    } else if (dataObj.has("plan_name") && !dataObj.get("plan_name").isJsonNull) {
                        planName = dataObj.get("plan_name").asString.trim()
                    }

                    // Extract traffic stats
                    val u = if (dataObj.has("u") && !dataObj.get("u").isJsonNull) dataObj.get("u").asLong else 0L
                    val d = if (dataObj.has("d") && !dataObj.get("d").isJsonNull) dataObj.get("d").asLong else 0L
                    val transferEnable = if (dataObj.has("transfer_enable") && !dataObj.get("transfer_enable").isJsonNull) {
                        dataObj.get("transfer_enable").asLong
                    } else {
                        0L
                    }

                    // Extract expiration timestamp
                    val expiredAt = if (dataObj.has("expired_at") && !dataObj.get("expired_at").isJsonNull) {
                        dataObj.get("expired_at").asLong
                    } else {
                        null
                    }

                    // Extract reset day
                    val resetDay = if (dataObj.has("reset_day") && !dataObj.get("reset_day").isJsonNull) {
                        dataObj.get("reset_day").asInt
                    } else {
                        null
                    }

                    // Update local SubscriptionInfo model
                    val subInfo = MmkvManager.getSubscriptionInfo() ?: SubscriptionInfo()
                    if (!planName.isNullOrBlank()) {
                        subInfo.subName = planName
                    }
                    if (transferEnable > 0L) {
                        val used = u + d
                        subInfo.traffic = "${SubscriptionInfo.formatBytes(used.toDouble())} / ${SubscriptionInfo.formatBytes(transferEnable.toDouble())}"
                    }
                    // expired_at null means the plan never expires (one-time/lifetime plan);
                    // without this branch a stale date from an earlier plan stayed on screen.
                    val expiredAtIsNull = dataObj.has("expired_at") && dataObj.get("expired_at").isJsonNull
                    if (expiredAt != null || expiredAtIsNull) {
                        subInfo.applyExpiry(expiredAt)
                    }
                    if (resetDay != null && resetDay > 0) {
                        subInfo.resetDay = "下次重置：${resetDay} 天"
                    } else if (resetDay == 0) {
                        // 0 = traffic resets at the coming midnight (last day of the cycle)
                        subInfo.resetDay = "下次重置：今天"
                    } else if (dataObj.has("reset_day") && dataObj.get("reset_day").isJsonNull) {
                        // null = no reset for this plan (lifetime / never-reset / expired);
                        // clear the value cached from a previous plan or subscription.
                        subInfo.resetDay = ""
                    }
                    if (subInfo.hasData()) {
                        MmkvManager.saveSubscriptionInfo(subInfo)
                    }

                    if (!subUrl.isNullOrBlank()) {
                        return SubscribeResult(
                            success = true,
                            subscribeUrl = subUrl,
                            planName = planName,
                            email = email,
                            u = u,
                            d = d,
                            transferEnable = transferEnable,
                            expiredAt = expiredAt,
                            resetDay = resetDay
                        )
                    }
                }
            }

            val errorMsg = json?.get("message")?.asString ?: "获取订阅失败 (HTTP $responseCode)"
            return SubscribeResult(success = false, message = errorMsg)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "getSubscribeUrl exception", e)
            return SubscribeResult(success = false, message = e.localizedMessage ?: "获取订阅网络失败")
        }
    }

    /**
     * Retrieves user profile details from /api/v1/user/info as a supplementary endpoint.
     */
    fun fetchUserInfo(domain: String, token: String): Boolean {
        val endpoint = "$domain/api/v1/user/info"
        try {
            val (responseCode, responseText) = panelRequest(
                endpoint, "GET", 10000, panelHeaders(token = token)
            )

            val json = JsonUtil.parseString(responseText)
            if (responseCode in 200..299 && json != null && json.has("data")) {
                val dataObj = json.getAsJsonObject("data")

                val email = if (dataObj.has("email") && !dataObj.get("email").isJsonNull) {
                    dataObj.get("email").asString.trim()
                } else null

                if (!email.isNullOrBlank()) {
                    val currentEmail = MmkvManager.getUserEmail().orEmpty()
                    if (currentEmail.isBlank() || currentEmail == "kncloud@gmail.com") {
                        MmkvManager.encodeSettings(AppConfig.PREF_USER_EMAIL, email)
                    }
                }

                var planName: String? = null
                if (dataObj.has("plan") && dataObj.get("plan").isJsonObject) {
                    val planObj = dataObj.getAsJsonObject("plan")
                    if (planObj.has("name") && !planObj.get("name").isJsonNull) {
                        planName = planObj.get("name").asString.trim()
                    }
                } else if (dataObj.has("plan_name") && !dataObj.get("plan_name").isJsonNull) {
                    planName = dataObj.get("plan_name").asString.trim()
                }

                val u = if (dataObj.has("u") && !dataObj.get("u").isJsonNull) dataObj.get("u").asLong else 0L
                val d = if (dataObj.has("d") && !dataObj.get("d").isJsonNull) dataObj.get("d").asLong else 0L
                val transferEnable = if (dataObj.has("transfer_enable") && !dataObj.get("transfer_enable").isJsonNull) {
                    dataObj.get("transfer_enable").asLong
                } else 0L

                val expiredAt = if (dataObj.has("expired_at") && !dataObj.get("expired_at").isJsonNull) {
                    dataObj.get("expired_at").asLong
                } else null

                val resetDay = if (dataObj.has("reset_day") && !dataObj.get("reset_day").isJsonNull) {
                    dataObj.get("reset_day").asInt
                } else null

                val subInfo = MmkvManager.getSubscriptionInfo() ?: SubscriptionInfo()
                if (!planName.isNullOrBlank()) {
                    subInfo.subName = planName
                }
                if (transferEnable > 0L) {
                    val used = u + d
                    subInfo.traffic = "${SubscriptionInfo.formatBytes(used.toDouble())} / ${SubscriptionInfo.formatBytes(transferEnable.toDouble())}"
                }
                // expired_at null means the plan never expires (one-time/lifetime plan);
                // without this branch a stale date from an earlier plan stayed on screen.
                val expiredAtIsNull = dataObj.has("expired_at") && dataObj.get("expired_at").isJsonNull
                if (expiredAt != null || expiredAtIsNull) {
                    subInfo.applyExpiry(expiredAt)
                }
                if (resetDay != null && resetDay > 0) {
                    subInfo.resetDay = "下次重置：${resetDay} 天"
                } else if (resetDay == 0) {
                    // 0 = traffic resets at the coming midnight (last day of the cycle)
                    subInfo.resetDay = "下次重置：今天"
                } else if (dataObj.has("reset_day") && dataObj.get("reset_day").isJsonNull) {
                    // null = no reset for this plan (lifetime / never-reset / expired);
                    // clear the value cached from a previous plan or subscription.
                    subInfo.resetDay = ""
                }
                if (subInfo.hasData()) {
                    MmkvManager.saveSubscriptionInfo(subInfo)
                }
                return true
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "fetchUserInfo exception", e)
        }
        return false
    }

    /**
     * Imports the subscription URL and fetches server configurations.
     */
    fun importAndSyncSubscription(subscribeUrl: String): Boolean {
        return try {
            val subItem = SubscriptionItem().apply {
                remarks = AppConfig.DEFAULT_SUB_REMARKS
                url = subscribeUrl
                autoUpdate = true
            }
            val subId = MmkvManager.encodeSubscription(AppConfig.DEFAULT_SUB_REMARKS.lowercase(), subItem)

            val count = AngConfigManager.updateConfigViaSub(Pair(subId, subItem))
            Log.i(AppConfig.TAG, "Imported and synced $count servers from KNcloud subscription")

            // If no server selected, select the first server
            if (MmkvManager.getSelectServer().isNullOrEmpty()) {
                val servers = MmkvManager.decodeServerList()
                if (servers.isNotEmpty()) {
                    MmkvManager.setSelectServer(servers[0])
                }
            }
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import subscription", e)
            false
        }
    }

    /**
     * Re-queries dynamic domain from aws.kncloud.top and refreshes subscription URL, user metadata, and servers.
     */
    fun refreshDynamicSubscription(): Int {
        if (!MmkvManager.isUserLoggedIn()) return 0
        val token = MmkvManager.getUserToken() ?: return 0
        return try {
            val dynamicDomain = fetchDynamicDomain()
            val subResult = getSubscribeUrl(dynamicDomain, token)
            // Also try fetching supplementary user info if plan name is missing
            if (subResult.planName.isNullOrBlank()) {
                fetchUserInfo(dynamicDomain, token)
            }

            if (subResult.success && !subResult.subscribeUrl.isNullOrBlank()) {
                val subItem = SubscriptionItem().apply {
                    remarks = AppConfig.DEFAULT_SUB_REMARKS
                    url = subResult.subscribeUrl
                    autoUpdate = true
                }
                val subId = MmkvManager.encodeSubscription(AppConfig.DEFAULT_SUB_REMARKS.lowercase(), subItem)
                val count = AngConfigManager.updateConfigViaSub(Pair(subId, subItem))
                if (MmkvManager.getSelectServer().isNullOrEmpty()) {
                    val servers = MmkvManager.decodeServerList()
                    if (servers.isNotEmpty()) {
                        MmkvManager.setSelectServer(servers[0])
                    }
                }
                count
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to refresh dynamic subscription", e)
            0
        }
    }
}
