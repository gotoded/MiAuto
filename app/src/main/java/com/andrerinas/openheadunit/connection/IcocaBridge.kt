package com.andrerinas.openheadunit.connection

import android.content.Context
import android.os.Build
import android.view.Surface
import com.andrerinas.openheadunit.connection.IcocaLinkState.Phase
import com.andrerinas.openheadunit.utils.AppLog
import com.miauto.app.IccoaManager
import com.ucar.vehiclesdk.UCarCommon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges the MiAuto ICCOA CarLink SDK (a plain Java facade, [IccoaManager]) into DiAuto's
 * reactive architecture.
 *
 * DiAuto drives every consumer from a single `StateFlow` (see `CommManager.connectionState`);
 * the ICCOA SDK instead pushes callbacks on worker threads. This class is the adapter between
 * the two: it implements [IccoaManager.Listener], folds the raw callbacks into one observable
 * [state], and exposes the navigation stream the BYD cluster / HUD outputs consume.
 *
 * MiAuto is ICCOA-only — there is no Android Auto path at runtime — so this bridge (not
 * `CommManager`) is the source of truth for both the home screen and the projection activity.
 *
 * The state → phase mapping lives in [IcocaLinkState] so it can be unit-tested: getting it wrong is
 * invisible until a real phone fails to connect, which is exactly how 0.1.1 shipped a receiver that
 * never reached [Phase.Connected].
 */
class IcocaBridge(context: Context) : IccoaManager.Listener {

    data class State(
        val phase: Phase = Phase.Idle,
        /** Pairing code to show on screen, if the SDK handed one out. */
        val pinCode: String? = null,
        val carDeviceName: String? = null,
        /** Last raw SDK log line, kept for the diagnostics screen. */
        val lastLog: String? = null,
        /** SDK error code for [Phase.Error] (`onConnectStateChanged` state 3). */
        val errorCode: Int? = null,
        /** Machine-readable reason for [Phase.Unsupported]; the UI shows the ABI. */
        val unsupportedDetail: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigation = MutableStateFlow<UCarCommon.NavigationInfo?>(null)

    /** Latest navigation snapshot from the phone (ICCOA `NotifyNavigationInfo`). */
    val navigation: StateFlow<UCarCommon.NavigationInfo?> = _navigation.asStateFlow()

    private val appContext: Context = context.applicationContext

    /** The underlying SDK facade. Public so projection / navigation code can reach raw APIs. */
    val manager = IccoaManager(appContext, this)

    @Volatile
    private var started = false

    /** Set once when the native stack turns out to be missing, so we never touch the SDK again. */
    @Volatile
    private var unsupported: String? = null

    /**
     * Starts (or restarts) the ICCOA link: SDK init once, then BLE advertising.
     *
     * Refuses to touch the SDK at all when its native libraries are absent for this ABI. The SDK's
     * `UsbNative.<clinit>` calls `System.loadLibrary("usbio")` unconditionally during `init()`, so on
     * a 32-bit or x86 head unit the process dies with `UnsatisfiedLinkError` before any of our code
     * can catch it — a crash with no explanation, which is what most of the "opens and closes
     * immediately" reports turned out to be.
     */
    fun start() {
        if (unsupported != null) return
        val missing = NativeStack.missingLibraries(appContext)
        if (missing.isNotEmpty()) {
            val detail = "missing ${missing.joinToString()} · abi=${Build.SUPPORTED_ABIS.joinToString("/")}"
            unsupported = detail
            AppLog.w("ICCOA: 本机缺少协议栈原生库，拒绝初始化 SDK —— $detail")
            _state.update {
                it.copy(phase = Phase.Unsupported, unsupportedDetail = detail, pinCode = null)
            }
            return
        }
        if (started) {
            AppLog.i("ICCOA: start() ignored — already started; restarting advertise")
            manager.start() // init-once inside; falls through to reAdvertise()
            return
        }
        started = true
        AppLog.i("ICCOA: start()")
        updatePhase(Phase.Advertising)
        manager.start()
    }

    /** Stops advertising and tears the session down, keeping the SDK initialised for a restart. */
    fun stop() {
        if (!started) return
        started = false
        AppLog.i("ICCOA: stop()")
        manager.stop()
        _state.update { it.copy(phase = Phase.Idle, pinCode = null, errorCode = null) }
    }

    val isStarted: Boolean get() = started

    /**
     * Drops the current session but keeps the link alive and advertising, so the phone can come
     * straight back without the user restarting the app. Backs the projection screen's disconnect
     * affordance, which must not behave like a power-off.
     */
    fun disconnectSession() {
        AppLog.i("ICCOA: disconnectSession()")
        manager.disconnectSession()
        _state.update { it.copy(phase = Phase.Advertising, pinCode = null) }
    }

    /**
     * User-triggered "refresh pairing code": re-broadcasts immediately so the phone re-prompts,
     * instead of waiting for the 30-second watchdog. The stale code is cleared at once so the
     * screen never shows a code that is already dead.
     */
    fun refreshPairing() {
        AppLog.i("ICCOA: refreshPairing()")
        manager.refreshPairing()
        _state.update { it.copy(pinCode = null) }
    }

    /** Native screen size handed to the SDK; drives the negotiated stream resolution. */
    fun setScreenSize(width: Int, height: Int) = manager.setScreenSize(width, height)

    /**
     * Hands the projection [surface] to the SDK. [IccoaManager] decides whether this is a new
     * surface (re-bind, pausing the old one first) or a repeated callback for the same one.
     */
    fun setSurface(surface: Surface) = manager.setSurface(surface)

    /** Surface gone: let the SDK stop writing before the BufferQueue is recycled. */
    fun onSurfaceDestroyed() = manager.onSurfaceDestroyed()

    /**
     * Forwards one touch event to the phone over ICCOA's UIBC channel.
     * Coordinates must already be in the SDK's video-display space.
     */
    fun sendTouch(action: Int, trackIds: IntArray, xs: IntArray, ys: IntArray): Boolean =
        manager.sendTouchEvent(action, trackIds.size, trackIds, xs, ys)

    /** Video-display space the SDK expects touch coordinates in. */
    val videoWidth: Int get() = manager.videoWidth

    val videoHeight: Int get() = manager.videoHeight

    /** Last PIN the SDK issued, or null. Useful when the projection activity opens late. */
    fun lastPinCode(): String? = manager.lastPinCode

    // ------------------------------------------------------------------
    // IccoaManager.Listener — called on SDK worker threads, hence the StateFlow.
    // ------------------------------------------------------------------

    override fun onLog(msg: String?) {
        AppLog.i("ICCOA: $msg")
        _state.update { it.copy(lastLog = msg) }
    }

    override fun onConnectionStateChanged(state: Int, parameter: Int) {
        val phase = IcocaLinkState.phaseFor(state, _state.value.phase)
        AppLog.i("ICCOA: connection state=$state parameter=$parameter -> $phase")
        _state.update { current ->
            current.copy(
                phase = phase,
                errorCode = if (phase == Phase.Error) parameter else null,
                // A code only means something while the SDK is still pairing.
                pinCode = if (phase == Phase.Pairing) current.pinCode else null,
            )
        }
    }

    override fun onPinCode(pinCode: String?, carDeviceName: String?) {
        AppLog.i("ICCOA: pinCode=$pinCode carDeviceName=$carDeviceName")
        _state.update {
            it.copy(phase = Phase.Pairing, pinCode = pinCode, carDeviceName = carDeviceName, errorCode = null)
        }
    }

    override fun onCastStarted() {
        AppLog.i("ICCOA: cast started")
        updatePhase(Phase.Connected)
    }

    override fun onNavigation(info: UCarCommon.NavigationInfo?) {
        _navigation.value = info
    }

    /**
     * Moves the phase and keeps the stored PIN honest: a code only means something while the SDK is
     * still pairing.
     *
     * Leaving it up after the session opens would show a code that no longer matches anything, and
     * leaving it up after a drop is worse — the phone is about to be issued a *different* one, so
     * the screen would invite the user to type a code that is already dead. Clearing on every
     * non-pairing phase is what makes the code "refresh itself" across reconnects.
     */
    private fun updatePhase(phase: Phase) {
        _state.update { current ->
            current.copy(
                phase = phase,
                pinCode = if (phase == Phase.Pairing) current.pinCode else null,
            )
        }
    }
}
