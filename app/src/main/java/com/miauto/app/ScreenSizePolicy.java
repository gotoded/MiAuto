// Adapted from guo-zhc/MiAuto (AGPL-3.0), which builds on this project's 0.1.1.
// See the "Credits and license" section of README.md for the full acknowledgement.
package com.miauto.app;

/**
 * 「这块屏的物理尺寸该以什么顺序报给 ICCOA SDK」的纯判定。
 *
 * <p>历史口径是无条件长边在前（{@code MainActivity.startIcocaLink} 的注释原话：车机就是
 * 横屏、SDK 只要横屏画布）。竖屏车机 / 竖屏平板上这个假设是错的：手机端按横屏画布协商
 * 编码，竖屏 Surface 上画面被拉变形；而 UIBC 触摸按 Surface <em>实际</em>尺寸映射
 * （{@code startCast(surface, w, h)} 的 w/h 只做除数），于是点击与所见内容整体错位。
 *
 * <p>口径与 {@code com.andrerinas.openheadunit.utils.Settings.ScreenOrientation} 对齐
 * （value 见 utils/Settings.kt 的枚举定义）：
 * <ul>
 *   <li>{@code PORTRAIT(4)} / {@code PORTRAIT_REVERSE(5)} → <b>短边在前</b>（竖屏画布）；</li>
 *   <li>{@code LANDSCAPE(2)} / {@code LANDSCAPE_REVERSE(3)} → <b>长边在前</b>（横屏画布，原行为）；</li>
 *   <li>{@code SYSTEM(0)} / {@code AUTO(1)} → <b>原样透传</b>当前窗口的长短边关系 —— 窗口方向
 *       就是用户此刻看到的方向，让协商画布跟设备形态解耦（竖屏平板 + SYSTEM 不再是隐性横屏）。</li>
 * </ul>
 *
 * <p>调用方拿到的就是「上报序」，直接喂 {@code setScreenSize}；无效尺寸原样返回 {@code {0, 0}}，
 * 由调用方的重试机制接手（见 {@code MainActivity.startIcocaLink} 的说明）。
 */
public final class ScreenSizePolicy {

    private ScreenSizePolicy() { }

    /** {@code Settings.ScreenOrientation.PORTRAIT}。 */
    public static final int PORTRAIT = 4;
    /** {@code Settings.ScreenOrientation.PORTRAIT_REVERSE}。 */
    public static final int PORTRAIT_REVERSE = 5;
    /** {@code Settings.ScreenOrientation.LANDSCAPE}。 */
    public static final int LANDSCAPE = 2;
    /** {@code Settings.ScreenOrientation.LANDSCAPE_REVERSE}。 */
    public static final int LANDSCAPE_REVERSE = 3;

    /**
     * @param rawW             {@code DeviceDisplay.realSizePx} 的宽（未归一的当前窗口/物理尺寸）
     * @param rawH             高
     * @param orientationValue {@code Settings.screenOrientation.value}（0..5）
     * @return {@code int[]{w, h}} 上报序；任一边非正时 {@code {0, 0}}
     */
    public static int[] reported(int rawW, int rawH, int orientationValue) {
        if (rawW <= 0 || rawH <= 0) return new int[]{0, 0};
        if (orientationValue == PORTRAIT || orientationValue == PORTRAIT_REVERSE) {
            return new int[]{Math.min(rawW, rawH), Math.max(rawW, rawH)};
        }
        if (orientationValue == LANDSCAPE || orientationValue == LANDSCAPE_REVERSE) {
            return new int[]{Math.max(rawW, rawH), Math.min(rawW, rawH)};
        }
        // SYSTEM / AUTO：窗口此刻是哪种方向就报哪种，不猜。
        return new int[]{rawW, rawH};
    }

    /**
     * 「这次连接要不要重新协商画布」的纯判定 —— 重连时拿「现在量到的画布」与「SDK 初始化时
     * 灌进 {@code UCarConfig} 的那份」比一比。
     *
     * <p>为什么需要它：协商画布在 {@code init()} 那一刻定死、运行期没有任何 API 能改，而
     * {@code start()} 在 {@code sdkInitialized} 为真时默认只走 reAdvertise（拿旧 config
     * 重播）。横竖互换、分辨率变化（换屏 / 改显示大小）都必须拆 SDK 重 init，否则手机端
     * 仍按旧画布编码 —— SYSTEM 档转过屏幕再连却还是老方向，就是这个漏洞。
     *
     * @param nowW  现在量到的画布宽（{@link #reported} 的产物）；未量到传 0
     * @param nowH  现在量到的画布高
     * @param initW SDK 初始化时灌进 config 的画布宽；从未记下传 0
     * @param initH SDK 初始化时灌进 config 的画布高
     * @return true = 需要拆 SDK 重 init（见 {@code IccoaManager#resumeLink}）
     */
    public static boolean needsRenegotiation(int nowW, int nowH, int initW, int initH) {
        // 没量到有效尺寸时不敢下结论：拆了重建只会拿到 buildConfig 的 1x1 兜底画布，更糟，
        // 宁可拿旧 config 重播。
        if (nowW <= 0 || nowH <= 0) return false;
        // init 时没记下画布（那次 init 的尺寸就是无效的，config 用了 Math.max(1, …) 的残缺值）
        // → 那份 config 不能被信任，重谈。
        if (initW <= 0 || initH <= 0) return true;
        return nowW != initW || nowH != initH;
    }
}
