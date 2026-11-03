// Adapted from guo-zhc/MiAuto (AGPL-3.0), which builds on this project's 0.1.1.
// See the "Credits and license" section of README.md for the full acknowledgement.
package com.miauto.app;

/**
 * 「连不上之后隔多久再重播一次」的纯判定。
 *
 * <p><b>背景（来自上车日志）</b>：一次异常断连后，车机固定 1.2 秒就重新广播，但手机端 Wi-Fi P2P
 * 组此时往往还在 teardown/重建，于是连续撞 {@code 10003}（无线建链失败）/ {@code 10008}（连接内部
 * 错误）/ {@code 10006}（建链超时），越撞越把共用射频往前推，从断开到真正连回花了近 80 秒。
 *
 * <p>解法：对「无线建链类失败」这一族错误码按连续次数做指数退避（base → ×2 → ×2 …，封顶 max），
 * 给 P2P 组一个干净回收的窗口；其它错误码（如参数非法、投屏启动失败）与射频回收无关，维持固定
 * base 间隔、不放大。连续失败一旦归零（连接成功或换了会话），退避也随之复位。
 *
 * <p>纯逻辑、不碰 Android API 与 SDK，便于 JVM 单测把「哪几档延迟、什么时候封顶、什么时候复位」
 * 钉死（见 {@code ReconnectBackoffTest}）。
 */
final class ReconnectBackoff {

    /** 首次（或非无线建链类失败）重播间隔：与历史 {@code RE_ADVERTISE_DELAY_MS} 对齐，不改变手感。 */
    static final long BASE_MS = 1_200L;

    /** 退避上限：再久也不超过它，保证最终一定能连回，只是把无效硬撞摊薄。 */
    static final long MAX_MS = 8_000L;

    /** 无线建链这一族错误码里，值得退避的三个（P2P/热点建不起来或握手无疾而终）。 */
    static boolean isWirelessBringupFailure(int errorCode) {
        return errorCode == 10003 || errorCode == 10006 || errorCode == 10008;
    }

    /**
     * 下一次重播该隔多久。
     *
     * @param wirelessFailure 本次是否为无线建链类失败（{@link #isWirelessBringupFailure}）
     * @param failureStreak   本会话内<b>连续</b>无线建链失败次数（含本次），从 1 起
     * @return 延时毫秒数；非无线失败恒为 {@link #BASE_MS}
     */
    static long nextDelayMs(boolean wirelessFailure, int failureStreak) {
        if (!wirelessFailure) return BASE_MS;
        long delay = BASE_MS;
        // 第 1 次 → BASE；第 2 次 → BASE*2；… 封顶 MAX。
        for (int i = 1; i < Math.max(1, failureStreak) && delay < MAX_MS; i++) {
            delay = Math.min(delay * 2, MAX_MS);
        }
        return delay;
    }

    /**
     * 连续失败计数怎么随一次连接失败推进。
     *
     * @param streakBefore 本会话已累计的连续无线建链失败次数
     * @param errorCode    本次失败码
     * @return 推进后的计数；非无线建链类失败<b>不累加</b>（保持 0），避免把参数类错误也拖进退避
     */
    static int streakAfterFailure(int streakBefore, int errorCode) {
        if (!isWirelessBringupFailure(errorCode)) return 0;
        return Math.max(0, streakBefore) + 1;
    }

    /** 连接成功 / 会话切换时复位。 */
    static int resetStreak() {
        return 0;
    }
}
