package com.danmaku.randcolor;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

/**
 * 首页顶部标签行（推荐 / 关注 / 同城 …）的两层液态玻璃：整行一层、每个标签各一层，画法和文字底条的玻璃是同一套（LineBg.drawGlass，同一份缓存）。
 *
 * 依据（TextRec 追踪，40.7.0，textrec-tasks-20261019-202639，见 TabHook）：标签行是 MainTabStripNew > MainTabStripScrollView > MainTabRelativeLayout，
 * 里面每个标签是 HomeTopTabItemViewNew（类名没混淆）；每个标签都会被调用 View.setContentDescription（页面切换、刷新时会重设）。
 * 所以挂靠的是 TabHook 已有的 View.setContentDescription 钩子：回调里拿到的标签控件，在这里挂一次背景 Drawable，**没有新钩子、没有 onDraw 钩子、没有定时器、没有扫描**。
 *  - 每个标签：给标签控件自己设一个背景 Drawable（ItemGlass），画在标签框里（四周缩进留出柔边）。
 *  - 整行：沿父级向上数 3 层，按简单类名认出 MainTabRelativeLayout / MainTabStripScrollView / MainTabStripNew，给最外层的 MainTabStripNew 设背景 Drawable（RowGlass）；
 *    它每次绘制时把「当前看得见的标签」的外框并起来画一条，横向划动标签行时跟着走。
 *  - 标签文字的投影：标签控件里（自己或往下 3 层内）所有 TextView 设同一个黑色投影（不透明度 / 半径 / 下移在设置页调）。
 * 抖音自己已经给这个控件设了背景时不覆盖（背景非空就什么都不做）；类名对不上、层级对不上都安静不画，不换别的办法。
 *
 * 局限（没编译、没上机）：① 设置只在下一次 setContentDescription（切页 / 刷新 / 重启抖音）时才挂上或刷新文字投影；已经挂上的玻璃每次绘制都读当前设置，所以不透明度 / 底色改了马上跟着变，0% 就什么都不画；
 * ② 标签控件和标签行的实际大小没有追踪数据，缩进量（标签玻璃左右 4dp、上下 5dp；整行两侧各多 10dp、上下各多 3dp）是按截图估的，上机后可能要调；③ 整行玻璃超出标签行框的部分靠关掉 MainTabStripNew 往上 8 层父容器的 clipChildren / clipToPadding 才画得出来（同 LineBg.unclip），被屏幕边缘挡住的部分看不到；④ 「选中」靠标签的无障碍描述里有「已选中」认（TextRec：「推荐，已选中，按钮」），描述格式变了就都不亮。
 */
final class TabGlass {
    private TabGlass() {}

    private static final int TAG = 0x7e0b0c07;
    private static final String ROW_CLS = "MainTabRelativeLayout", SCROLL_CLS = "MainTabStripScrollView", STRIP_CLS = "MainTabStripNew";

    /** TabHook 的 setContentDescription 回调里调：item 已经确认是 HomeTopTabItemViewNew；selected = 描述里有「已选中」（TextRec：「推荐，已选中，按钮」）。 */
    static void attach(View item, boolean selected) {
        DanmakuConfig c = DanmakuHook.cfg;
        // 文字的投影：每次回调都按当前设置刷新（LineBg.shadow 内部只在变了才重设）
        if (c.tabShadow > 0) shadowAll(item, c, 0); // 0% = 不碰抖音自己的字投影
        if (c.tabRowBg <= 0 && c.tabItemBg <= 0) return;
        if (item.getTag(TAG) == null && item.getBackground() == null) {
            item.setTag(TAG, Boolean.TRUE);
            item.setBackground(new ItemGlass());
        }
        Drawable bg = item.getBackground();
        if (bg instanceof ItemGlass && ((ItemGlass) bg).selected != selected) { // 选中的标签玻璃更亮
            ((ItemGlass) bg).selected = selected;
            bg.invalidateSelf();
        }
        ViewParent p1 = item.getParent();
        if (!(p1 instanceof ViewGroup) || !ROW_CLS.equals(p1.getClass().getSimpleName())) return;
        ViewParent p2 = p1.getParent();
        if (!(p2 instanceof ViewGroup) || !SCROLL_CLS.equals(p2.getClass().getSimpleName())) return;
        ViewParent p3 = p2.getParent();
        if (!(p3 instanceof ViewGroup) || !STRIP_CLS.equals(p3.getClass().getSimpleName())) return;
        ViewGroup strip = (ViewGroup) p3;
        if (strip.getTag(TAG) == null && strip.getBackground() == null) {
            strip.setTag(TAG, Boolean.TRUE);
            strip.setBackground(new RowGlass((ViewGroup) p1, (ViewGroup) p2));
            LineBg.unclip(strip); // 整行的玻璃比标签行大一圈（含柔边），关掉上面几层的裁剪才画得完整
        }
    }

    /** v 自己和往下 3 层里的所有 TextView 设标签文字的投影。 */
    private static void shadowAll(View v, DanmakuConfig c, int depth) {
        if (v instanceof TextView) LineBg.shadow((TextView) v, c.tabShadow, c.tabShadowR, c.tabShadowDy);
        if (depth >= 3 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0, n = g.getChildCount(); i < n; i++) shadowAll(g.getChildAt(i), c, depth + 1);
    }

    private static int dp(float v) {
        return Math.round(dpf(v));
    }

    private static float dpf(float v) {
        return v * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG); // 主线程绘制，复用
    private static final RectF BX = new RectF();
    private static final Path PT = new Path();

    private abstract static class Glass extends Drawable {
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 每个标签的玻璃（设置 tabItemBg / tabItemColor），要和整行那层分开：
     *  - 比标签框缩进得多（左右 4dp、上下 5dp 再加柔边），相邻标签之间留出缝，缝里透出整行的玻璃；
     *  - 玻璃下面有一道淡黑色的投影（往下偏 1.5dp、向外 3 层逐层变淡；只画在玻璃外面，不压暗玻璃本身；不用 BlurMaskFilter，硬件绘制下画不出来），像浮在整行玻璃上面；
     *  - 选中的标签（描述里有「已选中」）再叠一层白色填充和一圈白色细边，比别的标签亮。
     */
    private static final class ItemGlass extends Glass {
        boolean selected;

        @Override
        public void draw(Canvas cv) {
            try {
                DanmakuConfig c = DanmakuHook.cfg;
                if (c.tabItemBg <= 0) return;
                Rect bd = getBounds();
                int m = LineBg.softPx();
                float l = bd.left + m + dpf(4), t = bd.top + m + dpf(5), r = bd.right - m - dpf(4), b = bd.bottom - m - dpf(5);
                if (r <= l || b <= t) return;
                float f = Math.min(100, c.tabItemBg) / 100f, rad = (b - t) / 2f;
                // 投影：只画在玻璃形状外面
                PT.rewind();
                PT.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW);
                cv.save();
                cv.clipOutPath(PT);
                P.setStyle(Paint.Style.FILL);
                float dy = dpf(1.5f);
                for (int k = 3; k >= 1; k--) {
                    float e = dpf(k);
                    BX.set(l - e, t - e + dy, r + e, b + e + dy);
                    P.setColor(Math.round(255f * f * (k == 1 ? 0.16f : k == 2 ? 0.09f : 0.05f)) << 24);
                    cv.drawRoundRect(BX, (b - t) / 2f + e, (b - t) / 2f + e, P);
                }
                cv.restore();
                LineBg.drawGlass(cv, l, t, r, b, c.tabItemBg, c.tabItemColor);
                if (selected) { // 选中：白色填充 + 白色细边
                    BX.set(l, t, r, b);
                    P.setColor(0xFFFFFF | Math.round(255f * f * 0.20f) << 24);
                    cv.drawRoundRect(BX, rad, rad, P);
                    float sw = Math.max(1f, dpf(1f));
                    BX.inset(sw / 2f, sw / 2f);
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(sw);
                    P.setColor(0xFFFFFF | Math.round(255f * f * 0.70f) << 24);
                    cv.drawRoundRect(BX, rad - sw / 2f, rad - sw / 2f, P);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 整行的玻璃：把当前看得见的标签（HomeTopTabItemViewNew）的外框并起来，两侧各多 10dp、上下各多 3dp（比标签的玻璃大一圈，不再限制在标签行框内，靠 attach 里关掉上面几层的裁剪），设置 tabRowBg / tabRowColor。 */
    private static final class RowGlass extends Glass {
        private final ViewGroup row, scroll;

        RowGlass(ViewGroup row, ViewGroup scroll) {
            this.row = row;
            this.scroll = scroll;
        }

        @Override
        public void draw(Canvas cv) {
            try {
                DanmakuConfig c = DanmakuHook.cfg;
                if (c.tabRowBg <= 0) return;
                Class<?> k = TabHook.itemClass();
                if (k == null) return;
                int sl = scroll.getLeft(), sr = scroll.getRight(), sx = scroll.getScrollX();
                int ox = row.getLeft() - sx + sl, oy = row.getTop() + scroll.getTop();
                float L = Float.MAX_VALUE, T = Float.MAX_VALUE, R = -Float.MAX_VALUE, B = -Float.MAX_VALUE;
                for (int i = 0, n = row.getChildCount(); i < n; i++) {
                    View v = row.getChildAt(i);
                    if (!k.isInstance(v) || v.getVisibility() != View.VISIBLE) continue;
                    float x1 = Math.max(v.getLeft() + ox, sl), x2 = Math.min(v.getRight() + ox, sr);
                    if (x2 <= x1) continue; // 划出看得见的范围了
                    L = Math.min(L, x1);
                    R = Math.max(R, x2);
                    T = Math.min(T, v.getTop() + oy);
                    B = Math.max(B, v.getBottom() + oy);
                }
                if (R <= L || B <= T) return;
                L -= dpf(10);
                R += dpf(10);
                T -= dpf(3);
                B += dpf(3);
                LineBg.drawGlass(cv, L, T, R, B, c.tabRowBg, c.tabRowColor);
            } catch (Throwable ignored) {
            }
        }
    }
}
