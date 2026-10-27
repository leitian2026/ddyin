package com.danmaku.randcolor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

/**
 * 文字液态玻璃底（1.25.0 起是灰色底，1.42.9 起改成液态玻璃）：视频文案、视频时长、发布日期地点（1.29.0 起还有弹幕、直播间开播时间），每一行字后面画一条半透明的玻璃底（类名里的「灰色」是旧叫法），透明度 lineBg（0～100%）在设置页调，0 = 不画。
 * 玻璃的画法（都是 Canvas 画的，没有真正的背景模糊）：半透明的底色（用户选的底色，默认偏白的中浅灰）→ 整块一层竖向的白色高光渐变（上亮、中间透、底部再微亮）→ 整块一层淡白的磨砂；沿边缘一圈由外向内渐淡的内发光（模拟边缘折射光）；左上偏青、右下偏暖的色散细边；再叠一道很淡的顶部高光（都是把底的形状错开一点再用 clip 抠出来的月牙形，两端自然变细）。外面那圈柔边照旧。高光 / 亮边的强度都跟 lineBg 走，lineBg = 0 什么都不画。
 * 底 = 这一行的文字框向四周各放大 1sp（宽、高都比字大一个字号档），左右两端再各加长 13px（1.42.5 加 2px，1.42.6 起 8px，1.42.7 起 10px，1.42.8 起 13px），两端是半圆（圆角半径 = 底高的一半）；多行时每行一粒独立的胶囊（1.42.12 起不再合成并集；1.42.13 起行与行之间留 0.5sp 的缝，不再重叠）；玻璃画一次缓存成位图，见 drawPill。
 *
 * 画在文字之前：自己的两行（发布信息、时长）用 {@link Row}（TextView 子类，onDraw 先画底再 super）；
 * 抖音的文案控件在 PubHook.DrawHook（MentionTextView#onDraw 之前）里调 {@link #drawBars}；
 * 直播间开播时间也是 {@link Row}；弹幕（Compose 画的，没有 TextView）在 DanmakuHook 里按每条弹幕的文字框调 {@link #drawBox}。
 * 绘制路径里不分配对象：Path / Paint 是静态复用的（都在主线程绘制，不重入）。
 */
final class LineBg {
    private LineBg() {}

    private static final Path PATH = new Path();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 底的默认颜色：偏白的中浅灰（1.42.10 起，原来是近黑 #101010）。用户在设置页选了底色（cfg.lineBgColor）就用用户的，见 drawPill。 */
    private static final int GRAY = 0x007C7E84;

    /** 玻璃高光（竖向，单位渐变：0 = 底的上沿，1 = 下沿，画之前用 MAT 缩放 / 平移到底的包围盒）。颜色里的透明度是相对值，实际强度 = 它 × lineBg。 */
    private static final LinearGradient SHEEN = new LinearGradient(0f, 0f, 0f, 1f,
            new int[]{0x66FFFFFF, 0x3CFFFFFF, 0x24FFFFFF, 0x30FFFFFF}, new float[]{0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP);
    private static final Matrix MAT = new Matrix();
    /**
     * 省电（1.42.12）：玻璃（约 12 次 clip + 渐变）只在缓存没命中时画一次到一张小位图里，之后每个底只是 3 次 drawBitmap（左端、右端、中间拉伸）。
     * 缓存按「底高、底色、不透明度」认，16 个槽轮流替换；设置改了或字号变了才会重画。命中时绘制路径里不分配；没命中时才新建一张位图。
     */
    private static final int CACHE_N = 16;
    private static final Bitmap[] C_BMP = new Bitmap[CACHE_N];
    private static final int[] C_H = new int[CACHE_N], C_COL = new int[CACHE_N], C_A = new int[CACHE_N];
    private static int cNext, cLast; // cLast：上一次命中的槽，同一字号的弹幕连着画时直接命中，不用扫一遍
    private static final Canvas OFF = new Canvas();
    private static final Rect SRC = new Rect();
    private static final RectF DST = new RectF();
    private static final Paint BMP = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** 位图里底中间那一段不随长度变的平直部分（px），左右端半圆各占 h/2。 */
    private static final int FLAT_PX = 8;
    private static final RectF BOX = new RectF();
    /** 顶部高光的厚度（sp，最厚处；两端变细）。 */
    private static final float RIM_TOP_SP = 1.6f;
    /** 边缘内发光（模拟玻璃边缘的折射光）：由外向内两层，厚度（sp）和强度（相对 lineBg 的比例）；上、下、左、右四个方向各抠一道月牙，合起来是一圈越往里越淡的亮带。 */
    private static final float GLOW1_SP = 0.7f, GLOW2_SP = 1.8f, GLOW3_SP = 3.6f;
    /** 色散：左上沿偏青、右下沿偏暖（相对 lineBg 的透明度 / 255），厚度 DISP_SP。 */
    private static final float DISP_SP = 1.4f;

    /**
     * 以后新增的、由模块自己决定颜色的文字（像随机色的弹幕）：颜色过一遍这里，太暗的（纯蓝、紫、深红等）往白色方向提亮，
     * 直到相对亮度（gamma 2 近似）达到 minLum（0～1，设置页「字的最低亮度」的百分比 / 100）；够亮的原样返回，minLum <= 0 不提亮。
     * 底是半透明的黑、叠在亮一点的画面上，实际比纯黑亮，所以这个值交给用户自己调。用户自己选的颜色（文案、时长、开播时间）不改。只处理 0xRRGGBB，不分配对象。
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

    /** 底比字多出的边：1sp。柔边向外再延伸 1.5sp（分 3 层逐层变淡）。 */
    private static final float BAR_SP = 1f, SOFT_SP = 1.5f;
    /** 底左右两端再各加长的像素（物理像素，不随字号变）。 */
    private static final float END_PX = 13f;

    /** 自己放的两行的内边距：3sp，给底和它的模糊边留地方（底只比字多 1sp，模糊边再往外一点）。PubHook 用它抵消行距 / 左边距。 */
    static int pad(Context c) {
        return Math.round(3f * c.getResources().getDisplayMetrics().scaledDensity);
    }

    /** 左右内边距：上下的内边距再加底两端加长的 13px，底的柔边才不会被控件边界裁掉。PubHook 对齐文案左边时也用它。 */
    static int padX(Context c) {
        return pad(c) + Math.round(END_PX);
    }

    /** 在 cv 上给 t 的每一行文字画底（每一行一粒胶囊玻璃，行与行之间留 0.5sp 的缝）。t 还没排好版（没有 Layout）或 pct <= 0 就什么都不画。 */
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

    /** 画一条两端半圆的玻璃（l,t,r,b = 底本身的框，不含柔边）：取缓存位图，左端 / 右端原样、中间拉伸。 */
    private static void drawPill(Canvas cv, float l, float t, float r, float b, float sd, int pct) {
        int a = Math.min(100, pct) * 255 / 100;
        int gray = DanmakuHook.cfg.lineBgColor == 0 ? GRAY : (DanmakuHook.cfg.lineBgColor & 0xFFFFFF); // 用户选的底色；0 = 默认
        int h = Math.max(2, Math.round(b - t));
        int m = (int) Math.ceil(SOFT_SP * sd); // 柔边占的位图边距
        int idx = -1;
        int q = cLast;
        if (C_BMP[q] != null && C_H[q] == h && C_COL[q] == gray && C_A[q] == a) {
            idx = q;
        } else {
            for (int i = 0; i < CACHE_N; i++) {
                if (C_BMP[i] != null && C_H[i] == h && C_COL[i] == gray && C_A[i] == a) {
                    idx = i;
                    break;
                }
            }
        }
        int bw = h + FLAT_PX + 2 * m, bh = h + 2 * m;
        if (idx < 0) { // 没命中：画一次进位图，占一个槽
            idx = cNext;
            cNext = (cNext + 1) % CACHE_N;
            Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            OFF.setBitmap(bm);
            PATH.rewind();
            float rad = h / 2f;
            PATH.addRoundRect(m, m, m + h + FLAT_PX, m + h, rad, rad, Path.Direction.CW);
            paintGlass(OFF, sd, a, gray);
            OFF.setBitmap(null);
            C_BMP[idx] = bm;
            C_H[idx] = h;
            C_COL[idx] = gray;
            C_A[idx] = a;
        }
        cLast = idx;
        Bitmap bm = C_BMP[idx];
        int cap = m + (h + 1) / 2; // 一端：柔边 + 半圆
        float L = l - m, T = t - m, R = r + m, B = b + m;
        float capD = Math.min(cap, (R - L) / 2f);
        SRC.set(0, 0, cap, bh);
        DST.set(L, T, L + capD, B);
        cv.drawBitmap(bm, SRC, DST, BMP);
        SRC.set(bw - cap, 0, bw, bh);
        DST.set(R - capD, T, R, B);
        cv.drawBitmap(bm, SRC, DST, BMP);
        if (R - capD > L + capD) {
            SRC.set(bw / 2 - 1, 0, bw / 2 + 1, bh);
            DST.set(L + capD, T, R - capD, B);
            cv.drawBitmap(bm, SRC, DST, BMP);
        }
    }

    /** 把 PATH 画成带柔边的液态玻璃底（画进缓存位图，a = 不透明度 0～255，gray = 0xRRGGBB 底色）。 */
    private static void paintGlass(Canvas cv, float sd, int a, int gray) {
        // 柔边：不用 BlurMaskFilter（硬件绘制下这台机器上画不出来）。先在底的外面画 3 圈逐层变淡的描边（只画在底的外侧），再画底本身。
        cv.save();
        cv.clipOutPath(PATH);
        PAINT.setStyle(Paint.Style.STROKE);
        PAINT.setStrokeJoin(Paint.Join.ROUND);
        for (int k = 3; k >= 1; k--) {
            PAINT.setStrokeWidth(2f * SOFT_SP * sd * k / 3f);
            PAINT.setColor(gray | (a / 4) << 24);
            cv.drawPath(PATH, PAINT);
        }
        cv.restore();
        PAINT.setStyle(Paint.Style.FILL);
        // 玻璃本体：底色只用 60% 的不透明度，让后面的画面透一点出来
        PAINT.setColor(gray | (a * 3 / 5) << 24);
        cv.drawPath(PATH, PAINT);
        // 磨砂感：整块再盖一层很淡的白，压低后面画面的对比度（没有真正的背景模糊，这是视觉上的近似）
        PAINT.setColor(0xFFFFFF | (a * 18 / 100) << 24);
        cv.drawPath(PATH, PAINT);
        // 高光：竖向白色渐变，铺满整块底（多行时是一整块，和底一样按并集画）；Paint 的透明度乘在渐变上
        PATH.computeBounds(BOX, false);
        MAT.setScale(1f, BOX.height());
        MAT.postTranslate(0f, BOX.top);
        SHEEN.setLocalMatrix(MAT);
        PAINT.setColor(0xFFFFFFFF);
        PAINT.setAlpha(a);
        PAINT.setShader(SHEEN);
        cv.drawPath(PATH, PAINT);
        PAINT.setShader(null);
        // 边缘内发光：三层由外向内渐淡（0.7 / 1.8 / 3.6sp），四个方向各抠一道月牙，合起来是一圈柔和的亮带，不是一条硬线，中间留给文字
        float g3 = GLOW3_SP * sd, g2 = GLOW2_SP * sd, g1 = GLOW1_SP * sd;
        int w3 = 0xFFFFFF | (a * 8 / 100) << 24, w2 = 0xFFFFFF | (a * 14 / 100) << 24, w1 = 0xFFFFFF | (a * 28 / 100) << 24;
        rim(cv, 0f, g3, w3);
        rim(cv, 0f, -g3, w3);
        rim(cv, g3, 0f, w3);
        rim(cv, -g3, 0f, w3);
        rim(cv, 0f, g2, w2);
        rim(cv, 0f, -g2, w2);
        rim(cv, g2, 0f, w2);
        rim(cv, -g2, 0f, w2);
        rim(cv, 0f, g1, w1);
        rim(cv, 0f, -g1, w1);
        rim(cv, g1, 0f, w1);
        rim(cv, -g1, 0f, w1);
        // 色散：左上沿一道偏青、右下沿一道偏暖的细边，像玻璃边缘把背景拉开了颜色
        float dd = DISP_SP * sd;
        rim(cv, dd, dd, 0x80E0FF | (a * 22 / 100) << 24);
        rim(cv, -dd, -dd, 0xFFB070 | (a * 22 / 100) << 24);
        // 顶部高光：比四周的发光稍亮一点点，只是让上沿有受光的感觉，不再是一条白线
        rim(cv, 0f, RIM_TOP_SP * sd, 0xFFFFFF | (a * 22 / 100) << 24);
    }

    /** 在 PATH 里面画一道亮边：把 PATH 错开 (dx, dy) 再抠掉，剩下的是错开方向反侧的一道月牙（dy > 0 是上沿、dy < 0 是下沿、dx > 0 是左沿、dx < 0 是右沿）；argb 是这道亮边的颜色（含透明度）。PATH 画完会移回原位。 */
    private static void rim(Canvas cv, float dx, float dy, int argb) {
        cv.save();
        cv.clipPath(PATH);
        PATH.offset(dx, dy);
        cv.clipOutPath(PATH);
        PAINT.setColor(argb);
        cv.drawRect(BOX, PAINT);
        cv.restore();
        PATH.offset(-dx, -dy);
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
