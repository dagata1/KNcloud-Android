package com.v2ray.ang.handler

import android.content.Context
import android.os.Build
import com.v2ray.ang.BuildConfig
import top.kncloud.com.KNApplication
import java.util.UUID

/**
 * Device registration for the panel's device management (设备管理).
 *
 * Every panel account API call (login, getSubscribe, user/info) and the account subscription
 * download carry:
 *  - X-KN-Device-Id: random UUID v4 generated once and kept in SharedPreferences
 *    ("kncloud_device"); never derived from hardware serials or ANDROID_ID
 *  - X-KN-Device-Name: Build.MODEL (e.g. "Pixel 8"); the user can rename it in the panel
 *  - X-KN-Platform: android
 *  - X-KN-Client-Version: app version name
 *
 * Third-party subscriptions the user added by hand do not get these headers.
 */
object DeviceIdentity {
    const val HDR_DEVICE_ID = "X-KN-Device-Id"
    const val HDR_DEVICE_NAME = "X-KN-Device-Name"
    const val HDR_PLATFORM = "X-KN-Platform"
    const val HDR_CLIENT_VERSION = "X-KN-Client-Version"

    private const val PREFS = "kncloud_device"
    private const val KEY_DEVICE_ID = "device_id"

    /** Panel-issued per-device subscription URL (device_subscribe_url), kept in the same prefs. */
    private const val KEY_DEVICE_SUB_URL = "device_subscribe_url"

    @Volatile
    private var cachedId: String? = null

    private fun prefs() = KNApplication.application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun deviceId(): String {
        cachedId?.let { return it }
        val p = prefs()
        var id = p.getString(KEY_DEVICE_ID, null)
        if (id == null || !isUuidV4(id)) {
            id = UUID.randomUUID().toString()
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        cachedId = id
        return id
    }

    fun isUuidV4(s: String): Boolean =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(s.lowercase())

    /** Build.MODEL, restricted to printable ASCII (HttpURLConnection rejects other header bytes). */
    fun deviceName(): String = asciiHeaderValue(Build.MODEL.orEmpty()).ifBlank { "Android" }

    fun asciiHeaderValue(s: String): String = s.filter { it in ' '..'~' }.trim()

    fun headers(): Map<String, String> = linkedMapOf(
        HDR_DEVICE_ID to deviceId(),
        HDR_DEVICE_NAME to deviceName(),
        HDR_PLATFORM to "android",
        HDR_CLIENT_VERSION to asciiHeaderValue(BuildConfig.VERSION_NAME)
    )

    fun deviceSubUrl(): String? = prefs().getString(KEY_DEVICE_SUB_URL, null)?.takeIf { it.isNotBlank() }

    fun setDeviceSubUrl(url: String?) {
        prefs().edit().apply {
            if (url.isNullOrBlank()) remove(KEY_DEVICE_SUB_URL) else putString(KEY_DEVICE_SUB_URL, url)
        }.apply()
    }

    /** Status codes meaning the device subscription token is no longer valid (device removed). */
    fun isDeviceSubInvalidStatus(code: Int): Boolean = code == 401 || code == 403 || code == 404 || code == 410
}
