package com.danmaku.randcolor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 *  3. lp.lay = LongPressLayout 的构造方法（没混淆的 View 类，所有重载），after（1.39.3 起，1.39.4 改）：给这个控件挂一个单例的 OnAttachStateChangeListener；
 *     控件挂到窗口时（预加载页挂载时）登记「这一页的页面根 → 这一页的长按计时任务」，点按键时不扫描。
 *     依据（TextRec 40.9.0，textrec-tasks-20261025-102152，见 README 1.38.0）：长按时栈是 Handler → X.0tdv.run → Y.AhaS2700S0100000_34.LIZ(float, float)
 *     → X.10du.LIZIZ / LIZJ → LongPressPanelServiceImpl.LJIIZILJ → … → DialogFragment#show，全是同一个栈、同步；X.0tdv 在
 *     LongPressLayout.<init> 里创建，字段只有 LongPressLayout（指回控件）和 bytex 插桩字段。混淆名一律不用：第一次登记时，在 LongPressLayout（及父类，
 *     不含 View）的字段里找「值是 Runnable、它的类里有一个字段类型是 LongPressLayout 且指回这个控件」的那个，只有一个才认（Field 缓存，之后每页一次 Field.get）；
 *     有歧义就 miss（lp.lay 记成未适配）、一个都不登记。
 *     页面根 = com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView（RTViewPager 的直接子控件，每页一个，类名没混淆；TextRec 视图树里
 *     「FeedRightScaleView 在 MonitorScrollFrameLayout 里、MonitorScrollFrameLayout 在 VideoViewHolderRootView 里」）。按键和 LongPressLayout 各自往上找这个页面根（最多 16 层、按类名）作为同一页的凭据。
 *  4. 点按键：armed = true，直接 run() 按键自己这一页的计时任务，没有延迟、没有任何搜索：不遍历视图树、不反射、不在列表里挑——一次 Map 取值。
 *     钩子放行这一次 show = 弹出这一页视频原来的面板，面板内容 / 后续逻辑一点没改。
 *     不许有任何兜底：这一页没登记 / 计时任务已被回收 / run() 没弹出面板，就什么都不做（1.39.1 起没有「合成长按」退路了）。
 *  5. 拦截长按（lp.show）只在点按键这条路通着时才做：lp.col（按键放得进去）和 lp.lay（拿得到计时任务、至少登记过一页）都有结论是可用，才忽略用户的长按；
 *     否则长按照常弹面板（1.39.3 起；之前 lp.col 没挂上也会忽略长按，又没有按键可点）。
 * 没有轮询、没有定时器；lp.show 的回调只在弹面板时才走到实处；lp.col 的回调每张图都进（一次类比较就放行）；
 * lp.lay 的回调只在构造一个 LongPressLayout 时走到（每个视频页一次，翻页复用的页不再构造）：挂一个单例监听；监听在控件挂到窗口时登记一次（第一次解析字段是一次性反射，之后一个 Field.get + 最多 16 层往上找页面根）；
 * 这是「每页一次」的事，不是每次触摸，也不是点按键时。回调里只有第一次认头像时按资源名解析一次 id（getIdentifier，一次性），放按键时 new 一个 View（一页一次）。
 *
 * 局限（没编译、没上机）：
 *  - 只在 40.9.0 一台手机上验证过钩点；类名 / 重载变了会安静失效（长按照常弹面板，或没有按键），设置页「钩子状态」里 lp.show / lp.col 会显示未适配。
 *  - 没验证：这一列是不是贴底对齐（贴底的话多一个子项是列往上长、头像不动；如果是贴顶，头像会被往下挤一个按键的高度）。
 *  - 没验证：同一页里头像换作者时 setController 会不会再调（只在第一次绑定时放，之后列里已经有按键就不动）。
 *  - 没验证：直播间 / 个人页里的视频是不是也用同一个 FeedRightScaleView；用的话那里也会有按键。
 *  - 直接 run() 没有上机验证：计时任务的 run() 如果还要读控件上「按下时记的状态」（按下位置、是否按着），没有真实触摸时可能什么都不做，点按键弹不出面板。
 *  - 没验证：LongPressLayout 是不是在页面根（VideoViewHolderRootView）里面。只有「每页一个（视图树里 LongPressLayout 被加了 3 次，对应常驻 3 页）」这一条旁证；
 *    如果它不在页面根里，往上找不到页面根，这一页就不登记，lp.lay 不会记成未适配，只是点按键没反应（长按仍照常弹——一页都没登记时不拦截；有别的页登记过就会拦）。
 *  - 登记靠 LongPressLayout 的构造方法 + 挂到窗口监听：开关第一次打开（或模块第一次装上）之前就已经建好的页没挂监听，不会登记，要强杀抖音重开才生效。
 *  - 个人页、直播间里 LongPressLayout 的子类也会挂监听；它们不在 VideoViewHolderRootView 里时往上找不到，不登记。
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
        tryLay(cl);
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
                // 点按键这条路没通（按键放不进去 / 拿不到计时任务）：长按照常弹出，不改抖音原来的行为
                if (!anyTimer || !HookGuard.isOk(HookGuard.LP_COL) || !HookGuard.isOk(HookGuard.LP_LAY)) return;
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
        View root = pageRoot(col);                                           // 这一页的页面根：点按键时用它直接取这一页的计时任务
        if (root == null) return;
        PanelBtn b = new PanelBtn(col.getContext());
        b.page = new WeakReference<View>(root);
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
     * 点按键：放行下一次面板 show，直接跑「这一页」的长按计时任务（Runnable）的 run()，没有延迟。
     * 点按键时没有任何搜索：不遍历视图树、不反射、不在列表里挑——按键在放进列的那一刻就记下了自己这一页的页面根，
     * 页面根 → 计时任务 是 lp.lay 在这一页的 LongPressLayout 挂到窗口时直接登记的，这里只是一次 Map 取值。
     * 不许有任何兜底（用户明确要求）：这一页没登记 / 计时任务已被回收 / run() 没弹出面板，都什么都不做，不发合成触摸、不换别的办法。
     */
    private static void fire(View b) {
        if (!(b instanceof PanelBtn) || !b.isAttachedToWindow()) return;
        WeakReference<View> w = ((PanelBtn) b).page;
        View root = w == null ? null : w.get();
        if (root == null) return;
        Runnable r;
        synchronized (PAGES) {
            WeakReference<Runnable> t = PAGES.get(root);
            r = t == null ? null : t.get();
        }
        if (r == null) return;
        armed = true;
        try {
            r.run();
        } catch (Throwable ignored) {
        } finally {
            armed = false; // show 在 run 里同步调用，走完就不留放行标记
        }
    }

    // ------------------------------------------------------------ 挂到窗口时登记这一页的长按计时任务（lp.lay）

    /** 页面根（RTViewPager 的直接子控件，每页一个，类名没混淆；依据见 PubHook 头部注释和 TextRec 视图树）→ 这一页的长按计时任务（弱引用）。预加载页可能在别的线程构造 / 挂载，所以访问都要加锁。 */
    private static final java.util.WeakHashMap<View, WeakReference<Runnable>> PAGES = new java.util.WeakHashMap<>();
    private static final String ROOT = "com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView";
    /** lp.lay 认的类（先赋值再挂钩）；计时任务字段（第一次解析后缓存）；解析有歧义 = 失败；至少登记过一页。 */
    private static volatile Class<?> layCls;
    private static volatile Field timerField;
    private static volatile boolean timerFailed, anyTimer;

    private static void tryLay(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.LP_LAY)) return;
        Class<?> c = HookGuard.find(HookGuard.LP_LAY, LP_LAYOUT, cl);
        if (c == null) return;
        layCls = c;
        int n = 0;
        try {
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(k, LAY_HOOK);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) { // 构造方法签名里引用的类还没就绪
            HookGuard.waitFail(HookGuard.LP_LAY);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.LP_LAY, "");
        else HookGuard.miss(HookGuard.LP_LAY, "LongPressLayout 里没有可挂的构造方法（抖音改了，需要重新用 TextRec 追踪长按计时任务的创建）");
    }

    /** 构造完：给这个控件挂一个「挂到窗口时登记」的监听（单例，不分配）；不需要 LongPressLayout 自己覆写 onAttachedToWindow。 */
    private static final class LayHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Class<?> lc = layCls;
                Object t = p.thisObject;
                if (lc == null || timerFailed || !(t instanceof View) || !lc.isInstance(t)) return;
                if (!DanmakuHook.cfg.lpBtn) return;
                ((View) t).addOnAttachStateChangeListener(ATTACH);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final XC_MethodHook LAY_HOOK = new LayHook();

    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            try {
                Class<?> lc = layCls;
                if (lc == null || timerFailed || !DanmakuHook.cfg.lpBtn) return;
                Field f = timerField;
                if (f == null) {
                    f = resolveTimer(v, lc); // 第一次：在字段里找计时任务（一次性）
                    if (f == null) return;
                    timerField = f;
                }
                Object o = f.get(v);
                View root = pageRoot(v);
                if (!(o instanceof Runnable) || root == null) return;
                synchronized (PAGES) {
                    PAGES.put(root, new WeakReference<Runnable>((Runnable) o));
                }
                anyTimer = true;
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
        }
    };

    /** 往上找页面根（最多 16 层，按类名；没有就 null）。 */
    private static View pageRoot(View v) {
        for (int i = 0; i < 16 && v != null; i++) {
            if (ROOT.equals(v.getClass().getName())) return v;
            ViewParent q = v.getParent();
            v = q instanceof View ? (View) q : null;
        }
        return null;
    }

    /**
     * 在 LongPressLayout（及父类，不含 View）的字段里找长按计时任务：值是 Runnable、它的类里有一个字段类型是 LongPressLayout 且指回这个控件。
     * 只有一个才返回；没有返回 null（这一次不登记）；有两个以上 = 有歧义，记成未适配、以后一个都不记。
     */
    private static Field resolveTimer(View v, Class<?> base) throws Exception {
        Field found = null;
        for (Class<?> c = base; c != null && c != View.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object o = f.get(v);
                if (!(o instanceof Runnable) || !pointsBack(o, base, v)) continue;
                if (found != null) {
                    timerFailed = true;
                    HookGuard.miss(HookGuard.LP_LAY, "LongPressLayout 里有不止一个像长按计时任务的字段（有歧义，一个都不记；需要重新用 TextRec 追踪）");
                    return null;
                }
                found = f;
            }
        }
        return found;
    }

    private static boolean pointsBack(Object o, Class<?> base, View owner) throws Exception {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != base || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.get(o) == owner) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 按键的样子

    /** 按键：和点赞 / 评论 / 分享一致（白色实心图标 + 下面一行白字，带阴影）；类本身也是「列里已经有按键了」的标记。 */
    private static final class PanelBtn extends LinearLayout {
        /** 这个按键所在页的页面根（点按键时直接用它取计时任务）。 */
        WeakReference<View> page;

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
