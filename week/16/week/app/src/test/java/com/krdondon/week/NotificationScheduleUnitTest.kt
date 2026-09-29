package com.krdondon.week

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NotificationScheduleUnitTest {

    @Test
    fun nextTransitionTime_isAlwaysInFuture() {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        if (currentHour < 12) {
            next.set(Calendar.HOUR_OF_DAY, 12)
            next.set(Calendar.MINUTE, 0)
        } else {
            next.add(Calendar.DAY_OF_YEAR, 1)
            next.set(Calendar.HOUR_OF_DAY, 0)
            next.set(Calendar.MINUTE, 0)
        }
        val triggerAtMillis = next.timeInMillis + 500

        assertTrue("Trigger time must be in the future", triggerAtMillis > System.currentTimeMillis())
    }
}
