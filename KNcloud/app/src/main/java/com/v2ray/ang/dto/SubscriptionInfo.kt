package com.v2ray.ang.dto

data class SubscriptionInfo(
    var subName: String = "",       // e.g. "KNcloud 专属套餐" or extracted from "订阅名称：已用/总量"
    var traffic: String = "",       // e.g. "23041.3G/99999G" or "流量信息：23041.3G/99999G"
    var resetDay: String = "",      // e.g. "下次重置：15 天"
    var expireDate: String = ""     // e.g. "套餐到期：长期有效" or "套餐到期：2026-12-31"
) {
    fun hasData(): Boolean = subName.isNotBlank() || traffic.isNotBlank() || resetDay.isNotBlank() || expireDate.isNotBlank()

    /**
     * Returns a cleanly formatted string for used/total traffic, e.g. "22.5 TB / 97.6 TB" or "23.0 GB / 100.0 GB"
     */
    fun getFormattedTraffic(): String {
        if (traffic.isBlank()) return ""
        try {
            // Strip any prefix before colon if present
            val raw = if (traffic.contains("：")) {
                traffic.substringAfter("：")
            } else if (traffic.contains(":")) {
                traffic.substringAfter(":")
            } else {
                traffic
            }.trim()

            val parts = raw.split("/")
            if (parts.size == 2) {
                val usedBytes = parseToBytes(parts[0].trim())
                val totalBytes = parseToBytes(parts[1].trim())
                if (usedBytes >= 0 && totalBytes > 0) {
                    return "${formatBytes(usedBytes)} / ${formatBytes(totalBytes)}"
                }
            }
            return raw
        } catch (_: Exception) {
            return traffic
        }
    }

    fun getCleanExpireDate(): String {
        return expireDate.replace("套餐到期：", "")
            .replace("套餐到期:", "")
            .replace("到期时间：", "")
            .replace("到期时间:", "")
            .replace("⏳", "")
            .trim()
    }

    fun getCleanResetDay(): String {
        return resetDay.replace("下次重置：", "")
            .replace("下次重置:", "")
            .replace("下次充值：", "")
            .replace("下次充值:", "")
            .trim()
    }

    /**
     * Checks whether the subscription is expired.
     */
    fun isExpired(): Boolean {
        val clean = getCleanExpireDate().trim()
        if (clean.isBlank()) return false
        if (clean.contains("长期") || clean.contains("无限") || clean.contains("永久") || clean.contains("不限")) return false
        if (clean.contains("过期") || clean.contains("已到期")) return true

        // Try timestamp (seconds or milliseconds)
        val timestamp = clean.toLongOrNull()
        if (timestamp != null) {
            val millis = if (timestamp < 10000000000L) timestamp * 1000L else timestamp
            return millis < System.currentTimeMillis()
        }

        // Try standard date formats
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd",
            "yyyy.MM.dd"
        )
        for (pattern in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
                sdf.isLenient = false
                val date = sdf.parse(clean)
                if (date != null) {
                    val expiryMillis = if (!pattern.contains("HH")) {
                        date.time + 24 * 3600 * 1000L - 1
                    } else {
                        date.time
                    }
                    return expiryMillis < System.currentTimeMillis()
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Calculates the usage percentage between 0 and 100, or -1 if unable to parse.
     */
    fun calculateUsagePercent(): Int {
        if (traffic.isBlank()) return -1
        try {
            val raw = if (traffic.contains("：")) {
                traffic.substringAfter("：")
            } else if (traffic.contains(":")) {
                traffic.substringAfter(":")
            } else {
                traffic
            }.trim()

            val parts = raw.split("/")
            if (parts.size == 2) {
                val usedBytes = parseToBytes(parts[0].trim())
                val totalBytes = parseToBytes(parts[1].trim())
                if (totalBytes > 0 && usedBytes >= 0) {
                    return ((usedBytes / totalBytes) * 100).toInt().coerceIn(0, 100)
                }
            }
        } catch (_: Exception) {}
        return -1
    }

    private fun parseToBytes(str: String): Double {
        val s = str.trim().uppercase()
        val numRegex = Regex("""([0-9]+(?:\.[0-9]+)?)""")
        val match = numRegex.find(s) ?: return -1.0
        val num = match.value.toDoubleOrNull() ?: return -1.0

        return when {
            s.contains("TB") or s.contains("T") -> num * 1024.0 * 1024.0 * 1024.0 * 1024.0
            s.contains("GB") or s.contains("G") -> num * 1024.0 * 1024.0 * 1024.0
            s.contains("MB") or s.contains("M") -> num * 1024.0 * 1024.0
            s.contains("KB") or s.contains("K") -> num * 1024.0
            s.contains("B") -> num
            else -> num * 1024.0 * 1024.0 * 1024.0
        }
    }

    private fun formatBytes(bytes: Double): String {
        val tb = 1024.0 * 1024.0 * 1024.0 * 1024.0
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return when {
            bytes >= tb -> String.format("%.2f TB", bytes / tb)
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            else -> String.format("%.0f KB", bytes / 1024.0)
        }
    }
}
