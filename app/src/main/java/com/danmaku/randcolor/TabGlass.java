package com.danmaku.randcolor;

import android.graphics.Bitmap;
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
        applySpread(item, Math.round(c.tabSpread * dpf(0.5f))); // 先撑开间距（标签框变宽），玻璃按新的框画
        // 文字的投影：每次回调都按当前设置刷新（LineBg.shadow 内部只在变了才重设）
        if (c.tabShadow > 0) shadowAll(item, c, 0); // 0% = 不碰抖音自己的字投影
        // 文字颜色：选中 / 未选中各一个，0 = 不碰抖音自己的
        int tc = selected ? c.tabTextSelColor : c.tabTextColor;
        if (tc != 0) colorAll(item, tc, 0);
        boolean glass = c.tabGlass && (c.tabRowBg > 0 || c.tabItemBg > 0);
        if (glass && item.getTag(TAG) == null && item.getBackground() == null) {
            item.setTag(TAG, Boolean.TRUE);
            ItemGlass ig = new ItemGlass();
            ig.top = true; // 顶部标签的：受 tabGlass 总开关管（底部导航共用这个类，不受它管）
            item.setBackground(ig);
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
        if (glass && strip.getTag(TAG) == null && strip.getBackground() == null) {
            strip.setTag(TAG, Boolean.TRUE);
            strip.setBackground(new RowGlass((ViewGroup) p1, (ViewGroup) p2));
            LineBg.unclip(strip); // 整行的玻璃比标签行大一圈（含柔边），关掉上面几层的裁剪才画得完整
        }
        hookCenter((ViewGroup) p1, (ViewGroup) p2, strip);
    }

    private static final int CENTER_TAG = 0x7e0b0c09, SHIFT_TAG = 0x7e0b0c0a;

    /** 挂过居中监听的标签条（弱引用，主线程用）：回到前台时各重算一次。 */
    private static final java.util.ArrayList<java.lang.ref.WeakReference<Centerer>> CENTERERS = new java.util.ArrayList<>();

    /** 回到前台（配置刚重读过）：登记过的标签条重算一次居中（关了 tabCenter 就还原）。只在回前台调，不是每帧；没有扫描，只对登记过的几个对象调 center。 */
    static void onResume() {
        for (int i = CENTERERS.size() - 1; i >= 0; i--) {
            Centerer z = CENTERERS.get(i).get();
            if (z == null) {
                CENTERERS.remove(i);
                continue;
            }
            try {
                if (z.row.isLaidOut() && !z.row.isLayoutRequested()) center(z.row, z.scroll, z.strip);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 挂在标签行（MainTabRelativeLayout）上的布局监听：行每次重新布局（隐藏 / 显示标签、标签宽度变）之后重新居中。 */
    private static final class Centerer implements View.OnLayoutChangeListener {
        final ViewGroup row, scroll, strip;

        /** 这个监听挂在了哪些控件上（标签行、滚动容器、标签条和标签条所有的祖先），换了新的标签行时旧的整批摘掉。 */
        final java.util.ArrayList<View> hosts = new java.util.ArrayList<>();

        Centerer(ViewGroup row, ViewGroup scroll, ViewGroup strip) {
            this.row = row;
            this.scroll = scroll;
            this.strip = strip;
        }

        void host(View v) {
            v.addOnLayoutChangeListener(this);
            hosts.add(v);
        }

        void detach() {
            for (int i = 0, n = hosts.size(); i < n; i++) hosts.get(i).removeOnLayoutChangeListener(this);
            hosts.clear();
        }

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            try {
                // 标签条的祖先：只有它自己的位置 / 大小变了才会影响居中；某个后代 requestLayout 时整条祖先链都会再布局一遍（框没变），那种不算。
                // 标签行 / 滚动容器 / 标签条本身不做这个判断：隐藏 / 显示标签、标签宽度变了，它们的框可以不变，但可见标签的范围变了。
                if (v != row && v != scroll && v != strip && l == ol && t == ot && r == or && b == ob) return;
                center(row, scroll, strip);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 标签行居中（设置 tabCenter）。做法：只改 MainTabStripNew 的 translationX，不改布局参数、不新增钩子、没有定时器。
     * 把「当前看得见的标签」的左右外沿并起来（和 RowGlass 同一套算法），把它的中点平移到窗口宽度的一半。
     * 什么时候算：标签行（MainTabRelativeLayout）上挂一个 OnLayoutChangeListener，行每次布局完算一次（隐藏标签后会重新布局）；
     * attach 里布局已经完成时也算一次。attach 时布局还在排队（isLayoutRequested）就不算，等监听来算。
     * 1.44.4：位置只按布局算（忽略祖先的平移 / 缩放），见 center() 里的说明。
     * 1.44.6：重算的时机加了两处——监听同时挂在滚动容器和标签条上；回到前台时对登记过的标签条重算一次（onResume）。算法没动。
     * 1.44.7：监听改成挂在标签条的整条祖先链上（1.44.6 挂的滚动容器、标签行都在标签条下面，祖先变了收不到）。
     * 1.44.8：祖先上的回调先看自己的框变没变，没变就返回（后代 requestLayout 会让整条祖先链都再布局一遍）。
     * 没追踪过的（上机看）：① MainTabStripNew 平移后它的触摸范围会跟着移，如果它比标签行宽很多、盖到右上角搜索按钮上，搜索按钮可能点不到；
     * ② 抖音有没有在别处自己设过这个控件的 translationX；③ 标签多到超出横向滚动容器时，只把看得见的那一段居中。
     * 关掉 tabCenter 后，下一次布局时把平移还原成 0（只还原我们设过的）。
     */
    private static void hookCenter(ViewGroup row, ViewGroup scroll, ViewGroup strip) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (row.getTag(CENTER_TAG) == null) {
            if (!c.tabCenter) return; // 没开过：什么都不碰
            row.setTag(CENTER_TAG, Boolean.TRUE);
            for (int i = CENTERERS.size() - 1; i >= 0; i--) { // 旧的标签条已经不在窗口里了（页面重建）：把它挂在祖先上的监听摘掉，不留着引用旧控件
                Centerer old = CENTERERS.get(i).get();
                if (old == null) {
                    CENTERERS.remove(i);
                } else if (!old.strip.isAttachedToWindow()) {
                    old.detach();
                    CENTERERS.remove(i);
                }
            }
            Centerer z = new Centerer(row, scroll, strip); // 每个标签行只挂一次
            z.host(row);
            z.host(scroll);
            z.host(strip);
            // 1.44.7：平移量取决于标签条在窗口里的位置（沿父级累加 getLeft），任何一层祖先的位置 / 宽度变了都要重算；
            // 祖先变了标签条自己的布局事件不会来，所以监听挂在整条祖先链上（十几层，只在那一层真的重新布局时才回调）
            ViewParent up = strip.getParent();
            while (up instanceof View) {
                z.host((View) up);
                up = up.getParent();
            }
            CENTERERS.add(new java.lang.ref.WeakReference<>(z));
            LineBg.unclip(strip); // 平移后可能超出原来的位置，关掉上面几层的裁剪
        }
        if (row.isLaidOut() && !row.isLayoutRequested()) center(row, scroll, strip);
    }

    private static void center(ViewGroup row, ViewGroup scroll, ViewGroup strip) {
        DanmakuConfig c = DanmakuHook.cfg;
        float tx = strip.getTranslationX();
        if (!c.tabCenter) {
            if (strip.getTag(SHIFT_TAG) != null) { // 我们平移过：还原
                strip.setTag(SHIFT_TAG, null);
                strip.setTranslationX(0f);
            }
            return;
        }
        Class<?> k = TabHook.itemClass();
        if (k == null) return;
        int sl = scroll.getLeft(), sr = scroll.getRight(), sx = scroll.getScrollX();
        int ox = row.getLeft() - sx + sl; // 标签在 strip 坐标里的横向偏移（同 RowGlass）
        float L = Float.MAX_VALUE, R = -Float.MAX_VALUE;
        for (int i = 0, n = row.getChildCount(); i < n; i++) {
            View v = row.getChildAt(i);
            if (!k.isInstance(v) || v.getVisibility() != View.VISIBLE) continue;
            float x1 = Math.max(v.getLeft() + ox, sl), x2 = Math.min(v.getRight() + ox, sr);
            if (x2 <= x1) continue; // 划出看得见的范围了
            L = Math.min(L, x1);
            R = Math.max(R, x2);
        }
        if (R <= L) return;
        if (!strip.isAttachedToWindow()) return;
        int w = strip.getRootView().getWidth();
        if (w <= 0) return;
        // 1.44.4：不再用 getLocationInWindow。它含「strip 自己和所有祖先的 translationX / scroll」，页面滑动 / 切页过渡中祖先在平移，
        // 此刻算出来的补偿量会被固化进 strip 的 translationX，过渡结束后没有新的布局来纠正 → 标签行偏出屏幕（只剩几个字在屏幕里）甚至整行不见。
        // 现在只按布局位置算：strip 在窗口里的「未平移」左沿 = 沿父级向上累加 (getLeft − 父级 scrollX)，一路忽略所有 translation；
        // 平移量 = 窗口中点 − (未平移左沿 + 可见标签的中点)，是绝对值，不依赖 strip 现在的 translationX，反复算结果一样。
        float base = 0f;
        View v = strip;
        while (true) {
            base += v.getLeft();
            ViewParent vp = v.getParent();
            if (!(vp instanceof View)) break; // 到根了（DecorView 的父级是 ViewRootImpl）
            View pv = (View) vp;
            base -= pv.getScrollX();
            v = pv;
        }
        float nt = w / 2f - (base + (L + R) / 2f);
        if (Math.abs(nt - tx) < 0.5f) return;
        strip.setTag(SHIFT_TAG, Boolean.TRUE);
        strip.setTranslationX(nt);
    }

    /** v 自己和往下 3 层里所有有文字的 TextView 设同一个文字颜色（只在颜色不一样时才设）。 */
    private static void colorAll(View v, int color, int depth) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getText().length() > 0 && t.getCurrentTextColor() != color) t.setTextColor(color);
        }
        if (depth >= 3 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0, n = g.getChildCount(); i < n; i++) colorAll(g.getChildAt(i), color, depth + 1);
    }

    private static final int SPREAD_TAG = 0x7e0b0c08;

    /** 记下抖音原来的布局参数和我们设过的值：抖音自己又改了的（现在的值不是我们设的）就把现在的当作新的原值。 */
    private static final class Spread {
        int w, ml, mr, applW, applMl, applMr;
    }

    /**
     * 撑开标签文字之间的距离（设置 tabSpread，px = 0.5dp 一档）。依据（TextRec 40.9.0，textrec-tasks-20261028-085358，点击视图树）：
     * 标签 HomeTopTabItemViewNew 114×110，布局参数宽度写死 114；里面的文字控件 AntiBurnDuxTextView 80×54，布局参数左右外边距各 17（80 + 17 × 2 = 114）；
     * 标签一个挨一个排；任务期间（切关注 / 切回推荐 / 左右划）item 的 setPadding / setLayoutParams / requestLayout 都没被调用过（钩点可用、0 次命中）。
     * 做法：只改布局参数、不改任何方法——标签框宽度 = 原值 + extra，文字左右外边距各加 extra 的一半（文字仍居中）。每次都按「原值 + extra」设（不累加）；extra = 0 就还原。
     * 标签里的文字控件按类型找：直接子控件里第一个有文字的 TextView；找不到、布局参数不是带外边距的类型就安静跳过。
     * 没追踪过的：标签里另外 4 个子控件是什么、要不要跟着挪，横向滚动容器的宽度是写死还是按内容，抖音在别的场景（换主题 / 标签数变化）会不会重设布局参数；上机看。
     */
    private static void applySpread(View item, int extra) {
        Spread s = (Spread) item.getTag(SPREAD_TAG);
        if (extra <= 0 && s == null) return; // 没开过：什么都不碰
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        TextView tv = textOf(item);
        if (lp == null || tv == null || !(tv.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) tv.getLayoutParams();
        if (s == null) {
            s = new Spread();
            s.w = s.applW = lp.width;
            s.ml = s.applMl = m.leftMargin;
            s.mr = s.applMr = m.rightMargin;
            item.setTag(SPREAD_TAG, s);
        } else {
            if (lp.width != s.applW) s.w = lp.width; // 抖音自己改过：现在的就是新的原值
            if (m.leftMargin != s.applMl) s.ml = m.leftMargin;
            if (m.rightMargin != s.applMr) s.mr = m.rightMargin;
        }
        int half = extra / 2;
        int nw = s.w >= 0 ? s.w + extra : s.w; // 写死宽度才加宽；按内容的靠外边距自己撑
        int nl = s.ml + half, nr = s.mr + (extra - half);
        s.applW = nw;
        s.applMl = nl;
        s.applMr = nr;
        if (nw == lp.width && nl == m.leftMargin && nr == m.rightMargin) return;
        lp.width = nw;
        m.leftMargin = nl;
        m.rightMargin = nr;
        item.setLayoutParams(lp);
        tv.setLayoutParams(m);
    }

    /** 标签里的文字控件：直接子控件里第一个有文字的 TextView。 */
    private static TextView textOf(View item) {
        if (!(item instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) item;
        for (int i = 0, n = g.getChildCount(); i < n; i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView && ((TextView) v).getText().length() > 0) return (TextView) v;
        }
        return null;
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

    static float dpf(float v) {
        return v * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG); // 主线程绘制，复用
    private static final RectF BX = new RectF();
    private static final Path PT = new Path();

    abstract static class Glass extends Drawable {
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---- 更立体的玻璃（只给顶部标签用；文字底条的玻璃没动）。画在 Canvas 上，没有真正的背景模糊；1.44.9 起按参数缓存成硬件位图（drawGlass），glass() 只在缓存没命中时画一次。
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
    static void glass(Canvas cv, float l, float t, float r, float b, float f, float lvl, int color, boolean selected) {
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

    // ---- 1.44.9：玻璃缓存成硬件位图。glass() 一块要 5 道边缘光 × 2 次路径裁剪 + 十来个绘制操作，12 块（顶部 5 + 整行、底部 5 + 整行）每次重绘都现画，
    // 划视频 / 标签动画时每帧上百个操作、上百次非矩形裁剪。玻璃外观只由（宽、高、不透明度、层级、颜色、选中、有没有投影）决定，
    // 所以按这些参数在软件画布上画一次，转成硬件位图（显存里），之后每次绘制只 drawBitmap 一下。参数一变（设置、大小、选中）就是新的一张。
    private static final int GC_N = 24;
    private static final Bitmap[] GC_BM = new Bitmap[GC_N];
    private static final float[] GC_F = new float[GC_N * 4]; // 宽、高、不透明度、层级
    private static final int[] GC_I = new int[GC_N * 2];     // 颜色、标志（1 = 选中，2 = 带投影）
    private static int gcNext;

    private static final float SHADOW_MARGIN_DP = 4.6f; // 投影最远：向外 3dp + 往下偏 1.5dp

    /** 投影：只画在玻璃形状外面（往下偏 1.5dp、向外 3 层逐层变淡；不用 BlurMaskFilter，硬件绘制下画不出来）。 */
    private static void shadow(Canvas cv, float l, float t, float r, float b, float f) {
        float rad = (b - t) / 2f;
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
    }

    /** 画一块玻璃（可带投影）到 cv 上，框是 (l, t, r, b)。从缓存取位图一次绘制；没有就画一张存起来。位置取整到像素。 */
    static void drawGlass(Canvas cv, float l, float t, float r, float b, float f, float lvl, int color, boolean selected, boolean withShadow) {
        float w = r - l, h = b - t;
        int flags = (selected ? 1 : 0) | (withShadow ? 2 : 0);
        int slot = -1;
        for (int i = 0; i < GC_N; i++) {
            Bitmap bm = GC_BM[i];
            if (bm == null) continue;
            int o = i * 4;
            if (GC_F[o] == w && GC_F[o + 1] == h && GC_F[o + 2] == f && GC_F[o + 3] == lvl && GC_I[i * 2] == color && GC_I[i * 2 + 1] == flags) {
                slot = i;
                break;
            }
        }
        int m = withShadow ? (int) Math.ceil(dpf(SHADOW_MARGIN_DP)) + 1 : 0;
        if (slot < 0) {
            int bw = (int) Math.ceil(w) + 2 * m, bh = (int) Math.ceil(h) + 2 * m;
            if (bw <= 0 || bh <= 0) return;
            Bitmap sw = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(sw);
            if (withShadow) shadow(c, m, m, m + w, m + h, f);
            glass(c, m, m, m + w, m + h, f, lvl, color, selected);
            Bitmap hw = sw.copy(Bitmap.Config.HARDWARE, false); // minSdk 27；转成硬件位图（显存），之后绘制不再上传
            if (hw == null) hw = sw;
            else sw.recycle();
            slot = gcNext;
            gcNext = (gcNext + 1) % GC_N;
            GC_BM[slot] = hw; // 被挤掉的旧位图不 recycle（可能还被已录好的显示列表引用），交给 GC
            int o = slot * 4;
            GC_F[o] = w;
            GC_F[o + 1] = h;
            GC_F[o + 2] = f;
            GC_F[o + 3] = lvl;
            GC_I[slot * 2] = color;
            GC_I[slot * 2 + 1] = flags;
        }
        cv.drawBitmap(GC_BM[slot], Math.round(l) - m, Math.round(t) - m, null);
    }

    /**
     * 每个标签的玻璃（设置 tabItemBg / tabItemColor）：一条小长条，和相邻的标签分开：
     *  - 大小在设置页调：高 = 标签框高 × tabItemH（%，上下居中）；相邻两条之间的缝 = tabGap（0.5dp 一档），宽 = 标签框宽 − 缝（标签是抖音一个挨一个排的，宽和缝是同一个自由度）；
     *  - 玻璃下面有一道淡黑色投影（往下偏 1.5dp、向外 3 层逐层变淡；只画在玻璃外面，不压暗玻璃本身；不用 BlurMaskFilter，硬件绘制下画不出来）；
     *  - 选中的标签（描述里有「已选中」）再亮一档。
     */
    static final class ItemGlass extends Glass {
        boolean selected, top;

        @Override
        public void draw(Canvas cv) {
            try {
                DanmakuConfig c = DanmakuHook.cfg;
                if (c.tabItemBg <= 0 || (top && !c.tabGlass)) return;
                Rect bd = getBounds();
                float gh = bd.height() * c.tabItemH / 100f, cy = bd.exactCenterY(), gap = c.tabGap * dpf(0.5f) / 2f; // 缝的一半
                float l = bd.left + gap, r = bd.right - gap, t = cy - gh / 2f, b = cy + gh / 2f;
                if (r <= l || b <= t) return;
                float f = Math.min(100, c.tabItemBg) / 100f;
                drawGlass(cv, l, t, r, b, f, 1f, c.tabItemColor, selected, true); // 投影 + 玻璃，一张缓存位图
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
                if (c.tabRowBg <= 0 || !c.tabGlass) return;
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
                drawGlass(cv, L + gap - px, cy - half, R - gap + px, cy + half, Math.min(100, c.tabRowBg) / 100f, 0.75f, c.tabRowColor, false, false);
            } catch (Throwable ignored) {
            }
        }
    }
}
