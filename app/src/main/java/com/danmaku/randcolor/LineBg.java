package com.danmaku.randcolor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

/**
 * 文字底条（1.25.0 起是灰色底，1.42.9～1.42.16 试过液态玻璃，1.42.17 起改成和音量浮窗「当前音量」那块一样的底）：视频文案、视频时长、发布日期地点（1.29.0 起还有弹幕、直播间开播时间），每一行字后面画一条两端半圆的底，透明度 lineBg（0～100%）在设置页调，0 = 不画。
 * 样子 = VolPanel.Val 的底：半透明白色填充（0x4D，即 30%）+ 1 个字号档宽的白色描边（0xB3，即 70%）；lineBg = 100% 时和音量块一模一样，其余按比例变淡。
 * 填充颜色默认白色（和音量块的底一样），设置页选了底色（cfg.lineBgColor）就用选的；描边始终是白色。
 * 底 = 这一行的文字框向四周各放大 1sp（宽、高都比字大一个字号档），左右两端再各加长 13px，两端是半圆（圆角半径 = 底高的一半）；多行时每行一粒独立的底，行与行之间留 0.5sp 的缝。
 * 每个底每次绘制只有两次 drawRoundRect（填充 + 描边），没有 clip、没有渐变、没有位图缓存。
 *
 * 画在文字之前：自己的两行（发布信息、时长）用 {@link Row}（TextView 子类，onDraw 先画底再 super）；
 * 抖音的文案控件在 PubHook.DrawHook（MentionTextView#onDraw 之前）里调 {@link #drawBars}；
 * 直播间开播时间也是 {@link Row}；弹幕（Compose 画的，没有 TextView）在 DanmakuHook 里按每条弹幕的文字框调 {@link #drawBox}。
 * 绘制路径里不分配对象：Paint / RectF 是静态复用的（都在主线程绘制，不重入）。
 */
final class LineBg {
    private LineBg() {}

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF BOX = new RectF();
    /** 底的填充 / 描边的最大不透明度（lineBg = 100% 时）：和 VolPanel.Val 的 track（0x4D）/ STROKE（0xB3）一样。 */
    private static final int FILL_A = 0x4D, EDGE_A = 0xB3;
    /** 底的默认填充色：白色（和音量块的底一样）。用户在设置页选了底色（cfg.lineBgColor）就用用户的，见 drawPill。 */
    private static final int WHITE = 0xFFFFFF;

    /**
     * 以后新增的、由模块自己决定颜色的文字（像随机色的弹幕）：颜色过一遍这里，太暗的（纯蓝、紫、深红等）往白色方向提亮，
     * 直到相对亮度（gamma 2 近似）达到 minLum（0～1，设置页「字的最低亮度」的百分比 / 100）；够亮的原样返回，minLum <= 0 不提亮。
     * 底是半透明的（1.42.17 起默认是淡白色），亮暗随画面变，所以这个值交给用户自己调。用户自己选的颜色（文案、时长、开播时间）不改。只处理 0xRRGGBB，不分配对象。
     */
    static int legible(int rgb, float minLum) {
        if (minLum <= 0f) return rgb;
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float t = 0f;
        while (t < 1f && 0.2126f * sq(r + (1f - r) * t) + 0.7152f * sq(g + (1f - g) * t) + 0.0722f * sq(b + (1f - b) * t) < minLum) t += 0.05f;
        if (t <= 0f) return rgb;
        if (t > 1f) t = 1f;
        return (Math.round((r + (1f - r) * t) * 255f) << 16) | (Math.round((g + (1f - g) * t) * 255f) << 8) | Math.round((b + (1f - b) * t) * 255f);
    }

    private static float sq(float x) {
        return x * x; // 近似 sRGB → 线性（gamma 2）
    }

    /** 底比字多出的边：1sp。 */
    private static final float BAR_SP = 1f;
    /** 底左右两端再各加长的像素（物理像素，不随字号变）。 */
    private static final float END_PX = 13f;

    /** 底上面的字的投影：和 VolPanel.Val「当前音量」的字一样（半径 2dp、下移 1dp、0xAA 黑）。 */
    static void shadow(TextView t) {
        float den = t.getResources().getDisplayMetrics().density;
        t.setShadowLayer((int) (2f * den + 0.5f), 0f, (int) (den + 0.5f), 0xAA000000);
    }

    /** 自己放的两行的内边距：3sp，给底和它的描边留地方（底只比字多 1sp）。PubHook 用它抵消行距 / 左边距。 */
    static int pad(Context c) {
        return Math.round(3f * c.getResources().getDisplayMetrics().scaledDensity);
    }

    /** 左右内边距：上下的内边距再加底两端加长的 13px，底的柔边才不会被控件边界裁掉。PubHook 对齐文案左边时也用它。 */
    static int padX(Context c) {
        return pad(c) + Math.round(END_PX);
    }

    /** 在 cv 上给 t 的每一行文字画底（每一行一粒独立的底，行与行之间留 0.5sp 的缝）。t 还没排好版（没有 Layout）或 pct <= 0 就什么都不画。 */
    static void drawBars(Canvas cv, TextView t, int pct) {
        if (pct <= 0 || cv == null) return;
        Layout l = t.getLayout();
        if (l == null) return;
        int n = l.getLineCount();
        if (n <= 0) return;
        float sd = t.getResources().getDisplayMetrics().scaledDensity;
        float pad = BAR_SP * sd;
        float ox = t.getTotalPaddingLeft(), oy = t.getTotalPaddingTop();
        for (int i = 0; i < n; i++) {
            float lf = l.getLineLeft(i), rt = l.getLineRight(i);
            if (rt <= lf) continue; // 空行不画
            float top = l.getLineTop(i), bot = l.getLineBottom(i), padT = pad, padB = pad;
            // 多行：和上 / 下一行之间留 0.5sp 的缝，每行是各自独立的一粒胶囊，不叠在一起（单行或没有相邻行时上下仍多 1sp）
            if (i > 0) padT = Math.min(pad, (top - l.getLineBottom(i - 1)) / 2f - 0.5f * sd);
            if (i < n - 1) padB = Math.min(pad, (l.getLineTop(i + 1) - bot) / 2f - 0.5f * sd);
            drawPill(cv, ox + lf - pad - END_PX, oy + top - padT, ox + rt + pad + END_PX, oy + bot + padB, sd, pct);
        }
    }

    /** 在 cv 上给一个文字框（左上右下，canvas 像素，不含底多出的边）画底，形状和 {@link #drawBars} 一样。弹幕用。 */
    static void drawBox(Canvas cv, float l, float t, float r, float b, float sd, int pct) {
        if (pct <= 0 || cv == null || r <= l || b <= t) return;
        float pad = BAR_SP * sd;
        drawPill(cv, l - pad - END_PX, t - pad, r + pad + END_PX, b + pad, sd, pct);
    }

    /** 画一条两端半圆的底（l,t,r,b = 底的外框）：半透明填充 + 1dp 的白色描边，和音量块的底一样；描边向里画，外框大小不变。 */
    private static void drawPill(Canvas cv, float l, float t, float r, float b, float sd, int pct) {
        float f = Math.min(100, pct) / 100f;
        int fillA = Math.round(FILL_A * f), edgeA = Math.round(EDGE_A * f);
        int tint = DanmakuHook.cfg.lineBgColor == 0 ? WHITE : (DanmakuHook.cfg.lineBgColor & 0xFFFFFF); // 用户选的底色；0 = 默认白
        float den = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        float sw = (int) (den + 0.5f); // 描边 = 1dp 取整到整数像素，和 VolPanel.Val 的 dp(ctx, 1) 一样
        float half = sw / 2f;
        BOX.set(l + half, t + half, r - half, b - half);
        if (BOX.right <= BOX.left || BOX.bottom <= BOX.top) return;
        float rad = Math.min((int) (12f * den + 0.5f), BOX.height() / 2f); // 圆角 = 12dp（VolPanel.OUT_R），高不够时取高的一半
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(tint | fillA << 24);
        cv.drawRoundRect(BOX, rad, rad, PAINT);
        PAINT.setStyle(Paint.Style.STROKE);
        PAINT.setStrokeWidth(sw);
        PAINT.setColor(WHITE | edgeA << 24);
        cv.drawRoundRect(BOX, rad, rad, PAINT);
    }

    /** 自己放的两行（发布信息、时长）用的 TextView：先画底再画字；上下留出 3sp、左右留出 3sp + 13px 的内边距，底才不会被控件边界裁掉。 */
    static final class Row extends TextView {
        /** 视频时长那一行：显示 / 隐藏 / 挂上 / 摘下时告诉音量浮窗（VolPanel「跟随显示」）；别的行不通知。 */
        boolean follow;

        Row(Context c) {
            super(c);
            int p = pad(c), px = padX(c);
            setPadding(px, p, px, p);
            setIncludeFontPadding(false); // 行高 = 字本身的行高，和文案的行距一样
            shadow(this); // 字的投影和音量块一样
        }

        // 自己或任何一层父控件显示 / 隐藏都会走到这里（View 自带的分发），不用轮询；isShown 只沿父链往上数，不扫控件
        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (follow) VolPanel.source(this, isShown());
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (follow) VolPanel.source(this, isShown());
        }

        @Override
        protected void onDetachedFromWindow() {
            if (follow) VolPanel.source(this, false); // 摘下时父链还在，isShown 会误判成真，这里直接给假
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            try {
                drawBars(canvas, this, DanmakuHook.cfg.lineBg);
            } catch (Throwable ignored) {
            }
            super.onDraw(canvas);
        }
    }
}
