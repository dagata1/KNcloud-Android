package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object KNcloudAuthService {

    data class LoginResult(
        val success: Boolean,
        val token: String? = null,
        val message: String? = null,
        val domain: String = AppConfig.DEFAULT_WEB_DOMAIN
    )

    data class SubscribeResult(
        val success: Boolean,
        val subscribeUrl: String? = null,
        val message: String? = null
    )

    /**
     * Fetches the dynamic domain from the remote API.
     * Falls back to stored domain or default domain if fetch fails.
     */
    fun fetchDynamicDomain(): String {
        try {
            val url = URL(AppConfig.API_DOMAIN_QUERY_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "KNcloud/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = conn.responseCode
            val responseStream: InputStream? = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = responseStream?.use { it.bufferedReader().readText() }.orEmpty()
            conn.disconnect()

            if (responseCode in 200..299 && responseText.isNotEmpty()) {
                val json = JsonUtil.parseString(responseText)
                if (json != null && json.has("data")) {
                    val dataObj = json.getAsJsonObject("data")
                    if (dataObj.has("domain")) {
                        var domain = dataObj.get("domain").asString.trim()
                        if (domain.endsWith("/")) {
                            domain = domain.dropLast(1)
                        }
                        if (domain.isNotEmpty() && (domain.startsWith("http://") || domain.startsWith("https://"))) {
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
     * Performs login request to V2Board API.
     */
    fun login(email: String, password: String): LoginResult {
        val domain = fetchDynamicDomain()
        val loginEndpoint = "$domain/api/v1/passport/auth/login"

        try {
            val url = URL(loginEndpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "KNcloud/${BuildConfig.VERSION_NAME}")
            }

            val requestJson = JsonObject().apply {
                addProperty("email", email)
                addProperty("password", password)
            }

            conn.outputStream.use { os ->
                os.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = conn.responseCode
            val responseStream: InputStream? = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = responseStream?.use { it.bufferedReader().readText() }.orEmpty()
            conn.disconnect()

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
                        MmkvManager.saveUserLogin(email, token, domain)
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
     * Retrieves user subscription URL from V2Board API.
     */
    fun getSubscribeUrl(domain: String, token: String): SubscribeResult {
        val subscribeEndpoint = "$domain/api/v1/user/getSubscribe"

        try {
            val url = URL(subscribeEndpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Authorization", token)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "KNcloud/${BuildConfig.VERSION_NAME}")
            }

            val responseCode = conn.responseCode
            val responseStream: InputStream? = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = responseStream?.use { it.bufferedReader().readText() }.orEmpty()
            conn.disconnect()

            val json = JsonUtil.parseString(responseText)

            if (responseCode in 200..299) {
                if (json != null && json.has("data")) {
                    val dataObj = json.getAsJsonObject("data")
                    if (dataObj.has("subscribe_url")) {
                        val subUrl = dataObj.get("subscribe_url").asString
                        if (!subUrl.isNullOrBlank()) {
                            return SubscribeResult(success = true, subscribeUrl = subUrl)
                        }
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
     * Re-queries dynamic domain from aws.kncloud.top and refreshes subscription URL and servers.
     */
    fun refreshDynamicSubscription(): Int {
        if (!MmkvManager.isUserLoggedIn()) return 0
        val token = MmkvManager.getUserToken() ?: return 0
        return try {
            val dynamicDomain = fetchDynamicDomain()
            val subResult = getSubscribeUrl(dynamicDomain, token)
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
