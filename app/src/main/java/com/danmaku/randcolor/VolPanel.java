package com.danmaku.randcolor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 音量浮窗：盖在抖音当前页面窗口里的一组按键，可拖动位置（设置页的「固定位置」默认开着，关掉才能拖）。只取「怎么接到系统音量」：
 * AudioManager 的 STREAM_MUSIC（getStreamVolume / getStreamMaxVolume / setStreamVolume，flags = 0 不弹系统音量条），
 * 系统音量变了（实体键 / 别处改）靠广播 android.media.VOLUME_CHANGED_ACTION 刷新读数。
 *
 * 外观（1.42.0）：没有底板，每个键自己是独立的立体按键（渐变 + 描边 + 按下变深）。
 * 竖放从上到下 / 横放从左到右：当前音量（拖这里挪浮窗；数字 + 按音量比例填充的电平）、
 * 大步长一组（橙色，竖放上 ＋ 下 －，横放左 － 右 ＋，两键之间只隔 1 dp，外轮廓连在一起）、
 * ±1 一组（蓝色，同样的连体样式）。同一组加减一眼能看出是一对，两组靠颜色和间隔分开。
 * 显示哪几块（1.42.16）：当前音量、大步长一对、±1 一对各有一个开关（volShowVal / volShowBig / volShowOne，默认都开，关掉的块不占位、浮窗变短，三块都关就不加浮窗）；
 * 当前音量块放在加减键的左 / 右 / 上 / 下（volValPos，默认 = 横放在左、竖放在上，和以前一样）：和加减键同一条线上就排成一排，
 * 否则当前音量单独成一条（厚度 = 设置页的「宽」）放在加减键那一排的上 / 下 / 左 / 右。当前音量块关掉后没法在抖音里拖动浮窗（拖动只认它）。
 * 大步长在设置页自定义（volStep，1～100）；普通那一对固定 ±1；横放 / 竖放在设置页选（volHoriz）。
 *
 * 跟随显示（volFollow，默认关）：开着时只有这个窗口里有视频时长行（LineBg.Row.follow，显示 / 隐藏 / 挂上 / 摘下由 View 自己的回调告诉这里）
 * 或直播间的开播时间文字（LiveHook 的 show / hide / dispose 告诉这里）在显示，浮窗才显示，都没有就 GONE；不加钩子、不轮询、不扫控件。
 *
 * 让评论盖住音量条（volUnderCmt，默认开，1.44.1）：开着时音量条不再只加在窗口最上层，而是照视频时长行的做法加进每一页的根
 * （PubHook 的 pub.bind 回调拿到的 VideoViewHolderRootView，作为它最后一个子控件，见 page）。评论面板画在这一页上面，所以评论一弹出来就盖住它，
 * 并且随页面上下滑动；不新增钩子，pub.bind 的回调在这个开关开着时也会走（PubHook.BindHook 用 pageOn 判断）。
 * 窗口里只要有页面里的音量条挂着，窗口最上层那一份就让位（GONE）；没有页面根的地方（直播间、没有视频页的页面）还用窗口最上层这一份。
 * 页面里的条不受「跟随显示」管（它本来就只在视频页里）。页面根不是 FrameLayout、pub.bind 没挂上：什么都不做，窗口这一份照旧。
 * 关着这个开关：不往页面里加，已经加的在回到前台时撤掉，保持 1.44.0 之前的样子。
 *
 * 不是钩子：不挂任何方法，只在 Activity.onResume（DanmakuHook.hookResume 已经挂着的框架方法）里被调到；没有定时器、没有轮询，
 * 没有长按连发。广播接收器全模块只有一个（所有音量条共用，LIVE_BARS 里有可见窗口的条才注册，一个都没有就注销），只认音乐流的变化、读数没变不刷新（也不重绘）；
 * 拖动时只改 translation（不触发整页重新布局），抬手才落到 margin。画笔 / 路径 / 背景都在创建时一次建好，onDraw 里不分配对象。
 * 全部用代码画（抖音进程里读不到模块的资源）。开关关掉或没开：回到前台时把已经加上的浮窗撤掉。
 * 位置（dp，左 / 上）单独存在 dyin_pos.properties 里，和别的位置一样不被设置页冲掉。
 */
final class VolPanel {
    private VolPanel() {}

    private static final String TAG = "dyin_vol";           // 窗口最上层那一份
    private static final String TAG_PAGE = "dyin_vol_page";   // 页面根里的那一份（和窗口那一份分开认）
    // 沿条方向的各段按比例分配总长（设置页的「长」）：当前音量 44 : 间隔 8 : 一组 81 : 间隔 8 : 一组 81 = 222，默认长度 222 dp 时就是原来的尺寸
    private static final float VAL_W = 44f, SEP_W = 8f, PAIR_W = 81f;
    private static final int GAP_DP = 1;     // 同一组加减键之间的缝
    private static final int OUT_R = 12, IN_R = 4; // 同组键的外侧 / 内侧圆角（dp）
    private static final int ORANGE_TOP = 0xF0FFA24A, ORANGE_BOT = 0xF0F26A0A, ORANGE_DOWN = 0xFFB84A00;
    private static final int BLUE_TOP = 0xF05B9BFF, BLUE_BOT = 0xF02F63E0, BLUE_DOWN = 0xFF1E3FA8;
    private static final int STROKE = 0xB3FFFFFF;

    /** 每次回到前台：开着就确保这个窗口里有浮窗（有就只刷新位置和读数），关着就撤掉。主线程。 */
    static void onResume(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        View top = a.getWindow().getDecorView();
        if (!(top instanceof FrameLayout)) return;
        FrameLayout decor = (FrameLayout) top;
        WeakReference<Bar> ow = BARS.get(decor); // 窗口那一份只会是这里登记过的；不再每次回到前台整棵树找一遍
        View old = ow == null ? null : ow.get();
        if (old != null && old.getParent() != decor) old = null;
        DanmakuConfig cf = DanmakuHook.cfg;
        if (!cf.volOn || !(cf.volShowVal || cf.volShowBig || cf.volShowOne)) { // 没开，或三块都关了：不要浮窗
            if (old != null && old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
            BARS.remove(decor);
            return;
        }
        if (old instanceof Bar) {
            Bar ob = (Bar) old;
            if (ob.sameConfig()) {
                ob.placeAndRefresh();
                BARS.put(decor, new WeakReference<>(ob));
                applyFollow(decor, ob);
                return;
            }
            decor.removeView(old); // 设置页改了步长 / 方向 / 大小 / 显示哪几块 / 当前音量放哪边：重建
        }
        Bar bar = new Bar(decor.getContext(), false);
        decor.addView(bar, new FrameLayout.LayoutParams(bar.barW(), bar.barH(), Gravity.TOP | Gravity.START));
        bar.placeAndRefresh();
        BARS.put(decor, new WeakReference<>(bar));
        applyFollow(decor, bar);
    }

    // ------------------------------------------------------------ 跟随显示（volFollow）

    /** 一个「在显示」的来源：视频时长那一行，或直播间的一页（开播时间那块文字）。 */
    private static final class Src {
        boolean on;
        /** 直播间的来源（LiveHook 告诉的）；视频时长行是假。 */
        boolean live;
        WeakReference<View> decor; // 它在哪个窗口里（Activity 的根）
    }

    /** 来源控件 → 状态；键是弱引用，页面 / 行没了条目就没了。只在主线程用。 */
    private static final WeakHashMap<View, Src> SRC = new WeakHashMap<>();
    /** Activity 的根 → 这个窗口里的浮窗。值也用弱引用：浮窗的父控件就是根，强引用会让键永远回收不了。 */
    private static final WeakHashMap<View, WeakReference<Bar>> BARS = new WeakHashMap<>();
    /** 页面根 → 它里面的音量条（volUnderCmt）。值用弱引用：条的父控件就是根。只在主线程用。 */
    private static final WeakHashMap<View, WeakReference<Bar>> PAGE_OF = new WeakHashMap<>();
    /** 挂在窗口上的页面音量条 → 它在哪个窗口里；摘下就删。 */
    private static final WeakHashMap<Bar, WeakReference<View>> PAGE_BARS = new WeakHashMap<>();

    /** 视频时长行显示 / 隐藏（LineBg.Row 的回调；摘下时给假）。 */
    static void source(View v, boolean on) {
        try {
            set(v, on, false);
        } catch (Throwable ignored) {
        }
    }

    /** 直播间这一页的开播时间文字显示 / 隐藏 / 撤掉（LiveHook 的 show / hide / dispose）。 */
    static void live(View root, boolean on) {
        try {
            set(root, on, true);
        } catch (Throwable ignored) {
        }
    }

    private static void set(View key, boolean on, boolean live) {
        Src s = SRC.get(key);
        if (s == null) {
            if (!on) return; // 从没显示过又说隐藏：什么都不用做
            s = new Src();
            s.live = live;
            SRC.put(key, s);
        }
        View top = on ? key.getRootView() : null; // 隐藏 / 摘下时沿用上次记的窗口（父链可能已经断了）
        View old = s.decor == null ? null : s.decor.get();
        if (s.on == on && (top == null || top == old)) return; // 没变
        s.on = on;
        if (top != null && top != old) s.decor = new WeakReference<>(top);
        View decor = top != null ? top : old;
        if (old != null && top != null && top != old) applyWindow(old); // 换窗口：旧窗口也要重算
        if (decor != null) applyWindow(decor);
    }

    private static void applyWindow(View decor) {
        WeakReference<Bar> w = BARS.get(decor);
        Bar bar = w == null ? null : w.get();
        if (bar != null) applyFollow(decor, bar);
    }

    /**
     * 窗口最上层这一份要不要显示：
     * 「跟随显示」关着：一直显示；开着：这个窗口里有来源在显示才显示。
     * 「让评论盖住音量条」开着、而且这个窗口里已经有页面里的音量条挂着：窗口这一份让位（直播间在显示时除外，直播间没有视频页的条）。
     */
    private static void applyFollow(View decor, Bar bar) {
        DanmakuConfig cf = DanmakuHook.cfg;
        boolean show = !cf.volFollow || anySource(decor, false);
        if (show && cf.volUnderCmt && pageBarIn(decor) && !anySource(decor, true)) show = false;
        int v = show ? View.VISIBLE : View.GONE;
        if (bar.getVisibility() != v) bar.setVisibility(v);
    }

    /** 这个窗口里有在显示的来源；liveOnly = 只看直播间的。 */
    private static boolean anySource(View decor, boolean liveOnly) {
        for (Map.Entry<View, Src> e : SRC.entrySet()) {
            Src s = e.getValue();
            if (s.on && (!liveOnly || s.live) && s.decor != null && s.decor.get() == decor) return true;
        }
        return false;
    }

    private static boolean pageBarIn(View decor) {
        for (WeakReference<View> w : PAGE_BARS.values()) {
            if (w != null && w.get() == decor) return true;
        }
        return false;
    }

    /** 页面音量条挂上 / 摘下：窗口最上层那一份跟着重算。 */
    private static void pageBar(Bar b, boolean attached) {
        WeakReference<View> o = PAGE_BARS.get(b);
        View oldDecor = o == null ? null : o.get();
        View decor = attached ? b.getRootView() : oldDecor;
        if (attached) PAGE_BARS.put(b, new WeakReference<>(decor));
        else PAGE_BARS.remove(b);
        if (decor != null) applyWindow(decor);
    }

    // ------------------------------------------------------------ 页面里的音量条（volUnderCmt）

    /** 页面里要不要放音量条：总开关、「让评论盖住音量条」开着，且至少显示一块。PubHook 的 pub.bind 回调也靠它决定要不要往下走。 */
    static boolean pageOn() {
        DanmakuConfig cf = DanmakuHook.cfg;
        return cf.volOn && cf.volUnderCmt && (cf.volShowVal || cf.volShowBig || cf.volShowOne);
    }

    /**
     * 这一页的根（pub.bind 回调拿到的页面根）：开着就在根最后一个子控件的位置放一份音量条（有就只刷新位置和读数），关着就撤掉。主线程。
     * 页面根不是 FrameLayout 就什么都不做。
     */
    static void page(View root) {
        try {
            if (!(root instanceof FrameLayout)) return;
            FrameLayout g = (FrameLayout) root;
            WeakReference<Bar> w = PAGE_OF.get(g);
            Bar old = w == null ? null : w.get();
            if (old != null && old.getParent() != g) old = null;
            if (!pageOn()) {
                if (old != null) g.removeView(old);
                PAGE_OF.remove(g);
                return;
            }
            if (old != null) {
                if (old.sameConfig()) {
                    old.place();
                    return;
                }
                g.removeView(old); // 设置页改了步长 / 方向 / 大小 / 显示哪几块：重建
            }
            Bar bar = new Bar(g.getContext(), true);
            g.addView(bar, new FrameLayout.LayoutParams(bar.barW(), bar.barH(), Gravity.TOP | Gravity.START));
            PAGE_OF.put(g, new WeakReference<>(bar));
            bar.place(); // 读数在挂上窗口时 register 里刷新
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 音量广播（所有音量条共用一个接收器）

    /** 窗口可见、正在听音量广播的音量条（窗口那一份 + 每个页面里的）。弱引用，主线程用。 */
    private static final java.util.ArrayList<WeakReference<Bar>> LIVE_BARS = new java.util.ArrayList<>();
    private static Context rcvCtx;
    private static boolean rcvOn;

    /** 只有一个接收器：N 个页面各注册一个会让每次音量变化唤醒 N 次、各查一次系统音量。 */
    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                // 只认音乐流（别的流变了不用刷新）
                if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return;
                for (int i = LIVE_BARS.size() - 1; i >= 0; i--) {
                    Bar b = LIVE_BARS.get(i).get();
                    if (b == null) LIVE_BARS.remove(i);
                    else b.refresh();
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static void addLive(Bar b) {
        LIVE_BARS.add(new WeakReference<>(b));
        if (rcvOn) return;
        Context c = b.getContext().getApplicationContext();
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(RECEIVER, f, Context.RECEIVER_EXPORTED);
        else c.registerReceiver(RECEIVER, f);
        rcvCtx = c;
        rcvOn = true;
    }

    private static void removeLive(Bar b) {
        for (int i = LIVE_BARS.size() - 1; i >= 0; i--) {
            Bar x = LIVE_BARS.get(i).get();
            if (x == null || x == b) LIVE_BARS.remove(i);
        }
        if (!LIVE_BARS.isEmpty() || !rcvOn) return; // 还有音量条在听：接收器留着；没人了就注销（切后台 / 离开窗口不再被广播唤醒）
        try {
            rcvCtx.unregisterReceiver(RECEIVER);
        } catch (Throwable ignored) {
        }
        rcvOn = false;
        rcvCtx = null;
    }

    // ------------------------------------------------------------ 浮窗本体

    private static final class Bar extends LinearLayout {
        private final AudioManager am;
        private final Val tvVal; // 当前音量块；「当前音量」开关关着时为 null
        private final int step, max;
        private final boolean horiz;
        private final boolean showVal, showBig, showOne;
        private final boolean page; // 加在页面根里的那一份（窗口最上层的那一份是 false）
        private final int valPos; // 0 左 1 右 2 上 3 下（默认已按横放 / 竖放换算好）
        private final int pxW, pxH; // 整块浮窗的大小（px）
        private final int wDp, lDp; // 建的时候的宽 / 长（dp），设置页改了就重建
        private float downRawX, downRawY;
        private int startL, startT;
        private boolean receiverOn; // 已经登记进共享接收器（LIVE_BARS）
        private int shown = -1; // 当前显示的音量，没变就不刷新

        Bar(final Context ctx, boolean page) {
            super(ctx);
            this.page = page;
            am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            step = Math.max(1, Math.min(100, DanmakuHook.cfg.volStep));
            horiz = DanmakuHook.cfg.volHoriz;
            wDp = Math.max(DanmakuConfig.VOL_W_MIN, Math.min(DanmakuConfig.VOL_W_MAX, DanmakuHook.cfg.volW));
            lDp = Math.max(DanmakuConfig.VOL_L_MIN, Math.min(DanmakuConfig.VOL_L_MAX, DanmakuHook.cfg.volL));
            setTag(page ? TAG_PAGE : TAG);
            DanmakuConfig cf = DanmakuHook.cfg;
            showVal = cf.volShowVal;
            showBig = cf.volShowBig;
            showOne = cf.volShowOne;
            valPos = cf.volValPos >= 0 && cf.volValPos <= 3 ? cf.volValPos : (horiz ? 0 : 2); // 默认：横放在左、竖放在上（和以前一样）
            boolean pairs = showBig || showOne;
            boolean rootHoriz = valPos <= 1;
            // 当前音量块和加减键排在同一条线上（和加减键同方向，或者只剩一边）：一排到底；否则当前音量块单独一条，放在加减键的那一边
            boolean flat = !showVal || !pairs || rootHoriz == horiz;
            // 沿加减键方向的各段按比例分配总长：每块的长度 = 设置页的「长」/ 222 × 它的比例，关掉的块不占位
            float k = lDp / 222f;
            float keysW = (showBig ? PAIR_W : 0f) + (showOne ? PAIR_W : 0f) + (showBig && showOne ? SEP_W : 0f);

            tvVal = showVal ? new Val(ctx, horiz) : null;
            if (tvVal != null) {
                tvVal.setOnTouchListener(new OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent e) {
                        try {
                            return drag(e);
                        } catch (Throwable t) {
                            return true;
                        }
                    }
                });
            }
            if (flat) {
                setOrientation(horiz ? HORIZONTAL : VERTICAL);
                boolean valFirst = valPos == 0 || valPos == 2;
                boolean needSep = false;
                if (tvVal != null && valFirst) {
                    addView(tvVal, lp(VAL_W));
                    needSep = true;
                }
                if (showBig) {
                    if (needSep) addView(spacer(ctx, SEP_W));
                    addView(pair(ctx, step, true));
                    needSep = true;
                }
                if (showOne) {
                    if (needSep) addView(spacer(ctx, SEP_W));
                    addView(pair(ctx, 1, false));
                    needSep = true;
                }
                if (tvVal != null && !valFirst) {
                    if (needSep) addView(spacer(ctx, SEP_W));
                    addView(tvVal, lp(VAL_W));
                }
                float total = (tvVal != null ? VAL_W : 0f) + keysW + (tvVal != null && pairs ? SEP_W : 0f);
                int len = dp(ctx, Math.round(k * total)), thick = dp(ctx, wDp);
                pxW = horiz ? len : thick;
                pxH = horiz ? thick : len;
            } else {
                // 当前音量块单独一条（宽 / 高 = 浮窗的「宽」，另一向撑满），加减键放成另一条，中间隔 gap
                setOrientation(rootHoriz ? HORIZONTAL : VERTICAL);
                LinearLayout keys = new LinearLayout(ctx);
                keys.setOrientation(horiz ? HORIZONTAL : VERTICAL);
                if (showBig) keys.addView(pair(ctx, step, true));
                if (showBig && showOne) keys.addView(spacer(ctx, SEP_W));
                if (showOne) keys.addView(pair(ctx, 1, false));
                int thickPx = dp(ctx, wDp), gapDp = Math.max(1, Math.round(k * SEP_W)), gapPx = dp(ctx, gapDp);
                LayoutParams side = rootHoriz ? new LayoutParams(thickPx, -1) : new LayoutParams(-1, thickPx);
                LayoutParams keysLp = rootHoriz ? new LayoutParams(thickPx, -1) : new LayoutParams(-1, thickPx);
                View gapV = new View(ctx);
                gapV.setLayoutParams(rootHoriz ? new LayoutParams(gapPx, -1) : new LayoutParams(-1, gapPx));
                if (valPos == 0 || valPos == 2) {
                    addView(tvVal, side);
                    addView(gapV);
                    addView(keys, keysLp);
                } else {
                    addView(keys, keysLp);
                    addView(gapV);
                    addView(tvVal, side);
                }
                int len = dp(ctx, Math.round(k * keysW)), thick = 2 * thickPx + gapPx;
                pxW = rootHoriz ? thick : len;
                pxH = rootHoriz ? len : thick;
            }
        }

        /** 设置页里的步长 / 方向 / 大小 / 显示哪几块 / 当前音量放哪边，和建的时候一样吗（不一样就重建）。 */
        boolean sameConfig() {
            DanmakuConfig c = DanmakuHook.cfg;
            return step == Math.max(1, Math.min(100, c.volStep)) && horiz == c.volHoriz && wDp == c.volW && lDp == c.volL && sameBlocks();
        }

        /** 设置页里「显示哪几块 / 当前音量放哪边」和建的时候一样吗（不一样就重建）。 */
        boolean sameBlocks() {
            DanmakuConfig c = DanmakuHook.cfg;
            int vp = c.volValPos >= 0 && c.volValPos <= 3 ? c.volValPos : (horiz ? 0 : 2);
            return showVal == c.volShowVal && showBig == c.volShowBig && showOne == c.volShowOne && valPos == vp;
        }

        /** 沿条方向按权重 weight 分、另一方向撑满的布局参数。 */
        private LayoutParams lp(float weight) {
            return horiz ? new LayoutParams(0, -1, weight) : new LayoutParams(-1, 0, weight);
        }

        private View spacer(Context ctx, float weight) {
            View v = new View(ctx);
            v.setLayoutParams(lp(weight));
            return v;
        }

        /** 同组加减键之间的缝：固定 GAP_DP。 */
        private View gap(Context ctx) {
            View v = new View(ctx);
            v.setLayoutParams(horiz ? new LayoutParams(dp(ctx, GAP_DP), -1) : new LayoutParams(-1, dp(ctx, GAP_DP)));
            return v;
        }

        /** 一组加减：竖放上 ＋ 下 －，横放左 － 右 ＋；两个键连成一个外轮廓。 */
        private View pair(Context ctx, int n, boolean big) {
            LinearLayout g = new LinearLayout(ctx);
            g.setOrientation(horiz ? HORIZONTAL : VERTICAL);
            View plus = button(ctx, "＋" + n, n, big, horiz ? false : true);
            View minus = button(ctx, "－" + n, -n, big, horiz ? true : false);
            if (horiz) {
                g.addView(minus, lp(1f));
                g.addView(gap(ctx));
                g.addView(plus, lp(1f));
            } else {
                g.addView(plus, lp(1f));
                g.addView(gap(ctx));
                g.addView(minus, lp(1f));
            }
            g.setLayoutParams(lp(PAIR_W));
            return g;
        }

        /** 一个立体键。first = 这一组里排在前面的那个（竖放在上、横放在左），决定哪几个角是大圆角。 */
        private View button(Context ctx, String text, final int delta, boolean big, boolean first) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextColor(Color.WHITE);
            t.setTextSize(text.length() > 3 ? 12 : 16);
            t.setTypeface(null, Typeface.BOLD);
            t.setShadowLayer(dp(ctx, 2), 0, dp(ctx, 1), 0xAA000000);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            t.setClickable(true);
            // 圆角顺序：左上、右上、右下、左下
            float o = dp(ctx, OUT_R), n = dp(ctx, IN_R);
            float[] r;
            if (horiz) r = first ? new float[] {o, o, n, n, n, n, o, o} : new float[] {n, n, o, o, o, o, n, n};
            else r = first ? new float[] {o, o, o, o, n, n, n, n} : new float[] {n, n, n, n, o, o, o, o};
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[] {android.R.attr.state_pressed}, face(ctx, big ? ORANGE_DOWN : BLUE_DOWN, big ? ORANGE_DOWN : BLUE_DOWN, r));
            bg.addState(new int[] {}, face(ctx, big ? ORANGE_TOP : BLUE_TOP, big ? ORANGE_BOT : BLUE_BOT, r));
            t.setBackground(bg);
            t.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    try {
                        int a = e.getActionMasked();
                        if (a == MotionEvent.ACTION_DOWN) {
                            v.setPressed(true);
                            change(delta);
                        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                            v.setPressed(false);
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
            return t;
        }

        private static GradientDrawable face(Context ctx, int top, int bottom, float[] radii) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
            d.setCornerRadii(radii);
            d.setStroke(dp(ctx, 1), STROKE);
            return d;
        }

        private void change(int delta) {
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int next = Math.max(0, Math.min(max, cur + delta));
            if (next != cur) am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
            refresh();
        }

        void refresh() {
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v == shown) return;
            shown = v;
            if (tvVal != null) tvVal.set(v, max);
        }

        /** 按配置里的位置（dp，左 / 上；-1 = 默认：靠右、偏上）摆好，并保证整块在窗口里。 */
        void placeAndRefresh() {
            place();
            refresh();
        }

        /** 只摆位置，不查系统音量（页面里的条每次翻页绑定都会来一次；读数靠广播和挂上窗口时的 register 保持最新）。 */
        void place() {
            ViewGroup p = (ViewGroup) getParent();
            if (p == null) return;
            DanmakuConfig c = DanmakuHook.cfg;
            Context ctx = getContext();
            int pw = p.getWidth() > 0 ? p.getWidth() : ctx.getResources().getDisplayMetrics().widthPixels;
            int ph = p.getHeight() > 0 ? p.getHeight() : ctx.getResources().getDisplayMetrics().heightPixels;
            int l = c.volX >= 0 ? dp(ctx, c.volX) : pw - barW() - dp(ctx, 8);
            int t = c.volY >= 0 ? dp(ctx, c.volY) : ph * 2 / 5;
            applyMargins(l, t, pw, ph);
        }

        private int barW() {
            return pxW;
        }

        private int barH() {
            return pxH;
        }

        private void applyMargins(int l, int t, int pw, int ph) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            int w = getWidth() > 0 ? getWidth() : barW();
            int h = getHeight() > 0 ? getHeight() : barH();
            int nl = Math.max(0, Math.min(Math.max(0, pw - w), l));
            int nt = Math.max(0, Math.min(Math.max(0, ph - h), t));
            if (lp.leftMargin == nl && lp.topMargin == nt) return; // 没变就不动：setLayoutParams 会让整个页面根重新布局
            lp.leftMargin = nl;
            lp.topMargin = nt;
            setLayoutParams(lp);
        }

        /** 拖中间的读数挪浮窗（设置页「固定位置」开着时不动）；抬手保存位置（dp）。 */
        private boolean drag(MotionEvent e) {
            ViewGroup p = (ViewGroup) getParent();
            if (p == null) return true;
            if (DanmakuHook.cfg.volLock) return true; // 固定位置：吃掉触摸，不挪
            int a = e.getActionMasked();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            if (a == MotionEvent.ACTION_DOWN) {
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startL = lp.leftMargin;
                startT = lp.topMargin;
            } else if (a == MotionEvent.ACTION_MOVE) {
                // 只改 translation，不触发整页重新布局；范围限制在窗口内
                int w = getWidth(), h = getHeight();
                int l = Math.max(0, Math.min(Math.max(0, p.getWidth() - w), startL + (int) (e.getRawX() - downRawX)));
                int t = Math.max(0, Math.min(Math.max(0, p.getHeight() - h), startT + (int) (e.getRawY() - downRawY)));
                setTranslationX(l - lp.leftMargin);
                setTranslationY(t - lp.topMargin);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                lp.leftMargin += Math.round(getTranslationX());
                lp.topMargin += Math.round(getTranslationY());
                setTranslationX(0);
                setTranslationY(0);
                setLayoutParams(lp);
                float d = getContext().getResources().getDisplayMetrics().density;
                DanmakuConfig c = DanmakuHook.cfg;
                c.volX = Math.round(lp.leftMargin / d);
                c.volY = Math.round(lp.topMargin / d);
                DanmakuHook.saveCfg();
            }
            return true;
        }

        private void register() {
            if (receiverOn) return;
            receiverOn = true;
            addLive(this);
            refresh(); // 不可见期间可能变过
        }

        private void unregister() {
            if (!receiverOn) return;
            receiverOn = false;
            removeLive(this);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (page) pageBar(this, true);
            if (getWindowVisibility() == VISIBLE) register();
        }

        /** 窗口不可见（切后台 / 锁屏）就注销广播，回来再注册。 */
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) register();
            else unregister();
        }

        @Override
        protected void onDetachedFromWindow() {
            unregister();
            if (page) pageBar(this, false);
            super.onDetachedFromWindow();
        }
    }

    /** 当前音量：圆角块，数字居中，按音量比例从下（横放从左）填充一层绿色电平；拖它挪浮窗。 */
    private static final class Val extends TextView {
        private final boolean horiz;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint level = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF box = new RectF();
        private final float radius;
        private float ratio;

        Val(Context ctx, boolean horiz) {
            super(ctx);
            this.horiz = horiz;
            radius = dp(ctx, OUT_R);
            track.setColor(0x4DFFFFFF);
            level.setColor(0xD934C759);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(ctx, 1));
            edge.setColor(STROKE);
            setTextColor(Color.WHITE);
            setTextSize(18);
            setTypeface(null, Typeface.BOLD);
            setShadowLayer(dp(ctx, 2), 0, dp(ctx, 1), 0xAA000000);
            setGravity(Gravity.CENTER);
            setIncludeFontPadding(false);
            setClickable(true);
        }

        void set(int v, int max) {
            ratio = Math.max(0f, Math.min(1f, v / (float) max));
            setText(String.valueOf(v));
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float half = edge.getStrokeWidth() / 2f;
            box.set(half, half, w - half, h - half);
            clip.reset();
            clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRoundRect(box, radius, radius, track);
            if (ratio > 0f) {
                c.save();
                c.clipPath(clip);
                if (horiz) c.drawRect(box.left, box.top, box.left + box.width() * ratio, box.bottom, level);
                else c.drawRect(box.left, box.bottom - box.height() * ratio, box.right, box.bottom, level);
                c.restore();
            }
            c.drawRoundRect(box, radius, radius, edge);
            super.onDraw(c);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
