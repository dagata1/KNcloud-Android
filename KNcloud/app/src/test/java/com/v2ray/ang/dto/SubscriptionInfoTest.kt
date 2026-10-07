package com.v2ray.ang.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SubscriptionInfoTest {

    private fun todayAt(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun expiryDay_notExpiredUntilExactTime() {
        val expireAt = todayAt(15, 0)
        val info = SubscriptionInfo()
        info.applyExpiry(expireAt / 1000L) // unix seconds, as the server sends it

        assertEquals(expireAt, info.expireAtMillis)
        assertFalse(info.isExpired(todayAt(9, 0)))
        assertFalse(info.isExpired(expireAt - 1))
        assertTrue(info.isExpired(expireAt))
        assertTrue(info.isExpired(todayAt(15, 1)))
    }

    @Test
    fun nullOrZeroExpiry_isNeverExpired() {
        val info = SubscriptionInfo()
        info.applyExpiry(null)
        assertEquals("套餐到期：长期有效", info.expireDate)
        assertEquals(0L, info.expireAtMillis)
        assertFalse(info.isExpired())

        info.applyExpiry(0L)
        assertFalse(info.isExpired())
    }

    @Test
    fun dateOnly_withoutExactTime_fallsBackToEndOfDay() {
        val info = SubscriptionInfo(expireDate = "套餐到期：2000-01-01")
        assertTrue(info.isExpired())
        val future = SubscriptionInfo(expireDate = "套餐到期：2999-01-01")
        assertFalse(future.isExpired())
    }

    @Test
    fun staleExactTime_isIgnoredWhenDateChanged() {
        val info = SubscriptionInfo()
        info.applyExpiry(todayAt(15, 0) / 1000L)
        // Plan renewed; an info entry updated only the date text.
        info.expireDate = "套餐到期：2999-01-01"
        assertFalse(info.isExpired(todayAt(16, 0)))
    }
}
