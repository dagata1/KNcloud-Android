package com.v2ray.ang.dto

data class SubscriptionInfo(
    var traffic: String = "",       // e.g. "流量信息：12.50 GB/100.00 GB"
    var resetDay: String = "",      // e.g. "下次重置：15 天"
    var expireDate: String = ""     // e.g. "套餐到期：2026-12-31"
) {
    fun hasData(): Boolean = traffic.isNotBlank() || resetDay.isNotBlank() || expireDate.isNotBlank()

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
            s.contains("TB") -> num * 1024.0 * 1024.0 * 1024.0 * 1024.0
            s.contains("GB") -> num * 1024.0 * 1024.0 * 1024.0
            s.contains("MB") -> num * 1024.0 * 1024.0
            s.contains("KB") -> num * 1024.0
            s.contains("B") -> num
            else -> num * 1024.0 * 1024.0 * 1024.0
        }
    }
}
