package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.IcocaLinkState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SDK state codes and the phase they fold into.
 *
 * 0.1.1 read `onConnectStateChanged`'s third argument (`parameter`) as if it were the state, so
 * every callback — including a successful connect — landed on [Phase.Advertising]. The receiver
 * therefore never opened its projection screen, and the session the phone had just established was
 * thrown away on the car side. These tests are the regression fence around that mistake.
 */
class IcocaLinkStateTest {

    @Test
    fun stateCodesMatchTheSdkCallbacks() {
        // Values read out of the SDK dex: UCarAdapter$4/$5/$6 and UCarAdapter#notifyConnectFailed.
        assertEquals(1, IcocaLinkState.DISCONNECTED)
        assertEquals(2, IcocaLinkState.CONNECTED)
        assertEquals(3, IcocaLinkState.FAILED)
        assertEquals(4, IcocaLinkState.WFD_READY)
        assertEquals(5, IcocaLinkState.WFD_STOPPED)
        assertEquals(6, IcocaLinkState.PHONE_DESKTOP)
        assertEquals(7, IcocaLinkState.PHONE_FOREGROUND)
    }

    @Test
    fun connectingSucceedsThroughWfdReady() {
        assertEquals(Phase.Connected, IcocaLinkState.phaseFor(IcocaLinkState.CONNECTED, Phase.Pairing))
        assertEquals(Phase.Connected, IcocaLinkState.phaseFor(IcocaLinkState.WFD_READY, Phase.Pairing))
        // The channel becoming ready while we already show Connected must stay Connected.
        assertEquals(Phase.Connected, IcocaLinkState.phaseFor(IcocaLinkState.WFD_READY, Phase.Connected))
    }

    @Test
    fun failureAndDisconnectReturnToAdvertisingOrError() {
        assertEquals(Phase.Error, IcocaLinkState.phaseFor(IcocaLinkState.FAILED, Phase.Pairing))
        assertEquals(Phase.Advertising, IcocaLinkState.phaseFor(IcocaLinkState.DISCONNECTED, Phase.Connected))
    }

    @Test
    fun stoppedChannelNeverInventsOrDropsASession() {
        // Still connected: keep showing the session (the phone may reopen the channel).
        assertEquals(Phase.Connected, IcocaLinkState.phaseFor(IcocaLinkState.WFD_STOPPED, Phase.Connected))
        // No session yet: do not fabricate one.
        assertEquals(Phase.Advertising, IcocaLinkState.phaseFor(IcocaLinkState.WFD_STOPPED, Phase.Advertising))
        assertEquals(Phase.Pairing, IcocaLinkState.phaseFor(IcocaLinkState.WFD_STOPPED, Phase.Pairing))
    }

    @Test
    fun phoneSideUiChangesLeaveTheCarSessionAlone() {
        for (phase in listOf(Phase.Advertising, Phase.Pairing, Phase.Connected, Phase.Error)) {
            assertEquals(phase, IcocaLinkState.phaseFor(IcocaLinkState.PHONE_DESKTOP, phase))
            assertEquals(phase, IcocaLinkState.phaseFor(IcocaLinkState.PHONE_FOREGROUND, phase))
        }
    }

    @Test
    fun unknownCodesAreInert() {
        assertEquals(Phase.Connected, IcocaLinkState.phaseFor(0, Phase.Connected))
        assertEquals(Phase.Pairing, IcocaLinkState.phaseFor(99, Phase.Pairing))
        assertEquals(Phase.Pairing, IcocaLinkState.phaseFor(-1, Phase.Pairing))
    }

    /**
     * The full callback sequence a phone produces on a successful connect, including the transient
     * `-1` parameters that 0.1.1 mistook for a state. The phase must reach [Phase.Connected] and
     * stay there until the SDK actually disconnects.
     */
    @Test
    fun successfulSessionSurvivesTheParameterNoise() {
        var phase = Phase.Advertising
        // onPinCode
        phase = Phase.Pairing
        val callbacks = listOf(
            IcocaLinkState.DISCONNECTED to -1, // initial DISCONNECTED the SDK emits at start
            IcocaLinkState.CONNECTED to -1,    // trySetDeviceConnected
            IcocaLinkState.WFD_READY to -1,    // onWfdSinkReady
            IcocaLinkState.PHONE_DESKTOP to -1,
            IcocaLinkState.PHONE_FOREGROUND to -1,
            IcocaLinkState.WFD_STOPPED to -1,
        )
        // The first DISCONNECTED legitimately clears the pairing attempt; start the walk after it.
        phase = IcocaLinkState.phaseFor(callbacks.first().first, phase)
        assertEquals(Phase.Advertising, phase)
        phase = Phase.Pairing
        for ((state, parameter) in callbacks.drop(1)) {
            phase = IcocaLinkState.phaseFor(state, phase)
            assertEquals("state=$state parameter=$parameter must keep the session", Phase.Connected, phase)
        }
        // ...until the SDK says it is over.
        assertEquals(Phase.Advertising, IcocaLinkState.phaseFor(IcocaLinkState.DISCONNECTED, phase))
    }

    @Test
    fun watchdogStaysOutOfAnActiveSession() {
        assertTrue(IcocaLinkState.isSessionActive(IcocaLinkState.CONNECTED))
        assertTrue(IcocaLinkState.isSessionActive(IcocaLinkState.WFD_READY))
        assertTrue(IcocaLinkState.isSessionActive(IcocaLinkState.WFD_STOPPED))
        assertFalse(IcocaLinkState.isSessionActive(IcocaLinkState.DISCONNECTED))
        assertFalse(IcocaLinkState.isSessionActive(IcocaLinkState.FAILED))
        assertFalse(IcocaLinkState.isSessionActive(IcocaLinkState.PHONE_DESKTOP))
    }

    @Test
    fun nativeStackRequiresTheSinkAndTheUsbHalf() {
        assertEquals(emptyList<String>(), NativeStack.missingFrom(NativeStack.requiredLibraries))
        assertEquals(
            listOf("libovmsink.so"),
            NativeStack.missingFrom(listOf("libusbio.so", "libusb1.0.so")),
        )
        // What a 32-bit / x86 install actually looks like: neither arm64-only library is present.
        assertEquals(
            NativeStack.requiredLibraries,
            NativeStack.missingFrom(listOf("libusb1.0.so", "libusbhelper.so", "libhur_soft_hevc.so")),
        )
    }
}
