package com.v2ray.ang.dto

data class SubscriptionInfo(
    var traffic: String = "",       // e.g. "流量信息：23041.3G/99999G"
    var resetDay: String = "",      // e.g. "下次重置：15 天"
    var expireDate: String = ""     // e.g. "套餐到期：长期有效" or "套餐到期：2026-12-31"
) {
    fun hasData(): Boolean = traffic.isNotBlank() || resetDay.isNotBlank() || expireDate.isNotBlank()

    /**
     * Returns a cleanly formatted string for used/total traffic, e.g. "22.5 TB / 97.6 TB" or "23.0 GB / 100.0 GB"
     */
    fun getFormattedTraffic(): String {
        if (traffic.isBlank()) return ""
        try {
            val clean = traffic.replace("流量信息：", "")
                .replace("流量信息:", "")
                .replace("已用流量：", "")
                .replace("已用流量:", "")
                .trim()
            val parts = clean.split("/")
            if (parts.size == 2) {
                val usedBytes = parseToBytes(parts[0].trim())
                val totalBytes = parseToBytes(parts[1].trim())
                if (usedBytes >= 0 && totalBytes > 0) {
                    return "${formatBytes(usedBytes)} / ${formatBytes(totalBytes)}"
                }
            }
            return clean
        } catch (_: Exception) {
            return traffic
        }
    }

    fun getCleanExpireDate(): String {
        return expireDate.replace("套餐到期：", "")
            .replace("套餐到期:", "")
            .replace("到期时间：", "")
            .replace("到期时间:", "")
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
     * Calculates the usage percentage between 0 and 100, or -1 if unable to parse.
     */
    fun calculateUsagePercent(): Int {
        if (traffic.isBlank()) return -1
        try {
            val clean = traffic.replace("流量信息：", "")
                .replace("流量信息:", "")
                .replace("已用流量：", "")
                .replace("已用流量:", "")
                .trim()
            val parts = clean.split("/")
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
