package com.houseoftech.voicelock

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeDeciderTest {
    @Test
    fun `a secure keyguard means voice can only lock`() {
        assertEquals(LockMode.SECURE_KEYGUARD, ModeDecider.decide(isDeviceSecure = true))
    }

    @Test
    fun `no keyguard means our overlay is the lock screen`() {
        assertEquals(LockMode.NO_KEYGUARD, ModeDecider.decide(isDeviceSecure = false))
    }
}
