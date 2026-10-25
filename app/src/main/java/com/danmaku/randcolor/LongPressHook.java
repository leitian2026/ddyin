package com.danmaku.randcolor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 首页长按弹出的面板（分享给朋友 / 不感兴趣 …）改成：长按什么都不做（忽略），面板只能点一个常驻按键弹出；
 * 按键放在右侧按钮列里、作者头像的正上方，和头像一起走（1.39.0 起；之前是盖在窗口上、位置自己调，那套位置调节整个删了）。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261025-094241，首页长按两次、点空白处关一次、按返回关一次）：
 *  - 长按弹出的面板是 com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl（类名没混淆），是个 DialogFragment；
 *    弹出的入口 = androidx.fragment.app.DialogFragment#show(FragmentManager, String)，this = LongPressPanelFragmentImpl，
 *    参数 (FragmentManager{… in HostCallbacks{…}}, "LongPressPanelFragment")；返回 void（TextRec 标明可以 setResult(null) 整个跳过）。
 *  - 长按没有走 View#performLongClick（那个钩点整个任务里 0 次），所以不在长按手势上做文章，只管面板自己的 show。
 *  - 追踪里只见到 (FragmentManager, String) 这一个重载，只挂这一个。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261025-131001，视图树 / 点击入口 / 图标记录；首页翻视频、点头像、点关注加号、进评论再返回）：
 *  - 作者头像 = com.ss.android.ugc.aweme.base.ui.SmartAvatarBorderView，资源名 user_avatar（128x128，类名没混淆）。
 *    父级链（下 → 上）：RelativeLayout #i=i（153x229，头像在里面，边距 13,50）→ FrameLayout #b15 → FrameLayout #b16 →
 *    com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView #x2k（右侧点赞 / 评论 / 收藏 / 分享那一列，竖向 LinearLayout，wrap × wrap，
 *    类名没混淆）。#b16 是这一列的第 6 个子项（下标 5），它上面是几个 GONE 的占位。
 *  - 头像在 #i=i 里的位置不变；位置变的是整列：同一台手机上这一列 y 从 1538 变成 1448（头像 1588 → 1498，关注加号 #j-q 同步），x 恒定。
 *    所以「跟着头像走」就是做这一列的子项：放在头像所在的那一项前面，由 LinearLayout 布局自然跟随，不用算坐标，也不用挂位置相关的钩子。
 *  - 头像图片的设置入口：com.facebook.drawee.view.DraweeView#setController(DraweeController)（TextRec 标 [稳定]，栈第 1 层，
 *    main 线程；栈上还有 SmartCircleImageView#setController，同样没混淆，它调到这里）。user_avatar 那一条在 5 个任务阶段里共被设置十几次
 *    （每个视频页绑定头像时），所以第一次绑定就能把按键放进这一页的列。
 *
 * 做法：
 *  1. lp.show = DialogFragment#show 的 (FragmentManager, String) 重载，before：this 的类名是 LongPressPanelFragmentImpl 才处理，
 *     别的 DialogFragment 一次字符串比较就放行。
 *     - 放行条件：我们自己点按键触发的那一次长按（armed = true，用完立刻清掉）。
 *     - 其余（用户自己长按）：setResult(null) 跳过原来的 show = 忽略，什么都不弹。
 *  2. lp.col = DraweeView#setController，after：this 的类就是 SmartAvatarBorderView 且 id 是 user_avatar，才往上找 FeedRightScaleView，
 *     列里还没有我们的按键就 addView 到头像那一项前面（一页一次）。别的 Drawee 控件一次类比较就放行（这个方法每张图都会调到）。
 *     开关 lpBtn 关着、lp.show 没挂上、不在主线程都不放。回到前台（onResume）时按开关把已放的按键显示 / 隐藏（GONE，不占位）。
 *  3. 点按键：armed = true，直接跑当前视频那个 LongPressLayout 里长按计时任务（Runnable）的 run()，没有延迟。
 *     依据（TextRec 40.9.0，textrec-tasks-20261025-102152）：长按时栈是 Handler → X.0tdv.run → Y.AhaS2700S0100000_34.LIZ(float, float)
 *     → X.10du.LIZIZ / LIZJ → LongPressPanelServiceImpl.LJIIZILJ → … → DialogFragment#show，全是同一个栈、同步；X.0tdv 在
 *     LongPressLayout.<init> 里创建，字段只有 LongPressLayout（指回控件）和 bytex 插桩字段。混淆名一律不用，用没混淆的类型找
 *     （见 findLongPressRunnable）。钩子放行这一次 show = 弹出当前视频原来的面板，面板内容 / 后续逻辑一点没改。
 *     不许有任何兜底：找不到计时任务（类型对不上 / 有歧义）或 run() 没弹出面板，就什么都不做（1.39.1 起没有「合成长按」退路了）。
 * 没有轮询、没有定时器；两个钩子的回调只在弹面板 / 绑头像时才走到实处；点按键时遍历一次视图树找 LongPressLayout（用户点一次一次）。
回调里只有第一次认头像时按资源名解析一次 id（getIdentifier，一次性），放按键时 new 一个 View（一页一次）。
 *
 * 局限（没编译、没上机）：
 *  - 只在 40.9.0 一台手机上验证过钩点；类名 / 重载变了会安静失效（长按照常弹面板，或没有按键），设置页「钩子状态」里 lp.show / lp.col 会显示未适配。
 *  - 没验证：这一列是不是贴底对齐（贴底的话多一个子项是列往上长、头像不动；如果是贴顶，头像会被往下挤一个按键的高度）。
 *  - 没验证：同一页里头像换作者时 setController 会不会再调（只在第一次绑定时放，之后列里已经有按键就不动）。
 *  - 没验证：直播间 / 个人页里的视频是不是也用同一个 FeedRightScaleView；用的话那里也会有按键。
 *  - 直接 run() 没有上机验证：计时任务的 run() 如果还要读控件上「按下时记的状态」（按下位置、是否按着），没有真实触摸时可能什么都不做，点按键弹不出面板。
 *  - 同时有多个 LongPressLayout 时按「屏幕上可见面积最大」选；个人页、直播间里的子类也会被选中，它们的长按是不是同一个面板没验证。
 *  - 跳过 show 后抖音自己有没有「面板正在显示」之类的状态没追踪过。
 */
final class LongPressHook {
    private LongPressHook() {}

    private static final String DF = "androidx.fragment.app.DialogFragment";
    private static final String FM = "androidx.fragment.app.FragmentManager";
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl";

    private static final String DRAWEE = "com.facebook.drawee.view.DraweeView";
    private static final String AVATAR = "com.ss.android.ugc.aweme.base.ui.SmartAvatarBorderView";
    private static final String AVATAR_ID = "user_avatar";
    private static final String COLUMN = "com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView";

    private static final XC_MethodHook HOOK = new Hook();
    private static final XC_MethodHook COL_HOOK = new ColHook();

    /** 长按识别的控件（没混淆的 View 类，每个视频页一个；个人页 / 直播的子类也是它）。 */
    private static final String LP_LAYOUT = "com.ss.android.ugc.aweme.feed.ui.LongPressLayout";

    /** 点按键触发的那一次长按放行（主线程读写）。 */
    private static boolean armed;

    /** lp.col 要认的两个类（先赋值再挂钩）；头像的资源 id（-1 = 还没解析，0 = 解析不到）。 */
    private static volatile Class<?> avatarCls, colCls;
    private static int avatarId = -1;
    /** 已经放进列里的按键（主线程读写）；回到前台时按开关显示 / 隐藏。 */
    private static final ArrayList<WeakReference<View>> BTNS = new ArrayList<>();

    /** 启动时和每次回到前台都会被调用；功能没开就不挂（没有结论，开了之后回到前台再挂）。 */
    static synchronized void tryHook(ClassLoader cl) {
        if (cl == null || !DanmakuHook.cfg.lpBtn) return;
        tryShow(cl);
        tryCol(cl);
    }

    private static void tryShow(ClassLoader cl) {
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

    private static void tryCol(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.LP_COL)) return;
        Class<?> av = HookGuard.find(HookGuard.LP_COL, AVATAR, cl);
        if (av == null) return;
        Class<?> col = HookGuard.find(HookGuard.LP_COL, COLUMN, cl);
        if (col == null) return;
        avatarCls = av;
        colCls = col;
        HookGuard.hook(HookGuard.LP_COL, DRAWEE, HookGuard.named("setController"), COL_HOOK,
                "DraweeView 里没有 setController（抖音 / fresco 改了，需要重新用 TextRec 追踪作者头像 user_avatar 的图片设置）", cl);
    }

    /** 回到前台（配置刚重读过）：按开关把已经放进列里的按键显示 / 隐藏（GONE 不占位）。 */
    static void onResume() {
        try {
            int vis = DanmakuHook.cfg.lpBtn ? View.VISIBLE : View.GONE;
            for (int i = BTNS.size() - 1; i >= 0; i--) {
                View b = BTNS.get(i).get();
                if (b == null || b.getParent() == null) {
                    BTNS.remove(i);
                    continue;
                }
                if (b.getVisibility() != vis) b.setVisibility(vis);
            }
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

    // ------------------------------------------------------------ 按键放进右侧按钮列

    private static final class ColHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object t = p.thisObject;
                if (t == null || t.getClass() != avatarCls) return;            // 别的 Drawee 控件：一次类比较就放行
                if (!DanmakuHook.cfg.lpBtn) return;
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                View v = (View) t;
                if (!isUserAvatar(v)) return;
                ensureButton(v);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 资源名是 user_avatar（作者头像；同一个类还有别的头像，id 不同）。id 第一次用到时按名字解析一次。 */
    private static boolean isUserAvatar(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        if (avatarId == -1) avatarId = v.getResources().getIdentifier(AVATAR_ID, "id", v.getContext().getPackageName());
        return id == avatarId;
    }

    /** 头像往上找右侧按钮列；列里还没有按键就放到头像那一项前面（头像容器 #b16 之前）。 */
    private static void ensureButton(View avatar) {
        Class<?> cc = colCls;
        if (cc == null) return;
        View child = avatar;
        ViewGroup col = null;
        for (int i = 0; i < 8; i++) {
            ViewParent q = child.getParent();
            if (!(q instanceof View)) return;
            if (q.getClass() == cc) {
                col = (ViewGroup) q;
                break;
            }
            child = (View) q;
        }
        if (!(col instanceof LinearLayout)) return;
        int idx = col.indexOfChild(child);
        if (idx < 0) return;
        for (int i = 0, n = col.getChildCount(); i < n; i++) {
            if (col.getChildAt(i) instanceof PanelBtn) return;                // 这一页已经有了
        }
        if (!HookGuard.isOk(HookGuard.LP_SHOW)) return;                      // 拦截没挂上，放按键没有意义
        PanelBtn b = new PanelBtn(col.getContext());
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    fire(v);
                } catch (Throwable ignored) {
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(col.getContext(), 2);
        col.addView(b, idx, lp);
        BTNS.add(new WeakReference<View>(b));
    }

    /**
     * 点按键：放行下一次面板 show，然后直接跑当前视频那个 LongPressLayout 里的「长按计时任务」（Runnable）的 run()，
     * 同步走完抖音自己的长按流程，没有延迟。
     * 不许有任何兜底（用户明确要求）：找不到控件 / 找不到计时任务 / 有歧义 / run() 没弹出面板，都什么都不做，不发合成触摸、不换别的办法。
     */
    private static void fire(View b) {
        View top = b.getRootView();
        if (top == null || !b.isAttachedToWindow()) return;
        View lv = pickLayout(top);
        Runnable r = lv == null ? null : findLongPressRunnable(lv);
        if (r == null) return;
        armed = true;
        try {
            r.run();
        } catch (Throwable ignored) {
        } finally {
            armed = false; // show 在 run 里同步调用，走完就不留放行标记
        }
    }

    /**
     * 当前屏幕上的 LongPressLayout（可见面积最大的一个）里的长按计时任务。
     * 依据（TextRec 40.9.0，textrec-tasks-20261025-102152）：X.0tdv 实现 Runnable，在 LongPressLayout.&lt;init&gt; 里创建，
     * 字段只有 LongPressLayout（指回控件）和 bytex 的两个插桩字段；它的 run() 同步调到长按回调（x, y）→ 面板的 show，栈里没有别的异步。
     * 混淆名一律不用：在控件（及父类，不含 View）的字段里找「值是 Runnable、它的类里有一个字段类型是 LongPressLayout 且指回这个控件」的，
     * 找到不止一个（有歧义）或一个都没有就返回 null。
     */
    private static View pickLayout(View root) {
        try {
            View[] best = new View[1];
            int[] area = new int[1];
            scan(root, best, area, new Rect());
            return best[0];
        } catch (Throwable t) {
            return null;
        }
    }

    private static Runnable findLongPressRunnable(View v) {
        try {
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

    // ------------------------------------------------------------ 按键的样子

    /** 按键：和点赞 / 评论 / 分享一致（白色实心图标 + 下面一行白字，带阴影）；类本身也是「列里已经有按键了」的标记。 */
    private static final class PanelBtn extends LinearLayout {
        PanelBtn(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
            setClickable(true);
            addView(new Icon(ctx), new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));
            TextView t = new TextView(ctx);
            t.setText("面板");
            t.setTextSize(12);
            t.setTextColor(0xFFFFFFFF);
            t.setShadowLayer(dp(ctx, 2), 0, dp(ctx, 1), 0x99000000);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.topMargin = dp(ctx, 2);
            addView(t, tl);
        }
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

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
