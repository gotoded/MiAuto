package com.miauto.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the re-advertise backoff.
 *
 * The failure this prevents is specific: after an abnormal disconnect the phone's Wi-Fi P2P group
 * is still tearing down, so re-advertising on a fixed 1.2 s only collects 10003/10006/10008 in a
 * row and pushes the shared radio further out of shape. The delays below are the contract — if
 * they drift, reconnect times drift with them.
 */
class ReconnectBackoffTest {

    @Test
    fun `first attempt waits the historical 1_2 seconds`() {
        assertEquals(1_200L, ReconnectBackoff.nextDelayMs(true, 1))
        assertEquals(1_200L, ReconnectBackoff.nextDelayMs(true, 0))
    }

    @Test
    fun `each consecutive wireless failure doubles the wait`() {
        assertEquals(1_200L, ReconnectBackoff.nextDelayMs(true, 1))
        assertEquals(2_400L, ReconnectBackoff.nextDelayMs(true, 2))
        assertEquals(4_800L, ReconnectBackoff.nextDelayMs(true, 3))
    }

    @Test
    fun `the wait is capped so a connection always comes back`() {
        assertEquals(8_000L, ReconnectBackoff.nextDelayMs(true, 4))
        assertEquals(8_000L, ReconnectBackoff.nextDelayMs(true, 50))
    }

    @Test
    fun `non-wireless failures keep the fixed interval`() {
        // Parameter errors and cast-start failures have nothing to do with radio recycling;
        // backing off on them would only make the car feel less responsive.
        assertEquals(1_200L, ReconnectBackoff.nextDelayMs(false, 1))
        assertEquals(1_200L, ReconnectBackoff.nextDelayMs(false, 9))
    }

    @Test
    fun `only the wireless bring-up family is classified as such`() {
        assertTrue(ReconnectBackoff.isWirelessBringupFailure(10003))
        assertTrue(ReconnectBackoff.isWirelessBringupFailure(10006))
        assertTrue(ReconnectBackoff.isWirelessBringupFailure(10008))
        assertFalse(ReconnectBackoff.isWirelessBringupFailure(10007))
        assertFalse(ReconnectBackoff.isWirelessBringupFailure(-1))
        assertFalse(ReconnectBackoff.isWirelessBringupFailure(0))
    }

    @Test
    fun `the streak advances only on wireless failures`() {
        assertEquals(1, ReconnectBackoff.streakAfterFailure(0, 10003))
        assertEquals(4, ReconnectBackoff.streakAfterFailure(3, 10008))
        // A parameter error resets it: the radio is not the problem here.
        assertEquals(0, ReconnectBackoff.streakAfterFailure(3, 10007))
    }

    @Test
    fun `reset clears the streak`() {
        assertEquals(0, ReconnectBackoff.resetStreak())
    }
}
