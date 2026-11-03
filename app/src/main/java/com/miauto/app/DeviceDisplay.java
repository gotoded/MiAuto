// Adapted from guo-zhc/MiAuto (AGPL-3.0), which builds on this project's 0.1.1.
// See the "Credits and license" section of README.md for the full acknowledgement.
package com.miauto.app;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

/**
 * 运行时读取「当前这块屏」的<b>真实</b>分辨率与屏幕密度 —— 分辨率相关的唯一数据源。
 *
 * <p>以前这类信息散落在各处，且带着写死的兜底（{@code 1920x720}、{@code dpi=320}）。那些常量
 * 在一台屏幕尺寸不同的车机上就是错的：手机会按错误的画布出图（画面拉伸/模糊），UIBC 触摸
 * 也会整体偏移。所以这里只做一件事 —— <b>问系统要，要不到就说要不到</b>。
 *
 * <p><b>拿不到时返回 0，不退回常量</b>（见 {@link #NOT_READY}）。「先写个常见值顶着」看起来
 * 无害，实际是把错误静默地写进协商结果：手机端收到 1920x720 就会按 1920x720 编码，之后再
 * 没有任何环节会纠正它。调用方的责任是<b>重试</b>（见 {@code MainActivity.startIcocaLink}）。
 *
 * <p>取值优先级（越靠前越贴近「实际用来出画的那块画布」）：
 * <ol>
 *   <li>{@code WindowManager#getCurrentWindowMetrics()}（API 30+）—— 当前窗口的实际边界，
 *       分屏 / 多窗口 / 自由缩放窗口下会跟着变，这正是「窗口缩放」要响应的那个值；</li>
 *   <li>{@code Display#getRealMetrics()} —— 这块屏的物理像素（含系统栏）；</li>
 *   <li>{@code Resources#getDisplayMetrics()} —— 最后兜底，通常是<b>可用区域</b>（不含系统栏），
 *       比真实分辨率小一点，但总比编一个强。</li>
 * </ol>
 *
 * <p><b>多显示器</b>：优先用 {@code Context#getDisplay()}（API 30+）。Activity 挂在哪块屏上，
 * 取到的就是哪块屏 —— 车机把投屏放到副屏（{@code Presentation}）时不会拿成主屏的尺寸。
 */
public final class DeviceDisplay {

    /** 分辨率还没拿到（窗口未 attach / Display 不可用）。调用方必须重试，不得当作尺寸使用。 */
    public static final int NOT_READY = 0;

    private DeviceDisplay() { }

    /**
     * 当前显示器的像素尺寸，<b>不</b>做横竖屏归一（宽就是宽、高就是高）。
     *
     * @return {@code int[]{w, h}}；未就绪时是 {@code {0, 0}}
     */
    public static int[] realSizePx(Context ctx) {
        if (ctx == null) return new int[]{NOT_READY, NOT_READY};

        int[] size = currentWindowBounds(ctx);
        if (ready(size)) return size;

        size = realDisplayMetrics(ctx);
        if (ready(size)) return size;

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return new int[]{Math.max(NOT_READY, dm.widthPixels), Math.max(NOT_READY, dm.heightPixels)};
    }

    /**
     * 逻辑密度：UI 缩放用的那个值（{@code densityDpi}），对应「逻辑像素」这一侧。
     *
     * @return &gt; 0 的密度桶；拿不到时返回 {@link #NOT_READY}
     */
    public static int logicalDpi(Context ctx) {
        DisplayMetrics dm = metrics(ctx);
        return dm != null && dm.densityDpi > 0 ? dm.densityDpi : NOT_READY;
    }

    /**
     * 物理密度：这块屏每英寸的真实像素数，对应「物理像素」这一侧。
     *
     * <p>由 {@code xdpi} / {@code ydpi} 取几何平均（两者在非正方像素的屏上并不相等），
     * 而不是直接拿 {@code densityDpi} —— 后者是系统挑的密度<b>桶</b>（320 / 480 …），
     * 与真实物理密度可以差很远。个别设备 {@code xdpi} 为 0（模拟器 / 异常 ROM），
     * 那时才退回逻辑密度，并从此刻起两者口径一致，不会一边物理一边逻辑。
     *
     * @return &gt; 0 的物理 DPI；拿不到时返回 {@link #NOT_READY}
     */
    public static int physicalDpi(Context ctx) {
        DisplayMetrics dm = metrics(ctx);
        if (dm == null) return NOT_READY;
        if (dm.xdpi > 0f && dm.ydpi > 0f) {
            return Math.round((float) Math.sqrt(dm.xdpi * dm.ydpi));
        }
        return dm.densityDpi > 0 ? dm.densityDpi : NOT_READY;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static boolean ready(int[] size) {
        return size != null && size[0] > 0 && size[1] > 0;
    }

    /** 当前窗口的实际边界（API 30+）。窗口缩放 / 分屏 / 旋转都会反映在这里。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private static int[] currentWindowBounds(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return new int[]{NOT_READY, NOT_READY};
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return new int[]{NOT_READY, NOT_READY};
        Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        return new int[]{Math.max(NOT_READY, bounds.width()), Math.max(NOT_READY, bounds.height())};
    }

    /** 这块屏的物理像素（含系统栏）。 */
    @SuppressWarnings("deprecation")
    private static int[] realDisplayMetrics(Context ctx) {
        Display display = displayOf(ctx);
        if (display == null) return new int[]{NOT_READY, NOT_READY};
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            display.getRealMetrics(dm);
        } else {
            display.getMetrics(dm);
        }
        return new int[]{Math.max(NOT_READY, dm.widthPixels), Math.max(NOT_READY, dm.heightPixels)};
    }

    private static DisplayMetrics metrics(Context ctx) {
        Display display = displayOf(ctx);
        if (display != null) {
            DisplayMetrics dm = new DisplayMetrics();
            display.getMetrics(dm);
            if (dm.densityDpi > 0) return dm;
        }
        return ctx != null ? ctx.getResources().getDisplayMetrics() : null;
    }

    /**
     * 当前 Context 所在的那块屏。
     *
     * <p>优先 {@code Context#getDisplay()}：Activity 挂在哪块屏上就取哪块，副屏场景才不会
     * 拿错。取不到（app context / 旧系统）再走 {@code WindowManager#getDefaultDisplay()}。
     */
    @SuppressWarnings("deprecation")
    private static Display displayOf(Context ctx) {
        if (ctx == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Display d = ctx.getDisplay();
                if (d != null) return d;
            } catch (Throwable ignored) {
                // 个别 ROM 在未 attach 的 context 上会抛，继续走默认显示器。
            }
        }
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        return wm != null ? wm.getDefaultDisplay() : null;
    }
}
