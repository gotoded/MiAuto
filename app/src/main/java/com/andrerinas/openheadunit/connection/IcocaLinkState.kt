package com.andrerinas.openheadunit.connection

/**
 * The ICCOA link's state machine, isolated from the SDK so it can be unit-tested.
 *
 * The numbers below are `ICarConnectListener#onConnectStateChanged`'s **second** argument. They were
 * read out of the SDK itself: every listener implementation inside `UCarAdapter` calls
 * `notifyConnectStateChanged(deviceID, state, parameter)`, and
 * `UCarAdapter#lambda$notifyConnectStateChanged$13` passes those three arguments straight through
 * to the app's callback.
 *
 * | state | emitted by                                          | meaning                               |
 * |-------|-----------------------------------------------------|---------------------------------------|
 * | 1     | `notifyDisconnected`                                 | session over                          |
 * | 2     | `trySetDeviceConnected`                              | device connected, video not bound yet |
 * | 3     | `notifyConnectFailed` / `onUserInterventionNeeded`   | failed; `parameter` is the error code |
 * | 4     | `onWfdSinkReady`                                     | projection channel ready              |
 * | 5     | `onWfdSinkStopped`                                   | projection channel stopped            |
 * | 6     | `onGoToDesktop`                                      | phone went to its home screen         |
 * | 7     | `onBackToForeground`                                 | phone came back to the foreground     |
 *
 * The third argument is `parameter`, **not** a state. 0.1.1 treated it as one, so every callback —
 * successes included — collapsed onto [Phase.Advertising] and the projection screen never opened.
 *
 * These literals are intentionally duplicated in `com.miauto.app.IccoaManager` (which owns the SDK
 * callbacks) instead of importing them: that class pulls in `com.ucar.vehiclesdk.*`, which only
 * exists as a build-time stub and a runtime-injected dex. Keeping this file dependency-free is what
 * lets the mapping be tested on the JVM. `IcocaLinkStateTest` pins both the numbers and the
 * behaviour, so drift shows up as a failing test rather than as a car that will not connect.
 */
object IcocaLinkState {

    /** `notifyDisconnected` */
    const val DISCONNECTED = 1

    /** `trySetDeviceConnected` — connected at the protocol level, nothing on screen yet. */
    const val CONNECTED = 2

    /** `notifyConnectFailed` / `onUserInterventionNeeded` — the parameter is an error code. */
    const val FAILED = 3

    /** `onWfdSinkReady` — the projection channel is up, this is the point video can flow. */
    const val WFD_READY = 4

    /** `onWfdSinkStopped` — the channel went down; the phone may still be attached. */
    const val WFD_STOPPED = 5

    /** `onGoToDesktop` */
    const val PHONE_DESKTOP = 6

    /** `onBackToForeground` */
    const val PHONE_FOREGROUND = 7

    /** Coarse connection phase shown to the driver. */
    enum class Phase {
        /** Not started, or stopped by the user. */
        Idle,

        /** BLE advertising; waiting for a phone to discover us. */
        Advertising,

        /** Phone found us and is pairing / negotiating. */
        Pairing,

        /** Session established; the phone may now push video. */
        Connected,

        /** The SDK reported a failure; [IcocaBridge.State.errorCode] carries its code. */
        Error,

        /** This head unit cannot run the link at all (no native libraries for its ABI). */
        Unsupported,
    }

    /**
     * Folds one SDK state into the next phase.
     *
     * @param state the SDK's state code, not its accompanying parameter
     * @param current the phase we are in, for states that say nothing about the session
     */
    fun phaseFor(state: Int, current: Phase): Phase = when (state) {
        DISCONNECTED -> Phase.Advertising
        CONNECTED -> Phase.Connected
        FAILED -> Phase.Error
        WFD_READY -> Phase.Connected
        // The channel stopped but the phone may still be attached: keep an existing session, and
        // never invent one that does not exist.
        WFD_STOPPED -> if (current == Phase.Connected) Phase.Connected else current
        // Phone-side UI changes say nothing about the car session.
        PHONE_DESKTOP, PHONE_FOREGROUND -> current
        else -> current
    }

    /** True while a phone session exists, i.e. the pairing watchdog must stay out of the way. */
    fun isSessionActive(state: Int): Boolean = when (state) {
        CONNECTED, WFD_READY -> true
        // A stopped channel is still a live session unless the SDK says otherwise.
        WFD_STOPPED -> true
        else -> false
    }
}
