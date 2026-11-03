package com.miauto.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import com.share.connect.WifiOwnerConfig;
import com.ucar.vehiclesdk.ICarConnectListener;
import com.ucar.vehiclesdk.ICarInitCallback;
import com.ucar.vehiclesdk.IPhoneDataListener;
import com.ucar.vehiclesdk.UCarAdapter;
import com.ucar.vehiclesdk.UCarCommon;
import com.ucar.vehiclesdk.UCarConfig;

import java.util.List;
import java.util.Random;

/**
 * MiAuto 车机端 ICCOA CarLink（小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar）SDK 生命周期管理器。
 *
 * <p>本类只管 SDK：初始化 / 广播 / 投屏 Surface / 触控回传 / 回调转发。UI 与 BYD 输出通过
 * {@link Listener} 解耦（由 Activity 或 {@code IcocaNavigationBridge} 实现）。
 *
 * <p><b>★ init-once 状态机</b>：{@link UCarAdapter#init} 在 SDK 内部是「进程一次性」
 * （{@code sIsServiceInitialized} 守门）。重启只 {@code startAdvertise()}，绝不重复 init，
 * 否则 BLE 广播状态残留，手机扫不到、{@code onPinCode} 不再触发。
 * {@link #stop()} 断开但保留初始化标志。
 *
 * <p><b>★★ 连接状态语义</b>（0.1.2 修正）：{@code ICarConnectListener#onConnectStateChanged}
 * 的签名是 {@code (deviceID, state, parameter)} —— <b>第二个参数才是状态，第三个是随 state
 * 变化的附加码</b>。0.1.1 及以前把第三个参数当状态用，于是所有回调都被映射成「广播中」，
 * 投屏页永远打不开。对应关系由 SDK dex 反汇编实证（{@code UCarAdapter} 的各个回调
 * 逐一调用 {@code notifyConnectStateChanged}）：
 * <pre>
 *   onWfdSinkReady          → state 4, parameter -1       投屏通道就绪（真正可以出画）
 *   trySetDeviceConnected   → state 2, parameter -1       设备已连接（可开始绑定 Surface）
 *   notifyDisconnected      → state 1, parameter 11 / -1  会话结束
 *   onUserIntervention/失败  → state 3, parameter 10003/10007  连接失败（需用户干预/超时）
 *   onWfdSinkStopped        → state 5, parameter -1       投屏通道停止（会话可能仍在）
 *   onGoToDesktop           → state 6, parameter -1       手机回桌面
 *   onBackToForeground      → state 7, parameter -1       手机回前台
 * </pre>
 */
public class IccoaManager {

    private static final String TAG = "MiAuto/ICCOA";

    // ---- 连接状态（ICarConnectListener#onConnectStateChanged 的 state 参数）----

    /** 会话结束（SDK 的 {@code notifyDisconnected}）。 */
    public static final int ST_DISCONNECTED = 1;
    /** 设备已连接，尚未出画（SDK 的 {@code trySetDeviceConnected}）。 */
    public static final int ST_CONNECTED = 2;
    /** 连接失败 / 需要用户干预（parameter 为错误码，如 10003、10007）。 */
    public static final int ST_FAILED = 3;
    /** 投屏通道（WFD sink）就绪（SDK 的 {@code onWfdSinkReady}）。 */
    public static final int ST_WFD_READY = 4;
    /** 投屏通道停止（SDK 的 {@code onWfdSinkStopped}）。 */
    public static final int ST_WFD_STOPPED = 5;
    /** 手机侧回到桌面（不影响会话）。 */
    public static final int ST_PHONE_DESKTOP = 6;
    /** 手机侧回到前台（不影响会话）。 */
    public static final int ST_PHONE_FOREGROUND = 7;

    /** 事件回调。由 UI / 导航桥接实现。 */
    public interface Listener {
        void onLog(String msg);

        /**
         * 连接状态变化。
         *
         * @param state     {@link #ST_DISCONNECTED} 等常量
         * @param parameter {@link #ST_FAILED} 时为错误码，其余通常为 -1
         */
        void onConnectionStateChanged(int state, int parameter);

        void onPinCode(String pinCode, String carDeviceName);

        void onCastStarted();

        /** 手机导航信息（POC-2 / BYD 仪表与 HUD 的数据源）。 */
        void onNavigation(UCarCommon.NavigationInfo info);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private UCarAdapter adapter = null;
    private Surface surface = null;

    /** 已经交给 SDK 的那块 Surface。与 {@link #surface} 不同即意味着「换了一块，必须重绑」。 */
    private Surface castSurface = null;

    /** {@link #tryStartCast()} 的重试预算（×500ms = 最长 10 秒）。 */
    private static final int CAST_RETRY_MAX = 20;
    private int castRetry = 0;

    // ---- 状态机 ----
    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkReady = false;
    private volatile boolean castStarted = false;
    private volatile String lastPinCode = null;

    /** 会话结束后重新广播的延时：留出 SDK 内部收尾时间，避免与断连流程竞争。 */
    private static final long RE_ADVERTISE_DELAY_MS = 1200L;

    /**
     * 配对码自动刷新周期。
     *
     * <p>车机一直在广播并不足以把手机叫回来：BLE 广播帧内容不变时，手机端 CarWith 不会重新
     * 弹出配对界面。用户一旦关掉那个弹窗，之后就必须手动去 CarWith 里找车机才能重连。
     * 荣耀车联 / HiCar 的车机端做法是周期性重播，本常量即那个周期。
     */
    private static final long AUTO_REFRESH_INTERVAL_MS = 30_000L;

    /**
     * 是否向手机宣称「本机可以自己开热点（SoftAP）」。
     *
     * <p><b>侧载 APK 必须为 false。</b> 车机热点走 {@code WifiApService}，要
     * {@code TETHER_PRIVILEGED} / {@code OVERRIDE_WIFI_CONFIG} / {@code NETWORK_SETTINGS}，
     * 三者都是 {@code signature|privileged} —— 侧载拿不到，热点永远起不来。可一旦在
     * {@code UCarConfig} 里宣称支持，手机端就会优先选 AP 通道，协商精确地死在 10% 后失败，
     * 表现为「输完配对码一直连不上 / 正在验证后超时」。只宣称 P2P 后，SDK 走
     * {@code WifiP2pService}，会话可以走到 100%。
     *
     * <p>只有当车机是系统签名 / 预装（真的能开热点）时才改回 true —— 那样两条通道都可选。
     */
    private static final boolean SUPPORT_SOFT_AP = false;

    /** 会话是否正在进行（已连接或已出画）。由连接回调维护，看门狗据此避让。 */
    private volatile boolean sessionActive = false;

    /**
     * 会话结束后的重新广播。
     *
     * <p>车机端必须一直可被发现：手机断开后若不再广播，用户就得手动重启 App 才能重连。
     * 重新广播时会清掉旧配对码，手机因此走一次全新配对并下发【新的】配对码 —— 这就是
     * 「断开后自动刷新链接码」。
     */
    private final Runnable reAdvertiseRunnable = () -> {
        if (sdkReady && adapter != null) {
            log("会话结束 → 重新广播（等待手机再次连接，届时下发新的配对码）");
            reAdvertise();
        }
    };

    private void scheduleReAdvertise() {
        main.removeCallbacks(reAdvertiseRunnable);
        boolean wireless = ReconnectBackoff.isWirelessBringupFailure(lastErrorCode);
        long delay = ReconnectBackoff.nextDelayMs(wireless, wirelessFailStreak);
        if (delay != RE_ADVERTISE_DELAY_MS) {
            log("重播退避 " + delay + "ms（连续无线建链失败 " + wirelessFailStreak
                    + " 次，错误码 " + lastErrorCode + "）");
        }
        main.postDelayed(reAdvertiseRunnable, delay);
    }

    private void cancelReAdvertise() {
        main.removeCallbacks(reAdvertiseRunnable);
    }

    /**
     * 配对码看门狗：每隔 {@link #AUTO_REFRESH_INTERVAL_MS} 检查一次，仍未连上就重播一次。
     *
     * <p>这是「刷新出来的新码没人来连，之后就再也不刷新」的解法 —— 那种情况下手机端弹窗
     * 已经关掉，车机只能靠重播把它重新叫出来，否则用户点开 CarWith 也发现不了车机。
     *
     * <p>避让规则：只要会话已经建立（{@link #sessionActive}）或画面已经投出去
     * （{@code castStarted}）就一律跳过。0.1.1 的判定写错了参数（拿附加码当状态），
     * 于是 {@code sessionActive} 永远是 false —— 配对进行到一半就被重播打断，
     * 刚下发的配对码被换成新的，手机端因此停在「正在验证」直到超时。
     */
    private final Runnable autoRefreshRunnable = () -> {
        if (!sdkReady || adapter == null) return;
        if (sessionActive || castStarted) {
            log("自动刷新：会话进行中，跳过本轮");
            scheduleAutoRefresh();
            return;
        }
        log("★ 自动刷新：超过 " + (AUTO_REFRESH_INTERVAL_MS / 1000) + " 秒未连接 → 重播以重新触发手机弹窗");
        reAdvertise();
        scheduleAutoRefresh();
    };

    private void scheduleAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
        main.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    private void cancelAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
    }

    // ---- 屏幕尺寸 ----
    /**
     * 协商画布。{@code 0} = 还没量到 —— {@link DeviceDisplay} 取不到时不会编一个常见值顶替，
     * 因为错误画布一旦报给手机端，之后没有任何环节会纠正它（画面拉伸、触控整体偏移）。
     *
     * <p>{@code videoW/videoH} 是 UIBC 触控坐标的除数，不能为 0，所以保留上一次有效值；
     * 首次 {@link #setScreenSize} 之前用 1920x720 只为不除零，真正投入使用的画布一定来自实测。
     */
    private int screenW = 0, screenH = 0, videoW = 1920, videoH = 720;

    /** 灌进 {@code UCarConfig} 的那一份画布；判断「这次连接要不要重谈」就靠它。 */
    private volatile int initCanvasW = 0, initCanvasH = 0;

    /** 本会话内连续「无线建链类失败」次数，{@link ReconnectBackoff} 据此放大重播间隔。 */
    private int wirelessFailStreak = 0;

    /** 最近一次失败的错误码（{@code state == 3} 的 parameter），决定这次要不要退避。 */
    private int lastErrorCode = -1;

    public IccoaManager(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    /**
     * 灌入这块屏的真实画布（已按用户设置的屏幕方向归好序，见 {@link ScreenSizePolicy}）。
     *
     * <p>无效值一律不落库：把 0 或负数报给手机端，协商出来的就是一格像素的画布。
     * 宁可维持「未量到」让上层重试。
     */
    public void setScreenSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            log("! 忽略无效的屏幕尺寸 " + w + "x" + h + " —— 保留上一次有效值，等重试");
            return;
        }
        if (w != screenW || h != screenH) {
            log("协商画布 " + w + "x" + h
                    + (initCanvasW > 0 ? "（init 时 " + initCanvasW + "x" + initCanvasH + "）" : "（首次）"));
        }
        screenW = w;
        screenH = h;
        videoW = w;
        videoH = h;
    }

    /** 投屏画面（= 手机端坐标系）的宽高。UIBC 回传的触控坐标必须落在这个空间里。 */
    public int getVideoWidth() { return videoW; }

    public int getVideoHeight() { return videoH; }

    public String getLastPinCode() { return lastPinCode; }

    /**
     * Surface 就绪 / 变化（Activity 的 surfaceCreated / surfaceChanged 回调里调用）。
     *
     * <p><b>换 Surface 必须先让 SDK 停手。</b> {@code startCast} 一旦成功，SDK 就会持续往
     * 那块 BufferQueue 写帧；投屏页退出、窗口重建都会销毁旧 Surface，此时若只是「把新
     * Surface 传进来」而不复位状态，{@code tryStartCast()} 会因为 {@code castStarted} 仍为
     * true 直接返回，SDK 继续往已废弃的队列里解码 —— 报 {@code BufferQueue has been
     * abandoned / -19}，画面永久黑掉而声音还在。
     */
    public void setSurface(Surface s) {
        if (s != null && s == castSurface && castStarted) {
            // 同一个 Surface 的重复回调（surfaceChanged 常见）：无需重绑。
            return;
        }
        if (castStarted) {
            log("Surface 变更 → 先 pauseCast()，再绑定新 Surface");
            pauseCastQuietly();
            castStarted = false;
            castSurface = null;
        }
        this.surface = s;
        castRetry = 0;          // 新 Surface 到位，重试预算重置
        tryStartCast();
    }

    /**
     * Surface 被销毁（Activity 的 {@code surfaceDestroyed}）。
     *
     * <p>不主动停手的后果见 {@link #setSurface(Surface)}：SDK 会对废弃的 BufferQueue 解码并
     * 报致命 -19，之后同一会话再也恢复不了画面。
     */
    public void onSurfaceDestroyed() {
        log("Surface 销毁 → pauseCast，等待新 Surface 重新绑定");
        if (castStarted) pauseCastQuietly();
        castStarted = false;
        castSurface = null;
        surface = null;
    }

    private void pauseCastQuietly() {
        if (adapter == null) return;
        try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /** 启动（或重启）投屏链路。权限由调用方（Activity）负责。 */
    public void start() {
        log("=== 启动（ICCOA CarLink）===");
        if (sdkInitialized && adapter != null) {
            if (!renegotiateIfStale("start")) {
                log("重启：SDK 已初始化，仅重新广播");
                reAdvertise();
                return;
            }
            log("画布已变 → 旧 SDK 已拆，走完整重 init");
        }
        UCarConfig config;
        try {
            config = buildConfig();
        } catch (Throwable t) {
            log("✗ 配置构造失败: " + t);
            return;
        }
        try {
            adapter = UCarAdapter.getInstance();
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("✗ getInstance/register 异常: " + t);
            return;
        }
        // 记下这份 config 实际带的画布，供下一次 start() 判断是否需要重谈。
        initCanvasW = screenW;
        initCanvasH = screenH;
        initSdk(config);
    }

    /**
     * 协商画布在 {@code init()} 那一刻定死，运行期没有任何 API 能改。
     *
     * <p>横竖互换、换屏、改显示大小之后若仍拿旧 config 重播，手机端就按旧画布编码 ——
     * 画面被拉伸、UIBC 触控整体错位。这种时候只能拆掉 SDK 重新 init。
     *
     * @return true = 已经拆过，调用方必须继续走完整的 init 流程，不能再走重播分支
     */
    private boolean renegotiateIfStale(String reason) {
        if (!ScreenSizePolicy.needsRenegotiation(screenW, screenH, initCanvasW, initCanvasH)) {
            return false;
        }
        log("协商画布需重谈（init " + initCanvasW + "x" + initCanvasH
                + " → 现在 " + screenW + "x" + screenH + "）→ 拆 SDK 重新 init（" + reason + "）");
        deinitAdapter();
        return true;
    }

    /**
     * 拆掉 SDK 连接层，让下一次 {@link #start()} 走完整 init。
     *
     * <p>{@code deInit()} 之后 {@code getInstance()} 仍返回同一单例，可以重新 init —— 这是
     * 「改方向/换屏后重连仍按旧画布」唯一的解法。任一步失败都只记日志：把 SDK 拆到一半比
     * 带着旧画布重播更糟，所以每一步独立 try，保证后面的清理一定执行。
     */
    private void deinitAdapter() {
        cancelReAdvertise();
        cancelAutoRefresh();
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable ignored) { }
            try { adapter.stopAdvertise(); } catch (Throwable ignored) { }
            try { adapter.disconnect(); } catch (Throwable ignored) { }
            try {
                adapter.unregisterCarConnectListener(connectListener);
                adapter.unregisterPhoneDataListener(phoneDataListener);
            } catch (Throwable ignored) { }
            try { adapter.deInit(); } catch (Throwable t) { log("deInit 异常: " + t); }
        }
        adapter = null;
        sdkInitialized = false;
        sdkReady = false;
        castStarted = false;
        castSurface = null;
        sessionActive = false;
        lastPinCode = null;
        initCanvasW = 0;
        initCanvasH = 0;
        wirelessFailStreak = ReconnectBackoff.resetStreak();
        lastErrorCode = -1;
    }

    private void initSdk(UCarConfig config) {
        boolean posted = adapter.init(appCtx, config, new ICarInitCallback() {
            @Override
            public void onInitSuccess() {
                log("★ onInitSuccess()");
                sdkReady = true;
                sdkInitialized = true;   // ★ init-once 守门
                main.post(() -> safe("doAdvertise", IccoaManager.this::doAdvertise));
                main.post(IccoaManager.this::scheduleAutoRefresh);   // ★ 启动配对码看门狗
            }

            @Override
            public void onInitFailed(int errCode) {
                log("✗ onInitFailed(" + errCode + ")"
                        + (errCode == 10001 ? " ← HandlerThread 启动失败" : ""));
            }
        });
        if (!posted) log("✗ init() 返回 false —— 任务未提交");
    }

    /**
     * 重播：先停旧广播，再启新广播。
     *
     * <p><b>必须先 {@code stopAdvertise()}</b> —— 对一条已经在广播中的链路重复调用
     * {@code startAdvertise()} 会被 SDK 直接忽略，广播帧内容不变，手机端因此不会重新发现
     * 车机、也就不会重新弹窗。只有「停-启」才是一次真正的重播。
     */
    private void reAdvertise() {
        if (adapter == null) return;
        try {
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("reAdvertise 重新注册异常: " + t);
        }
        try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise(重播前) 异常: " + t); }
        sdkReady = true;
        castStarted = false;
        castSurface = null;
        castRetry = 0;
        lastPinCode = null;
        sessionActive = false;
        doAdvertise();
        // 重播之后回到「等待手机」—— 让界面上的失败提示 / 旧配对码及时收回去。
        notifyState(ST_DISCONNECTED, -1);
    }

    /**
     * 用户主动「刷新配对码」：立刻重播一次，不必等看门狗的 30 秒周期。
     *
     * <p>与自动刷新走同一条路径，重播后看门狗重新计时。
     */
    public void refreshPairing() {
        log("=== 手动刷新配对码 ===");
        if (!sdkReady || adapter == null) {
            log("刷新失败：SDK 尚未就绪");
            return;
        }
        reAdvertise();
        scheduleAutoRefresh();
    }

    public void stop() {
        log("=== 停止 ===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
            try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise 异常: " + t); }
            try {
                adapter.unregisterCarConnectListener(connectListener);
                adapter.unregisterPhoneDataListener(phoneDataListener);
            } catch (Throwable ignored) { }
        }
        main.removeCallbacks(reAdvertiseRunnable);
        cancelAutoRefresh();
        sessionActive = false;
        castStarted = false;
        castSurface = null;
        sdkReady = false;
        lastPinCode = null;
        // 保留 sdkInitialized —— init() 进程一次性，重启只走 reAdvertise()。
    }

    /**
     * 仅断开当前会话（用户主动点「断开」），保持广播 —— 手机可立即重新连接。
     *
     * <p>与 {@link #stop()} 的区别：{@code stop()} 会停掉广播（相当于彻底关机位），
     * 本方法只拆会话，车机仍可被发现。
     */
    public void disconnectSession() {
        log("=== 断开当前会话（保持广播）===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
        }
        sessionActive = false;
        castStarted = false;
        castSurface = null;
        lastPinCode = null;
        scheduleReAdvertise();
        scheduleAutoRefresh();
    }

    // ==================================================================
    // 广播 / 投屏 / 触控
    // ==================================================================

    private void doAdvertise() {
        if (adapter == null) return;
        boolean ok = adapter.startAdvertise();
        log((ok ? "✓ " : "✗ ") + "startAdvertise() = " + ok);
        tryStartCast();
    }

    /**
     * 尝试起投。
     *
     * <p>重试有上限：停车待机页没有 Surface，无上限重试会变成一个永不停止的 500ms 定时器
     * （待机页常驻，等于白耗电）。Surface 稍后就绪的常见情形由 {@link #setSurface} 兜底，
     * 这里的十秒预算只用于「Surface 已设但尚未 valid」的短暂窗口。
     */
    private void tryStartCast() {
        if (!sdkReady || castStarted || adapter == null) return;
        Surface s = surface;
        if (s == null || !s.isValid()) {
            if (castRetry < CAST_RETRY_MAX) {
                castRetry++;
                main.postDelayed(this::tryStartCast, 500);
            }
            return;
        }
        castRetry = 0;
        boolean ok = adapter.startCast(s, videoW, videoH);
        castStarted = ok;
        castSurface = ok ? s : null;
        log((ok ? "✓ " : "✗ ") + "startCast(" + videoW + "x" + videoH + ") = " + ok);
        if (ok) {
            main.post(listener::onCastStarted);
        }
    }

    /**
     * 把车机上的触控事件回传给手机（ICCOA 的 UIBC 通道）。
     *
     * <p>参数语义取自 SDK 自身：{@code UCarAdapter#sendTouchEvent(int action, int count,
     * int[] trackID, int[] x, int[] y)}，三个数组长度必须都等于 {@code count}；
     * 坐标是<b>车机显示区（{@code setVideoDisplayWidth/Height} 声明的那块）</b>的像素，
     * SDK 内部再按比例换算回手机的 source 坐标系。
     *
     * @return false 表示通道尚未就绪（加密未完成 / 未连接），调用方可以忽略
     */
    public boolean sendTouchEvent(int action, int count, int[] trackIds, int[] xs, int[] ys) {
        if (adapter == null || count <= 0) return false;
        if (trackIds == null || xs == null || ys == null) return false;
        if (trackIds.length != count || xs.length != count || ys.length != count) {
            log("sendTouchEvent 参数长度不一致，丢弃");
            return false;
        }
        try {
            return adapter.sendTouchEvent(action, count, trackIds, xs, ys);
        } catch (Throwable t) {
            log("sendTouchEvent 异常: " + t);
            return false;
        }
    }

    // ==================================================================
    // SDK 回调
    // ==================================================================

    private final ICarConnectListener connectListener = new ICarConnectListener() {
        @Override
        public void onPinCode(String pinCode, String carDeviceName) {
            lastPinCode = pinCode;
            log("★ onPinCode pinCode=" + pinCode + " carDeviceName=" + carDeviceName);
            // 新码下发 → 看门狗重新计时，保证用户拿到完整的有效期去确认，不会刚出码就被重播顶掉。
            scheduleAutoRefresh();
            main.post(() -> listener.onPinCode(pinCode, carDeviceName));
        }

        @Override
        public void onConnectStateChanged(String deviceID, int state, int parameter) {
            log("onConnectStateChanged state=" + stateName(state) + " parameter=" + parameter);
            switch (state) {
                case ST_DISCONNECTED:
                    // 会话真正结束：清掉已过期的配对码并延时重新广播，手机端 CarWith 会重新发现
                    // 车机并触发一次全新配对（新的 onPinCode）。
                    sessionActive = false;
                    castStarted = false;
                    castSurface = null;
                    lastPinCode = null;
                    scheduleReAdvertise();
                    scheduleAutoRefresh();
                    break;

                case ST_CONNECTED:
                    // 设备已连接：撤销待执行的重新广播与看门狗，绝不打断进行中的会话。
                    sessionActive = true;
                    wirelessFailStreak = ReconnectBackoff.resetStreak();   // 链路活了，退避复位
                    lastErrorCode = -1;
                    cancelReAdvertise();
                    cancelAutoRefresh();
                    break;

                case ST_FAILED:
                    // 失败 / 需要用户干预（parameter 是错误码）。会话没建立，重播一次把手机叫回来。
                    // 但无线建链类失败（10003/10006/10008）说明 P2P 组正在 teardown，此时固定
                    // 1.2 秒硬撞会把共用射频越推越乱 —— 按连续次数指数退避，给回收留出窗口。
                    lastErrorCode = parameter;
                    wirelessFailStreak = ReconnectBackoff.streakAfterFailure(wirelessFailStreak, parameter);
                    log("✗ 连接失败，错误码 " + parameter + " → 重播以重新触发配对");
                    sessionActive = false;
                    castStarted = false;
                    castSurface = null;
                    lastPinCode = null;
                    scheduleReAdvertise();
                    scheduleAutoRefresh();
                    break;

                case ST_WFD_READY:
                    // 投屏通道就绪：会话必定在。丢掉旧的 cast 绑定，让新的 Surface 重新起投。
                    sessionActive = true;
                    wirelessFailStreak = ReconnectBackoff.resetStreak();   // 通道起来了，退避复位
                    cancelReAdvertise();
                    cancelAutoRefresh();
                    castStarted = false;
                    castSurface = null;
                    main.post(IccoaManager.this::tryStartCast);
                    break;

                case ST_WFD_STOPPED:
                    // 通道停了但会话可能还活着（手机会重开），只复位 cast，不动 phase。
                    castStarted = false;
                    castSurface = null;
                    break;

                case ST_PHONE_DESKTOP:
                case ST_PHONE_FOREGROUND:
                default:
                    // 手机侧的界面切换，与车机会话无关。
                    break;
            }
            notifyState(state, parameter);
        }

        @Override
        public void onConnectingProgress(String phoneId, int progress) {
            log("onConnectingProgress " + progress);
        }

        @Override
        public void onReconfigureWifiInfo(WifiOwnerConfig config) {
            log("onReconfigureWifiInfo ssid=" + (config == null ? "?" : config.getSsid()));
        }

        @Override
        public void onRethinkBluetoothConnect(boolean rethink) {
            log("onRethinkBluetoothConnect=" + rethink);
        }

        @Override
        public UCarCommon.WorkMode onSelectWorkMode(String deviceID, UCarCommon.WorkMode supported) {
            // 与 buildConfig() 里声明的一致：只声明了普通投屏，就优先选它。0.1.1 先挑
            // APP_WINDOW_CAST，与配置自相矛盾，手机端可能据此协商出车机并不支持的形态。
            UCarCommon.WorkMode chosen;
            if (supported == null) {
                chosen = UCarCommon.WorkMode.NONE;
            } else if (supported.isSupportNormalCast()) {
                chosen = UCarCommon.WorkMode.NORMAL_CAST;
            } else if (supported.isSupportAppFullScreenCast()) {
                chosen = UCarCommon.WorkMode.APP_FULLSCREEN_CAST;
            } else if (supported.isSupportAppWindowCast()) {
                chosen = UCarCommon.WorkMode.APP_WINDOW_CAST;
            } else if (supported.isSupportDataTransfer()) {
                chosen = UCarCommon.WorkMode.DATA_TRANSFER;
            } else {
                chosen = UCarCommon.WorkMode.NONE;
            }
            log("onSelectWorkMode → " + chosen + "（手机声明 " + supported + "）");
            return chosen;
        }
    };

    private final IPhoneDataListener phoneDataListener = new IPhoneDataListener() {
        @Override
        public void onNavigationInfoReceived(String phoneId, UCarCommon.NavigationInfo info) {
            listener.onNavigation(info);
        }

        @Override public void onMusicInfoReceived(String phoneId, UCarCommon.MusicInfo info) { }
        @Override public void onCallInfoReceived(String phoneId, UCarCommon.CallInfo info) { }
        @Override public void onPhoneStateInfoReceived(String phoneId, UCarCommon.PhoneStateInfo info) { }
        @Override public void onPOIReceived(String phoneId, UCarCommon.POIAddress poi) { }
        @Override public void onAudioFocusChanged(UCarCommon.AudioType type, int focus) { }

        @Override
        @SuppressWarnings("rawtypes")
        public void onAppListStateChanged(String phoneId, List list, UCarCommon.AppListState state) { }

        @Override
        public void onAppStateChanged(String phoneId, int state, boolean flag,
                                      int a, int b, UCarCommon.AppState appState) { }

        @Override
        public UCarCommon.AudioAttributes getAudioAttributesByType(UCarCommon.AudioType type) {
            return null;
        }
    };

    // ==================================================================
    // 配置构造（SDK 硬校验，越界即抛）
    // ==================================================================

    private UCarConfig buildConfig() {
        byte[] carId = generateCarId();
        if (carId == null || carId.length != 6) {
            carId = new byte[6];
            new Random().nextBytes(carId);
            log("! carId 长度异常，强制随机 6 字节");
        }
        android.util.DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
        int densityDpi = dm.densityDpi;
        int dpi = clamp(320, 120, 640);
        int realDpi = clamp(densityDpi, 120, 640);
        int fps = clamp(60, 15, 60);
        int sw = Math.max(1, screenW), sh = Math.max(1, screenH);
        int vw = Math.max(1, videoW), vh = Math.max(1, videoH);

        UCarCommon.WorkMode workMode = new UCarCommon.WorkMode.Builder()
                .setSupportNormalCast(true)
                .setSupportAppFullScreenCast(false)
                .setSupportAppWindowCast(false)
                .build();

        log("通道声明：P2P=true SoftAP=" + SUPPORT_SOFT_AP
                + (SUPPORT_SOFT_AP ? "" : "（侧载 APK 拿不到热点权限，见 SUPPORT_SOFT_AP 注释）"));

        return new UCarConfig.Builder()
                .setCarBrMac(carId)                  // 必须恰好 6 字节
                .setDpi(dpi)                         // 120..640
                .setRealDpi(realDpi)                 // 120..640
                .setFps(fps)                         // 15..60
                .setScreenWidth(sw)
                .setScreenHeight(sh)
                .setVideoDisplayWidth(vw)
                .setVideoDisplayHeight(vh)
                .setSupportP2P(true)
                .setSupportSoftAP(SUPPORT_SOFT_AP)
                .setSupportCamera(false)
                .setSupportHevc(false)
                .setSupportMic(true)
                .setUseCustomAudioRecord(false)
                .setSupportStereoRecord(false)
                .setSupportLowLatencyDecodingMode(false)
                .setSupportPOINav(false)
                .setCcdFilePath("ccd.data")
                .setSupportedWorkModes(workMode)
                .build();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 车机身份（6 字节 BR MAC）。
     *
     * <p>Android 6 起非系统应用调用 {@code BluetoothAdapter.getAddress()} 只会拿到
     * {@code 02:00:00:00:00:00} —— 这是占位值不是真实地址。把它当身份发给 SDK，所有侧载车机
     * 就会共用同一个 id，手机端会把它当成「同一台车」而拒绝/复用错误的会话。
     * 因此占位值与全零一律作废，退回随机值；需要在多台车机上做区分时，可用
     * {@code car_id_prefs/car_id_override_mac} 显式指定真实地址。
     */
    private byte[] generateCarId() {
        SharedPreferences prefs = appCtx.getSharedPreferences("car_id_prefs", Context.MODE_PRIVATE);

        byte[] override = parseMac(prefs.getString("car_id_override_mac", null));
        if (override != null) return override;

        String saved = prefs.getString("car_id_key", "");
        if (saved != null && !saved.isEmpty()) {
            try {
                byte[] cached = Base64.decode(saved, Base64.NO_WRAP);
                if (cached != null && cached.length == 6 && !isPlaceholder(cached)) return cached;
                log("carId 缓存长度≠6 或为占位地址，作废重生成");
            } catch (Throwable t) {
                log("carId 缓存解码失败: " + t);
            }
        }
        byte[] raw = getBluetoothMac();
        if (raw == null || raw.length != 6 || isPlaceholder(raw)) {
            raw = new byte[6];
            new Random().nextBytes(raw);
            // 别把随机值撞成广播/组播地址。
            raw[0] = (byte) ((raw[0] & 0xFC) | 0x02);
        }
        prefs.edit().putString("car_id_key", Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        return raw;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private byte[] getBluetoothMac() {
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter a = (bm == null) ? null : bm.getAdapter();
            String mac = (a == null) ? null : a.getAddress();
            byte[] parsed = parseMac(mac);
            if (parsed != null) return parsed;
            log("读到的蓝牙地址不可用（占位/无权限）: " + mac);
        } catch (Throwable t) {
            log("读蓝牙 MAC 被拒（缺 BLUETOOTH_CONNECT？）: " + t);
        }
        return null;
    }

    /** 把 {@code AA:BB:CC:DD:EE:FF} 解析成 6 字节；占位地址与非法串一律返回 null。 */
    private byte[] parseMac(String mac) {
        if (mac == null) return null;
        String[] seg = mac.split(":");
        if (seg.length != 6) return null;
        byte[] out = new byte[6];
        try {
            for (int i = 0; i < 6; i++) out[i] = (byte) Integer.parseInt(seg[i], 16);
        } catch (Throwable t) {
            return null;
        }
        return isPlaceholder(out) ? null : out;
    }

    /** {@code 02:00:00:00:00:00} 与全零都是「没有真实地址」的占位值。 */
    private static boolean isPlaceholder(byte[] mac) {
        if (mac == null || mac.length != 6) return true;
        boolean allZero = true;
        for (byte b : mac) if (b != 0) { allZero = false; break; }
        if (allZero) return true;
        return mac[0] == 0x02 && mac[1] == 0 && mac[2] == 0
                && mac[3] == 0 && mac[4] == 0 && mac[5] == 0;
    }

    private void notifyState(int state, int parameter) {
        main.post(() -> {
            try { listener.onConnectionStateChanged(state, parameter); } catch (Throwable ignored) { }
        });
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        try { listener.onLog(msg); } catch (Throwable ignored) { }
    }

    private void safe(String where, Runnable r) {
        try { r.run(); } catch (Throwable t) { log("!!! " + where + " 异常: " + t); }
    }

    private static String stateName(int s) {
        switch (s) {
            case ST_DISCONNECTED: return "DISCONNECTED(1)";
            case ST_CONNECTED: return "CONNECTED(2)";
            case ST_FAILED: return "FAILED(3)";
            case ST_WFD_READY: return "WFD_READY(4)";
            case ST_WFD_STOPPED: return "WFD_STOPPED(5)";
            case ST_PHONE_DESKTOP: return "PHONE_DESKTOP(6)";
            case ST_PHONE_FOREGROUND: return "PHONE_FOREGROUND(7)";
            default: return "STATE_" + s;
        }
    }
}
