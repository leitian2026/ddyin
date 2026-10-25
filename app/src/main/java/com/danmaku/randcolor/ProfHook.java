package com.danmaku.randcolor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 个人主页默认停在「喜欢」（1.36.0：不再数次数，按调用来源区分「抖音自己的恢复」和「你手点的标签」；1.36.1：抖音自己的恢复不再取消，改成把它要去的第 0 页换成最后一页（防冷启动第一次触发时页没建好；这一条只是推断，1.36.0 本身没有上机验证过）；没有定时器、没有延时消息）。
 *
 * 三件事：
 *  1. 触发 prof.enter = com.ss.android.ugc.aweme.main.MainPageFragment$2#onChanged(Object)：底部标签切换的观察者，参数是一个 Pair，toString 形如
 *     "(USER, TabRouteConfig(enterFrom='change_selected_bottom_tab', ...))"；第一项是 USER = 切到底部「我」，HOME = 切到首页。
 *  2. 动作：触发时对个人主页的分页控件调一次 setCurrentItem(99, false)；99 越界，ViewPager 自己收成最后一页（= 喜欢），不认标签、不扫视图树、不依赖标签个数。
 *     分页控件从哪来：prof.pager 挂 ViewPager#setCurrentItem 两个重载，认出 this 是 ProfileViewPager 就记一个弱引用（整个进程只建一次，只在换对象时才分配）。
 *     触发时没有记下的对象 = 什么都不做。
 *  3. 改写抖音自己的恢复：触发（USER）之后到切回首页（HOME）之前是「窗口」；窗口里，触发时记下的那个分页控件上的 setCurrentItem(0)，
 *     如果调用栈里没有类名以 com.bytedance.ies.dmt.ui.widget.tablayout.DmtTabLayout 开头的帧，就是抖音延迟投递的恢复，把参数 0 换成 99（最后一页 = 喜欢）再执行；
 *     栈里有这个类 = 标签栏自己选的（你手点「作品」就是这样），原样放行。只看类名，不用里面任何混淆的方法名。看栈抛异常就放行。
 *     为什么不取消（推断，没上机验证）：冷启动第一次，触发（6317ms 开始、6900ms 才返回）时分页控件的页可能还没建好，触发时那一下 99 就没有效果；
 *     抖音的恢复（7170 / 7179 / 7548ms）是页建好之后才来的，在它这一下上改目标，页一定在，不用数次数、不用延时。
 *     页已经在最后一页时，这一下 99 被 ViewPager 收成当前页，什么都不会发生，所以页本来就建好了的情况（热启动）也不受影响。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261025-090731，加上之前的 20261024-141908、20261023-231633、20261024-071912）：
 *  - MainPageFragment$2#onChanged：类名、方法名没混淆；点「我」调 (USER, …)，点「首页」调 (HOME, …)。
 *  - 冷启动第一次进「我」：onChanged(USER) 在 6317ms（耗时 583ms）；其后 ProfileViewPager 上的 setCurrentItem(0) 共 4 次：
 *      6559ms（栈里有 DmtTabLayout / DmtTabLayout$Tab，落在 onChanged 的耗时里，是它嵌套调用的，早于窗口开始）、
 *      7170ms（X.18rI.run ← CallbackRunnable.run）、7179ms 和 7548ms（PandaProfileMyPager.LJJLJLI ← X.18rL.run ← CallbackRunnable.run）——后 3 次的栈里都没有 DmtTabLayout。
 *  - 热启动再进「我」：onChanged(USER) 在 83944ms，之后只有 1 次 (0)，在 84472ms，栈同上（LJJLJLI ← X.18rL.run ← CallbackRunnable.run），没有 DmtTabLayout。
 *  - 你手点：「喜欢」= setCurrentItem(4)，栈里有 DmtTabLayout、DmtTabLayout$TabView.performClick；「作品」= setCurrentItem(0)，栈里有 DmtTabLayout、DmtTabLayout$Tab。
 *  - 进别人主页那一件事里，这个分页控件上没有 setCurrentItem。整个记录里 this 都是同一个 ProfileViewPager 对象。
 *  - ViewPager#setCurrentItem(int) / (int, boolean) 是 androidx 声明的（没混淆），ProfileViewPager 不覆写；类名 com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager 没混淆。
 *    DmtTabLayout 的类名没混淆（TextRec 只是把里面的 LJIL / LIZLLL 方法标成混淆，这里不用方法名）。
 * 没有定时器、没有轮询、没有扫描；prof.enter 回调只在底部切页时才有（用户点一次一次），不是点击检测；prof.pager 回调里：别的 ViewPager 一次字符串比较就返回，
 * 窗口外 / 不是记下的那个控件 / 参数不是 0 都只比较引用或一个整数，只有窗口里的 (0) 才看一次调用栈（每次进「我」只有几次）。
 *
 * 局限（没编译、没上机）：
 *  - 只在 40.9.0 一个版本、一台手机上验证过栈的形状；换版本后 DmtTabLayout 这个类名没了 / 恢复也走了 DmtTabLayout，会安静失效（停回「作品」，或你手点「作品」被当成恢复改走——后者不会发生，因为手点一定过 DmtTabLayout）。
 *  - 窗口里抖音如果自己又从非标签栏的地方调 (0)（比如别的功能要回到「作品」），也会被改成最后一页，直到切回首页。
 *  - 只处理触发时记下的那一个分页控件；触发之后抖音把分页控件重建成新对象，恢复不会被改（停在「作品」，安静失效）。
 *  - 参数是 Pair 这一点来自 toString 的格式，没有看到类名；回调里用 toString().startsWith("(USER,") / ("(HOME,") 判断，格式变了就不触发（安静失效）。
 *    这个 toString 每次底部切页只做一次，不是热路径。
 *  - 「喜欢」是最后一页只有一个账号的证据。
 * 没挂上时个人主页保持原样，安静失效。
 */
final class ProfHook {
    private ProfHook() {}

    private static final String PAGER = "com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager";
    private static final String VP = "androidx.viewpager.widget.ViewPager";
    private static final String ENTER = "com.ss.android.ugc.aweme.main.MainPageFragment$2";
    /** Pair.toString() 里第一项是 USER 的开头。 */
    private static final String USER_PREFIX = "(USER,";
    /** Pair.toString() 里第一项是 HOME 的开头（切到首页）。 */
    private static final String HOME_PREFIX = "(HOME,";
    /** 越界页号：ViewPager 收到最后一页（= 喜欢）。 */
    private static final Integer LAST = 99;

    /** 标签栏的类名（含内部类）：栈里有它 = 标签栏自己选的页（手点标签）；没有 = 抖音延迟投递的恢复。只用类名，不用里面的混淆方法名。 */
    private static final String TABLAYOUT = "com.bytedance.ies.dmt.ui.widget.tablayout.DmtTabLayout";

    private static final XC_MethodHook PAGER_HOOK = new PagerHook();
    private static final XC_MethodHook ENTER_HOOK = new EnterHook();

    private static volatile WeakReference<Object> pagerRef;
    /** 窗口：触发（USER）之后到切回首页（HOME）之前（主线程读写）。 */
    private static boolean window;
    /** 触发时记下的那个分页控件：窗口里只处理它（主线程读写）。 */
    private static WeakReference<Object> mineRef;
    private static volatile Method setItem;

    /** 启动时和每次回到前台都会被调用；功能没开就不挂（没有结论，开了之后回到前台再挂）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || !DanmakuHook.cfg.profFav) return;
        tryPager(cl);
        tryEnter(cl);
    }

    private static synchronized void tryPager(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.PROF_PAGER)) return;
        // 只找 androidx 的 ViewPager（启动就在）。ProfileViewPager 要等第一次进个人主页才加载，类名在回调里按字符串比，不在这里 Class.forName。
        Class<?> vp = HookGuard.find(HookGuard.PROF_PAGER, VP, cl);
        if (vp == null) return;
        Method a, b;
        try {
            a = vp.getDeclaredMethod("setCurrentItem", int.class);
            b = vp.getDeclaredMethod("setCurrentItem", int.class, boolean.class);
        } catch (NoSuchMethodException e) {
            HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager 里没有 setCurrentItem(int) / (int, boolean)（需要重新用 TextRec 追踪个人主页切标签）");
            return;
        }
        try {
            setItem = b;
            XposedBridge.hookMethod(a, PAGER_HOOK);
            XposedBridge.hookMethod(b, PAGER_HOOK);
        } catch (Throwable t) {
            setItem = null;
            HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager#setCurrentItem 挂不上：" + t);
            return;
        }
        HookGuard.ok(HookGuard.PROF_PAGER, "");
    }

    private static void tryEnter(ClassLoader cl) {
        HookGuard.hook(HookGuard.PROF_ENTER, ENTER, HookGuard.named("onChanged"), ENTER_HOOK,
                "MainPageFragment$2 里没有 onChanged（抖音改了底部标签切换的观察者，需要重新用 TextRec 追踪点底部「我」）", cl);
    }

    /** 记住个人主页的分页控件；窗口里把抖音自己恢复标签的 (0)（栈里没有 DmtTabLayout 的）改成最后一页。 */
    private static final class PagerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                Object o = p.thisObject;
                WeakReference<Object> r = pagerRef;
                if (r == null || r.get() != o) {                    // 没记着这个对象：才比类名（别的 ViewPager 一次字符串比较就返回）
                    if (!PAGER.equals(o.getClass().getName())) return;
                    pagerRef = new WeakReference<Object>(o);
                }
                if (!window) return;                                // 不在窗口里：什么都不改
                WeakReference<Object> m = mineRef;
                if (m == null || m.get() != o) return;              // 只处理触发时记下的那个分页控件
                Object a = p.args[0];
                if (!(a instanceof Integer) || ((Integer) a).intValue() != 0) return; // 只看 (0)；我们自己的 99 放过
                if (fromTabLayout()) return;                        // 标签栏自己选的（手点标签）：放行
                p.args[0] = LAST;                                   // 抖音自己的恢复：把它要去的「作品」换成最后一页（喜欢）
            } catch (Throwable ignored) {
            }
        }
    }

    /** 点底部「我」：切到最后一页。 */
    private static final class EnterHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                Object a = p.args[0];
                if (a == null) return;
                String t = a.toString();
                if (t.startsWith(HOME_PREFIX)) {                                // 切到首页：窗口关闭
                    window = false;
                    return;
                }
                if (!t.startsWith(USER_PREFIX)) return;                         // 不是切到「我」
                mineRef = pagerRef;                                             // 窗口里只处理这个分页控件
                window = true;
                switchToLast();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 调用栈里有没有标签栏（DmtTabLayout，含内部类）的帧。只在窗口里的 (0) 被调到时才看；取栈抛异常就当有（放行）。 */
    private static boolean fromTabLayout() {
        try {
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                if (e.getClassName().startsWith(TABLAYOUT)) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 对记下的分页控件切到最后一页。 */
    private static void switchToLast() {
        try {
            if (!DanmakuHook.cfg.profFav) return;
            WeakReference<Object> r = pagerRef;
            Object pager = r != null ? r.get() : null;
            Method m = setItem;
            if (pager == null || m == null) return; // 还没见过分页控件：什么都不做
            m.invoke(pager, LAST, Boolean.FALSE);   // 越界 → 最后一页（喜欢）；不演滑动动画
        } catch (Throwable ignored) {
        }
    }
}
