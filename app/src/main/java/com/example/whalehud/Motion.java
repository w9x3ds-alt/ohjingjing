package com.example.whalehud;

import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * 动效令牌（Motion tokens）。
 *
 * 来源：本项目的设计逆向方法论 —— 动画是「品味指纹」，不能靠手感现编。
 * 两条硬规则：
 *
 *   1. <b>duration ramp</b>，每个档位有明确分配，不许在业务代码里写魔法数字：
 *      INSTANT 75ms  即时反馈（按压、涟漪）
 *      FAST    120ms 状态切换（颜色、透明度）
 *      BASE    200ms 小元素进出（气泡、提示条）
 *      SLOW    320ms 结构性移动（页面切换、列表级联进场）
 *      LONG    520ms 主题性动效（掉血反馈全过程）
 *
 *   2. <b>easing 语汇</b>，方向决定曲线：
 *      进入 → {@link #enter()}（快起慢收）
 *      离开 → {@link #exit()}（慢起快收）
 *      位移 → {@link #standard()}（cubic-bezier 0.4, 0, 0.2, 1）
 *      撞击 → {@link #emphasis()}（允许一次轻微过冲）
 *      轻柔 → {@link #gentle()}（用于点击反馈，平和不弹）
 *
 * 另：所有动画只作用于 opacity / transform，不动画 layout 属性。
 */
public final class Motion {

    public static final long INSTANT = 75L;
    public static final long FAST = 120L;
    public static final long BASE = 200L;
    public static final long SLOW = 320L;
    public static final long LONG = 520L;

    private Motion() { }

    /** 进入：快起慢收 */
    public static Interpolator enter() {
        return new DecelerateInterpolator(1.6f);
    }

    /** 离开：慢起快收 */
    public static Interpolator exit() {
        return new AccelerateInterpolator(1.6f);
    }

    /** 位移：cubic-bezier(0.4, 0, 0.2, 1) */
    public static Interpolator standard() {
        return new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    }

    /** 撞击：cubic-bezier(0.34, 1.26, 0.64, 1)，过冲幅度很小 */
    public static Interpolator emphasis() {
        return new PathInterpolator(0.34f, 1.26f, 0.64f, 1f);
    }

    /** 轻柔：cubic-bezier(0.22, 0.61, 0.36, 1)，无过冲，适合点击反馈 */
    public static Interpolator gentle() {
        return new PathInterpolator(0.22f, 0.61f, 0.36f, 1f);
    }

    /**
     * 冲击：标准减速曲线，专供「掉血反馈」。
     * 这一档刻意保留 2.0 的手感 —— 掉血要的就是那一下顿挫，不做柔和化处理。
     */
    public static Interpolator impact() {
        return new DecelerateInterpolator();
    }
}
