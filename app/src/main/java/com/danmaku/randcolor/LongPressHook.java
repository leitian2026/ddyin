package com.danmaku.randcolor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.ViewConfiguration;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 首页长按弹出的面板（分享给朋友 / 不感兴趣 …）改成：长按什么都不做（忽略），面板只能点一个常驻按键弹出。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261025-094241，首页长按两次、点空白处关一次、按返回关一次）：
 *  - 长按弹出的面板是 com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl（类名没混淆），是个 DialogFragment；
 *    弹出的入口 = androidx.fragment.app.DialogFragment#show(FragmentManager, String)，this = LongPressPanelFragmentImpl，
 *    参数 (FragmentManager{… in HostCallbacks{…}}, "LongPressPanelFragment")；返回 void（TextRec 标明可以 setResult(null) 整个跳过）。
 *  - 长按没有走 View#performLongClick（那个钩点整个任务里 0 次），所以不在长按手势上做文章，只管面板自己的 show。
 *  - 追踪里只见到 (FragmentManager, String) 这一个重载，只挂这一个。
 *
 * 做法：
 *  1. lp.show = DialogFragment#show 的 (FragmentManager, String) 重载，before：this 的类名是 LongPressPanelFragmentImpl 才处理，
 *     别的 DialogFragment 一次字符串比较就放行。
 *     - 放行条件：我们自己点按键触发的那一次长按（armed = true，用完立刻清掉）。
 *     - 其余（用户自己长按）：setResult(null) 跳过原来的 show = 忽略，什么都不弹。
 *  2. 常驻按键：回到前台时（onResume）把按键挂到主界面 Activity 的窗口最上层（只有按键本身，没有全屏触摸层）；开关关了 / 不是主界面就拿掉。
 *     样式和右侧点赞 / 评论 / 分享一致：白色实心图标 + 下面一行白字（带阴影），竖着排。
 *  3. 点按键：armed = true，直接跑当前视频那个 LongPressLayout 里长按计时任务（Runnable）的 run()，没有延迟。
 *     依据（TextRec 40.9.0，textrec-tasks-20261025-102152）：长按时栈是 Handler → X.0tdv.run → Y.AhaS2700S0100000_34.LIZ(float, float)
 *     → X.10du.LIZIZ / LIZJ → LongPressPanelServiceImpl.LJIIZILJ → … → DialogFragment#show，全是同一个栈、同步；X.0tdv 在
 *     LongPressLayout.<init> 里创建，字段只有 LongPressLayout（指回控件）和 bytex 插桩字段。混淆名一律不用，用没混淆的类型找
 *     （见 findLongPressRunnable）。钩子放行这一次 show = 弹出当前视频原来的面板，面板内容 / 后续逻辑一点没改。
 *     找不到计时任务（类型对不上 / 有歧义）时退回「向窗口发一次合成长按」（有延迟）。
 *  4. 按键位置 lpX / lpY（dp，左 / 上，-1 = 默认在屏幕右侧），在抖音里用位置浮窗（VidPosPanel）选「按键」拖，和视频时长一样。
 * 没有轮询、没有定时器；钩子回调只在弹面板时才走到；点按键时遍历一次视图树找 LongPressLayout（用户点一次一次）。退路才会 postDelayed 一条「抬起」。
 *
 * 局限（没编译、没上机）：
 *  - 只在 40.9.0 一台手机上验证过钩点；类名 / 重载变了会安静失效（长按照常弹面板），设置页「钩子状态」里 lp.show 会显示未适配。
 *  - 直接 run() 没有上机验证：计时任务的 run() 如果还要读控件上「按下时记的状态」（按下位置、是否按着），没有真实触摸时可能什么都不做，点按键弹不出面板。
 *  - 同时有多个 LongPressLayout 时按「屏幕上可见面积最大」选；个人页、直播间里的子类也会被选中，它们的长按是不是同一个面板没验证。
 *  - 常驻按键挂在整个主界面上，主界面里切到个人页、搜索等别的页面时按键也在。
 *  - 跳过 show 后抖音自己有没有「面板正在显示」之类的状态没追踪过。
 */
final class LongPressHook {
    private LongPressHook() {}

    private static final String DF = "androidx.fragment.app.DialogFragment";
    private static final String FM = "androidx.fragment.app.FragmentManager";
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl";

    private static final XC_MethodHook HOOK = new Hook();

    /** 主界面 Activity 的类名（TextRec 40.9.0 textrec-tasks-20261025-102152：LongPressPanelLaunchParams(context=com.ss.android.ugc.aweme.main.MainActivity@…)）。 */
    private static final String MAIN_ACT = "com.ss.android.ugc.aweme.main.MainActivity";
    /** 长按识别的控件（没混淆的 View 类，每个视频页一个；个人页 / 直播的子类也是它）。 */
    private static final String LP_LAYOUT = "com.ss.android.ugc.aweme.feed.ui.LongPressLayout";

    /** 点按键触发的那一次长按放行（主线程读写）。 */
    private static boolean armed;
    private static WeakReference<View> btnRef;
    private static WeakReference<Activity> btnAct;

    /** 启动时和每次回到前台都会被调用；功能没开就不挂（没有结论，开了之后回到前台再挂）。 */
    static synchronized void tryHook(ClassLoader cl) {
        if (cl == null || !DanmakuHook.cfg.lpBtn) return;
        if (HookGuard.settled(HookGuard.LP_SHOW)) return;
        Class<?> df = HookGuard.find(HookGuard.LP_SHOW, DF, cl);
        if (df == null) return;
        int n = 0;
        try {
            for (Method m : df.getDeclaredMethods()) {
                if (!"show".equals(m.getName())) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length != 2 || t[1] != String.class || !FM.equals(t[0].getName())) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, HOOK);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) { // 方法签名里引用的类还没就绪
            HookGuard.waitFail(HookGuard.LP_SHOW);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.LP_SHOW, "");
        else HookGuard.miss(HookGuard.LP_SHOW, "DialogFragment 里没有 show(FragmentManager, String)（抖音 / androidx 改了，需要重新用 TextRec 追踪首页长按弹框）");
    }

    /** 回到前台（配置刚重读过）：开关开着且是主界面 → 挂 / 更新常驻按键；否则拿掉。 */
    static void onResume(Activity a) {
        try {
            boolean want = DanmakuHook.cfg.lpBtn && a != null && MAIN_ACT.equals(a.getClass().getName())
                    && !a.isFinishing() && !a.isDestroyed()
                    && HookGuard.isOk(HookGuard.LP_SHOW);
            remove();
            if (want) attach(a);
        } catch (Throwable ignored) {
        }
    }

    private static final class Hook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.lpBtn) return;
                Object t = p.thisObject;
                if (t == null || !PANEL.equals(t.getClass().getName())) return; // 别的 DialogFragment：放行
                if (armed) {                                          // 点按键触发的那一次：放行
                    armed = false;
                    return;
                }
                p.setResult(null);                                    // 用户自己长按：忽略
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 常驻按键

    private static void attach(Activity a) {
        View top = a.getWindow().getDecorView();
        if (!(top instanceof FrameLayout)) return;
        Context ctx = top.getContext();
        View b = makeButton(ctx);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    fire();
                } catch (Throwable ignored) {
                }
            }
        });
        DanmakuConfig c = DanmakuHook.cfg;
        ((FrameLayout) top).addView(b, place(ctx, c.lpX, c.lpY));
        btnRef = new WeakReference<View>(b);
        btnAct = new WeakReference<Activity>(a);
    }

    private static void remove() {
        View b = btnRef == null ? null : btnRef.get();
        btnRef = null;
        btnAct = null;
        if (b != null && b.getParent() instanceof ViewGroup) ((ViewGroup) b.getParent()).removeView(b);
    }

    /**
     * 点按键：放行下一次面板 show，然后直接跑当前视频那个 LongPressLayout 里的「长按计时任务」（Runnable）的 run()，
     * 同步走完抖音自己的长按流程，没有延迟；找不到计时任务时退回「合成一次长按」（有延迟）。
     */
    private static void fire() {
        final Activity a = btnAct == null ? null : btnAct.get();
        View b = btnRef == null ? null : btnRef.get();
        if (a == null || b == null || a.isFinishing() || a.isDestroyed()) return;
        View top = a.getWindow().getDecorView();
        Runnable r = findLongPressRunnable(top);
        if (r != null) {
            armed = true;
            try {
                r.run();
            } catch (Throwable ignored) {
            } finally {
                armed = false; // show 在 run 里同步调用，走完就不留放行标记
            }
            return;
        }
        fireSynthetic(a, b, top);
    }

    /**
     * 当前屏幕上的 LongPressLayout（可见面积最大的一个）里的长按计时任务。
     * 依据（TextRec 40.9.0，textrec-tasks-20261025-102152）：X.0tdv 实现 Runnable，在 LongPressLayout.&lt;init&gt; 里创建，
     * 字段只有 LongPressLayout（指回控件）和 bytex 的两个插桩字段；它的 run() 同步调到长按回调（x, y）→ 面板的 show，栈里没有别的异步。
     * 混淆名一律不用：在控件（及父类，不含 View）的字段里找「值是 Runnable、它的类里有一个字段类型是 LongPressLayout 且指回这个控件」的，
     * 找到不止一个（有歧义）或一个都没有就返回 null。
     */
    private static Runnable findLongPressRunnable(View root) {
        try {
            View[] best = new View[1];
            int[] area = new int[1];
            scan(root, best, area, new Rect());
            View v = best[0];
            if (v == null) return null;
            Class<?> base = null;
            for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
                if (LP_LAYOUT.equals(c.getName())) {
                    base = c;
                    break;
                }
            }
            if (base == null) return null;
            Runnable found = null;
            for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object o = f.get(v);
                    if (!(o instanceof Runnable) || o == found) continue;
                    if (!pointsBack(o, base, v)) continue;
                    if (found != null) return null; // 有歧义：一个都不动
                    found = (Runnable) o;
                }
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean pointsBack(Object o, Class<?> base, View owner) throws Exception {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != base || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.get(o) == owner) return true;
            }
        }
        return false;
    }

    /** 遍历窗口里的视图，挑出可见面积最大的 LongPressLayout（只在点按键那一下走一次）。 */
    private static void scan(View v, View[] best, int[] area, Rect tmp) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            if (LP_LAYOUT.equals(c.getName())) {
                if (v.isShown() && v.getGlobalVisibleRect(tmp)) {
                    int ar = tmp.width() * tmp.height();
                    if (ar > area[0]) {
                        area[0] = ar;
                        best[0] = v;
                    }
                }
                break;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) scan(g.getChildAt(i), best, area, tmp);
        }
    }

    /** 退路：向窗口发一次合成长按（按下 → 系统长按时间后抬起），有延迟。 */
    private static void fireSynthetic(final Activity a, View b, View top) {
        int w = top.getWidth(), h = top.getHeight();
        if (w <= 0 || h <= 0) return;
        float x = w * 0.5f, y = h * 0.4f;
        int[] loc = new int[2];
        b.getLocationInWindow(loc);
        if (x >= loc[0] && x <= loc[0] + b.getWidth() && y >= loc[1] && y <= loc[1] + b.getHeight()) y = h * 0.25f; // 躲开按键
        final float fx = x, fy = y;
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, fx, fy, 0);
        armed = true;
        a.dispatchTouchEvent(d);
        d.recycle();
        final long dt = down;
        b.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = SystemClock.uptimeMillis();
                    MotionEvent u = MotionEvent.obtain(dt, now, MotionEvent.ACTION_UP, fx, fy, 0);
                    a.dispatchTouchEvent(u);
                    u.recycle();
                } catch (Throwable ignored) {
                } finally {
                    armed = false; // 没弹出来也不留着放行标记，下一次用户长按照样忽略
                }
            }
        }, ViewConfiguration.getLongPressTimeout() + 400);
    }

    // ------------------------------------------------------------ 样式 / 位置（位置浮窗的预览也用）

    /** 按键的样子：和点赞 / 评论 / 分享一致（白色实心图标 + 下面一行白字，带阴影）；位置浮窗里的预览直接用它。 */
    static View makeButton(Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
        col.setClickable(true);
        col.addView(new Icon(ctx), new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));
        TextView t = new TextView(ctx);
        t.setText("面板");
        t.setTextSize(12);
        t.setTextColor(0xFFFFFFFF);
        t.setShadowLayer(dp(ctx, 2), 0, dp(ctx, 1), 0x99000000);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(ctx, 2);
        col.addView(t, tl);
        return col;
    }

    /** 白色实心「列表」图标：三行（点 + 横条），带阴影，和右侧其它图标同一个视觉重量。 */
    private static final class Icon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Icon(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            p.setColor(Color.WHITE);
            p.setShadowLayer(dp(c, 2), 0, dp(c, 1), 0x99000000);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float bar = h * 0.11f, rad = bar / 2f;
            float left = w * 0.14f, right = w * 0.86f, dot = bar * 1.0f;
            for (int i = 0; i < 3; i++) {
                float cy = h * (0.30f + 0.20f * i);
                cv.drawCircle(left + dot / 2f, cy, dot / 2f + 0.5f, p);
                r.set(left + dot * 2.2f, cy - rad, right, cy + rad);
                cv.drawRoundRect(r, rad, rad, p);
            }
        }
    }

    /** 按键在窗口里的位置：x / y 是左 / 上（dp，相对整个窗口），任何一个 < 0 = 默认（右侧、点赞列上方）；不会放到屏幕外面。 */
    static FrameLayout.LayoutParams place(Context ctx, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        if (x < 0 || y < 0) {
            lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            lp.rightMargin = dp(ctx, 8);
            lp.bottomMargin = dp(ctx, 330);
            return lp;
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = Math.min(dp(ctx, x), Math.max(0, dm.widthPixels - dp(ctx, 60)));
        lp.topMargin = Math.min(dp(ctx, y), Math.max(0, dm.heightPixels - dp(ctx, 48)));
        return lp;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
