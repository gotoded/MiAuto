package com.andrerinas.openheadunit.icoca

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.IcocaBridge
import com.andrerinas.openheadunit.connection.IcocaLinkState
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.launch

/**
 * Full-screen host for the ICCOA CarLink phone stream.
 *
 * The ICCOA SDK does not decode video itself — it hands the H.264 stream to a Surface you supply
 * via `UCarAdapter.startCast(surface, w, h)`. This activity exists purely to own that surface,
 * forward touches back to the phone, and keep the session alive while the phone is projecting.
 *
 * It deliberately does *not* reuse `AapProjectionActivity`. That activity is wired end-to-end into
 * the Android Auto session (video-focus negotiation, `CommManager` transport state, AAP keyframes)
 * and MiAuto never opens an AA session, so every one of its branches would be dead code guarded by
 * a status that can never occur.
 */
class IcocaProjectionActivity : AppCompatActivity() {

    private lateinit var bridge: IcocaBridge
    private var surfaceView: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A projection session can run for a long time; never let the panel sleep mid-drive.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_icoca_projection)
        hideSystemUi()

        bridge = App.provide(this).icocaBridge
        val view = findViewById<SurfaceView>(R.id.icoca_surface)
        surfaceView = view
        // The surface is only an output; every touch on it belongs to the phone. Keeping the view
        // itself non-focusable leaves the whole panel to onTouchEvent below.
        view.isClickable = false
        view.isFocusable = false

        view.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    AppLog.i("ICCOA projection: surfaceCreated")
                    bridge.setSurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    AppLog.i("ICCOA projection: surfaceChanged ${width}x$height")
                    // Re-bind on every change: a size change tears the surface down underneath the
                    // SDK, and the stale reference would otherwise keep receiving frames.
                    bridge.setSurface(holder.surface)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // Tell the SDK to stop writing *before* the BufferQueue is recycled. Without
                    // this it keeps decoding into an abandoned queue, gets a fatal -19, and the
                    // session never shows a picture again (audio keeps playing).
                    AppLog.i("ICCOA projection: surfaceDestroyed")
                    bridge.onSurfaceDestroyed()
                }
            }
        )

        findViewById<View>(R.id.icoca_disconnect).setOnClickListener {
            AppLog.i("ICCOA projection: disconnect tapped")
            // Drop the session but keep advertising: the standby screen goes straight back to
            // "waiting for phone" and a fresh pairing code, instead of a dead link that needs an
            // app restart to recover.
            bridge.disconnectSession()
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bridge.state.collect { state ->
                    findViewById<View>(R.id.icoca_hint)?.visibility =
                        if (state.phase == IcocaLinkState.Phase.Connected) View.GONE else View.VISIBLE
                }
            }
        }
    }

    /**
     * Touch back-channel (ICCOA UIBC).
     *
     * The SDK's own `UCarSurfaceView.onTouchEvent` is the reference: the *raw* action
     * (`getAction()`, pointer index included), one entry per pointer, and coordinates that it later
     * converts from the declared video-display space back into the phone's source space. So we send
     * `getAction()`, `pointerId`, and `x/y` scaled into `videoWidth × videoHeight`.
     *
     * Without this the car screen is a picture: the phone is reachable in the mirror but nothing on
     * the head unit can be tapped, which is the second half of every "connected but useless" report.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!forwardTouch(event)) return super.onTouchEvent(event)
        return true
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        val view = surfaceView ?: return false
        val viewW = view.width
        val viewH = view.height
        if (viewW <= 0 || viewH <= 0) return false
        val targetW = bridge.videoWidth
        val targetH = bridge.videoHeight
        if (targetW <= 0 || targetH <= 0) return false

        val count = event.pointerCount
        if (count <= 0) return false
        val ids = IntArray(count)
        val xs = IntArray(count)
        val ys = IntArray(count)
        val sx = targetW.toFloat() / viewW
        val sy = targetH.toFloat() / viewH
        for (i in 0 until count) {
            ids[i] = event.getPointerId(i)
            // MotionEvent coordinates are relative to the activity's window; the surface view is
            // translated inside it, so subtract its origin before scaling.
            xs[i] = ((event.getX(i) - view.left) * sx).toInt().coerceIn(0, targetW - 1)
            ys[i] = ((event.getY(i) - view.top) * sy).toInt().coerceIn(0, targetH - 1)
        }
        val sent = bridge.sendTouch(event.action, ids, xs, ys)
        if (!sent && event.actionMasked == MotionEvent.ACTION_DOWN) {
            AppLog.w("ICCOA projection: touch channel not ready (encryption/negotiation still settling)")
        }
        return sent
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    override fun onStop() {
        isForeground = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    companion object {
        /** Read by MainActivity to avoid stacking a second projection instance. */
        @Volatile
        var isForeground: Boolean = false
            private set

        fun intent(context: Context): Intent = Intent(context, IcocaProjectionActivity::class.java)
    }
}
