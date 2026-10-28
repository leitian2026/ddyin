package com.danmaku.randcolor;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

/**
 * 首页顶部标签行（推荐 / 关注 / 同城 …）的两层液态玻璃：整行一层、每个标签各一层，画法是比文字底条更立体的一套（见 glass()：更透的底、高光渐变、弧形反光、边缘光、色散、细轮廓），只给标签用，文字底条的玻璃没动。
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
 * ② 标签控件和标签行的实际大小没有追踪数据，默认大小（标签玻璃：高 = 标签框高的 60%，缝 6dp；整行：比标签玻璃上下各多 6dp、左右多 7dp）是按截图估的，设置页都能调，上机后可能要调；③ 整行玻璃超出标签行框的部分靠关掉 MainTabStripNew 往上 8 层父容器的 clipChildren / clipToPadding 才画得出来（同 LineBg.unclip），被屏幕边缘挡住的部分看不到；④ 「选中」靠标签的无障碍描述里有「已选中」认（TextRec：「推荐，已选中，按钮」），描述格式变了就都不亮。
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

    // ---- 更立体的玻璃（只给顶部标签用；文字底条的玻璃没动）。画在 Canvas 上，没有真正的背景模糊；标签就几个，每次绘制现画，不缓存。
    /** 竖向高光（单位渐变：0 = 上沿，1 = 下沿，画之前缩放到框高）：上亮、中间透、底部再亮一点（折射回来的光）。颜色里的透明度是相对值，实际强度 = 它 × 不透明度 × 层级。 */
    private static final LinearGradient SHEEN = new LinearGradient(0f, 0f, 0f, 1f,
            new int[]{0x8CFFFFFF, 0x1AFFFFFF, 0x0DFFFFFF, 0x4DFFFFFF}, new float[]{0f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP);
    /** 上半部那块弧形反光：上面亮、往下渐隐。 */
    private static final LinearGradient GLOSS = new LinearGradient(0f, 0f, 0f, 1f,
            new int[]{0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
    private static final Matrix MX = new Matrix();
    private static final int DEF_COLOR = 0x7C7E84; // 和文字底条一样：偏白的中浅灰

    /** 在 PT（框的圆角形状）里面画一道亮 / 暗边：把形状错开 (dx, dy) 再抠掉，剩下错开方向反侧的一道月牙。 */
    private static void rim(Canvas cv, float dx, float dy, int argb, RectF box) {
        cv.save();
        cv.clipPath(PT);
        PT.offset(dx, dy);
        cv.clipOutPath(PT);
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(argb);
        cv.drawRect(box, P);
        cv.restore();
        PT.offset(-dx, -dy);
    }

    private static int argb(int rgb, float a) {
        return rgb | Math.round(255f * Math.max(0f, Math.min(1f, a))) << 24;
    }

    /**
     * 一块更立体的液态玻璃（l,t,r,b = 玻璃本身的框；两端半圆）。f = 不透明度（0～1，设置页的百分比），lvl = 层级强度（标签 1，整行 0.75），color = 底色（0 = 默认中浅灰），selected = 再亮一档。
     * 叠法（自下而上）：半透明底色（比文字底条的玻璃更透，让后面的画面透出来）→ 竖向高光渐变 → 上半部弧形反光 → 边缘光：左上亮边、右侧一道暗边、下沿折射回来的亮弧、
     * 左上偏青 / 右下偏暖的色散细边 → 一圈 1dp 的白色细轮廓。边缘光都是把形状错开一点再抠出来的月牙，两端自然变细。
     */
    private static void glass(Canvas cv, float l, float t, float r, float b, float f, float lvl, int color, boolean selected) {
        float h = b - t, rad = h / 2f, k = f * lvl, d = dpf(1f);
        int col = color == 0 ? DEF_COLOR : (color & 0xFFFFFF);
        BX.set(l, t, r, b);
        PT.rewind();
        PT.addRoundRect(BX, rad, rad, Path.Direction.CW);
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(argb(col, 0.38f * f)); // 底色：不随层级变淡，层级只管高光
        cv.drawRoundRect(BX, rad, rad, P);
        if (selected) {
            P.setColor(argb(0xFFFFFF, 0.20f * k));
            cv.drawRoundRect(BX, rad, rad, P);
        }
        // 竖向高光
        MX.setScale(1f, h);
        MX.postTranslate(0f, t);
        SHEEN.setLocalMatrix(MX);
        P.setShader(SHEEN);
        P.setColor(0xFFFFFFFF);
        P.setAlpha(Math.round(255f * Math.min(1f, k)));
        cv.drawRoundRect(BX, rad, rad, P);
        // 上半部弧形反光：两端各缩进 0.3 倍高，占上半部
        float gh = h * 0.38f;
        BX.set(l + h * 0.30f, t + h * 0.08f, r - h * 0.30f, t + h * 0.08f + gh);
        if (BX.right > BX.left) {
            MX.setScale(1f, gh);
            MX.postTranslate(0f, BX.top);
            GLOSS.setLocalMatrix(MX);
            P.setShader(GLOSS);
            P.setColor(0xFFFFFFFF);
            P.setAlpha(Math.round(255f * Math.min(1f, 0.9f * k)));
            cv.drawRoundRect(BX, gh / 2f, gh / 2f, P);
        }
        P.setShader(null);
        // 边缘光
        BX.set(l, t, r, b);
        rim(cv, 1.3f * d, 1.3f * d, argb(0xFFFFFF, 0.80f * k), BX); // 左上亮边
        rim(cv, 0f, -1.8f * d, argb(0xFFFFFF, 0.38f * k), BX); // 下沿折射回来的亮弧
        rim(cv, -1.2f * d, 0f, argb(0x000000, 0.22f * f), BX); // 右侧暗边（让玻璃有厚度）
        rim(cv, d, d, argb(0x80E0FF, 0.35f * k), BX); // 色散：左上偏青
        rim(cv, -d, -d, argb(0xFFB070, 0.35f * k), BX); // 色散：右下偏暖
        // 1dp 白色细轮廓（向里画）
        float sw = Math.max(1f, d);
        BX.inset(sw / 2f, sw / 2f);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(sw);
        P.setColor(argb(0xFFFFFF, (selected ? 0.90f : 0.45f) * k));
        cv.drawRoundRect(BX, rad - sw / 2f, rad - sw / 2f, P);
        P.setStyle(Paint.Style.FILL);
    }

    /**
     * 每个标签的玻璃（设置 tabItemBg / tabItemColor）：一条小长条，和相邻的标签分开：
     *  - 大小在设置页调：高 = 标签框高 × tabItemH（%，上下居中）；相邻两条之间的缝 = tabGap（0.5dp 一档），宽 = 标签框宽 − 缝（标签是抖音一个挨一个排的，宽和缝是同一个自由度）；
     *  - 玻璃下面有一道淡黑色投影（往下偏 1.5dp、向外 3 层逐层变淡；只画在玻璃外面，不压暗玻璃本身；不用 BlurMaskFilter，硬件绘制下画不出来）；
     *  - 选中的标签（描述里有「已选中」）再亮一档。
     */
    private static final class ItemGlass extends Glass {
        boolean selected;

        @Override
        public void draw(Canvas cv) {
            try {
                DanmakuConfig c = DanmakuHook.cfg;
                if (c.tabItemBg <= 0) return;
                Rect bd = getBounds();
                float gh = bd.height() * c.tabItemH / 100f, cy = bd.exactCenterY(), gap = c.tabGap * dpf(0.5f) / 2f; // 缝的一半
                float l = bd.left + gap, r = bd.right - gap, t = cy - gh / 2f, b = cy + gh / 2f;
                if (r <= l || b <= t) return;
                float f = Math.min(100, c.tabItemBg) / 100f, rad = (b - t) / 2f;
                // 投影：只画在玻璃形状外面
                PT.rewind();
                PT.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW);
                cv.save();
                cv.clipOutPath(PT);
                P.setShader(null);
                P.setStyle(Paint.Style.FILL);
                float dy = dpf(1.5f);
                for (int k = 3; k >= 1; k--) {
                    float e = dpf(k);
                    BX.set(l - e, t - e + dy, r + e, b + e + dy);
                    P.setColor(Math.round(255f * f * (k == 1 ? 0.16f : k == 2 ? 0.09f : 0.05f)) << 24);
                    cv.drawRoundRect(BX, rad + e, rad + e, P);
                }
                cv.restore();
                glass(cv, l, t, r, b, f, 1f, c.tabItemColor, selected);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 整行的玻璃（设置 tabRowBg / tabRowColor）：把当前看得见的标签（HomeTopTabItemViewNew）的外框并起来，
     * 高 = 标签玻璃的高 + 上下各 tabRowPadY，左右比最外面的标签玻璃再多 tabRowPadX（0.5dp 一档；不限制在标签行框内，靠 attach 里关掉上面几层的裁剪）；层级强度 0.75，比标签的玻璃弱一点，托住它们。
     */
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
                float cy = (T + B) / 2f, half = (B - T) * c.tabItemH / 100f / 2f + c.tabRowPadY * dpf(0.5f);
                float gap = c.tabGap * dpf(0.5f) / 2f, px = c.tabRowPadX * dpf(0.5f); // 最外面的标签玻璃的外沿 = 标签框外沿往里缩缝的一半
                glass(cv, L + gap - px, cy - half, R - gap + px, cy + half, Math.min(100, c.tabRowBg) / 100f, 0.75f, c.tabRowColor, false);
            } catch (Throwable ignored) {
            }
        }
    }
}
