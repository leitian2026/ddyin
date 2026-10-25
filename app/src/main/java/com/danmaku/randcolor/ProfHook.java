package com.danmaku.randcolor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 个人主页默认停在「喜欢」（1.39.2：只在点底部「我」时切一次；不再改写抖音自己的 setCurrentItem(0)。没有定时器、没有延时消息）。
 *
 * 为什么去掉改写（用户上机验证 + TextRec 40.9.0 textrec-tasks-20261025-134024）：1.36.x 起，点「我」之后到切回首页之前是一个「窗口」，
 * 窗口里抖音自己调的 setCurrentItem(0)（PandaProfileMyPager.LJJLJLI / LJJJJZ，栈里没有 DmtTabLayout）被我换成 99。结果：在「喜欢」里向上划之后，
 * 个人页的头部和标签栏再也回不来（ProfileViewPager 的可见面积从 3135600 变成 4590000 后 16 份视图树里再没变过，回首页再进「我」也没用）；
 * 关掉这个功能就正常。所以抖音自己的恢复一律不碰，这一整套（窗口、mineRef、看调用栈、DmtTabLayout 判断）都删了。
 *
 * 两件事：
 *  1. 触发 prof.enter = com.ss.android.ugc.aweme.main.MainPageFragment$2#onChanged(Object)：底部标签切换的观察者，参数是一个 Pair，toString 形如
 *     "(USER, TabRouteConfig(enterFrom='change_selected_bottom_tab', ...))"；第一项是 USER = 切到底部「我」。
 *  2. 动作：触发时对个人主页的分页控件调一次 setCurrentItem(99, false)；99 越界，ViewPager 自己收成最后一页（= 喜欢），不认标签、不扫视图树、不依赖标签个数。
 *     分页控件从哪来：prof.pager 挂 ViewPager#setCurrentItem 两个重载，认出 this 是 ProfileViewPager 就记一个弱引用（整个进程只建一次，只在换对象时才分配）；
 *     回调里只记对象，不改任何参数。触发时没有记下的对象 = 什么都不做。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261025-090731 / -134024，加上之前的 20261024-141908、20261023-231633、20261024-071912）：
 *  - MainPageFragment$2#onChanged：类名、方法名没混淆；点「我」调 (USER, …)，点「首页」调 (HOME, …)。
 *  - ViewPager#setCurrentItem(int) / (int, boolean) 是 androidx 声明的（没混淆），ProfileViewPager 不覆写；类名 com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager 没混淆。
 *  - 134024：点「我」时 onChanged(USER) 在 18420ms（耗时 497ms），我的 (99, false) 在 18907ms，之后「喜欢」页的 Fragment（FavProfileCommonTabFragment）才在 19006ms 出现。
 * 没有定时器、没有轮询、没有扫描；prof.enter 回调只在底部切页时才有（用户点一次一次），不是点击检测。
 *
 * 局限（没编译、没上机）：
 *  - 冷启动第一次进「我」时，触发那一下分页控件的页可能还没建好，99 没有效果，个人主页停在「作品」（安静失效，不再有任何补救，也不许有兜底）。
 *  - 只处理触发时记下的那一个分页控件；还没见过分页控件时什么都不做。
 *  - 参数是 Pair 这一点来自 toString 的格式，没有看到类名；回调里用 toString().startsWith("(USER,") 判断，格式变了就不触发（安静失效）。
 *    这个 toString 每次底部切页只做一次，不是热路径。
 *  - 「喜欢」是最后一页只有一个账号的证据。
 *  - 点「我」时的这一次 (99, false) 本身会不会也让头部联动出问题，没有单独验证（1.39.2 只去掉了改写）；如果还有，就是这一次切换造成的。
 * 没挂上时个人主页保持原样，安静失效。
 */
final class ProfHook {
    private ProfHook() {}

    private static final String PAGER = "com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager";
    private static final String VP = "androidx.viewpager.widget.ViewPager";
    private static final String ENTER = "com.ss.android.ugc.aweme.main.MainPageFragment$2";
    /** Pair.toString() 里第一项是 USER 的开头。 */
    private static final String USER_PREFIX = "(USER,";
    /** 越界页号：ViewPager 收到最后一页（= 喜欢）。 */
    private static final Integer LAST = 99;

    private static final XC_MethodHook PAGER_HOOK = new PagerHook();
    private static final XC_MethodHook ENTER_HOOK = new EnterHook();

    private static volatile WeakReference<Object> pagerRef;
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

    /** 记住个人主页的分页控件（只记对象，不改任何参数）。 */
    private static final class PagerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                Object o = p.thisObject;
                WeakReference<Object> r = pagerRef;
                if (r != null && r.get() == o) return;              // 已经记着这个对象
                if (!PAGER.equals(o.getClass().getName())) return;  // 别的 ViewPager 一次字符串比较就返回
                pagerRef = new WeakReference<Object>(o);
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
                if (!t.startsWith(USER_PREFIX)) return;                         // 不是切到「我」
                switchToLast();
            } catch (Throwable ignored) {
            }
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
