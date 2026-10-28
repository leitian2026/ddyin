package com.danmaku.randcolor;

import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 首页推荐流里的「直播预览卡」（带「直播中」标签和「点击进入直播间」按钮的那一页）也放音量条（1.44.3）。
 *
 * 为什么要单独一个钩子：这张卡不是视频，pub.bind（VideoViewHolder#bind）不会为它触发，VolPanel.page 拿不到它的页面根。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261028-164844，视图树 #1 / #4 / #6 的「父级链」）：直播卡里的文案控件往上，
 * ↑8 = com.ss.android.ugc.aweme.feed.ui.LivePreviewFrameLayout（id = root_view），↑9 = com.ss.android.ugc.aweme.common.widget.RTViewPager（id = viewpager，
 * 首页上下划的翻页容器）。也就是这个类是翻页容器的直接子控件，位置和视频页的 VideoViewHolderRootView 一样，当作这一页的根。
 * 类名没有混淆。没追踪过它的 bind / 构造时机，也没确认它是不是 FrameLayout（类名这么叫；不是的话 VolPanel.page 什么都不做）。
 *
 * 做法：钩 LivePreviewFrameLayout 的所有构造方法（after，只处理类正好是它的；和 LongPressHook.lp.lay 同一个写法），
 * 给每个实例挂一个单例的「挂载监听」；挂到窗口时（子控件这时已经都加完，音量条才能排在最后）调 VolPanel.page(它)，
 * 之后和视频页里的音量条一样：评论面板画在上面所以盖住它，随页面滑动，窗口那一份在页面条挂着时让位。
 * 回到前台（配置刚重读过）：onResume 把登记过的页面按新配置重放一遍（开关关了就撤掉）。
 *
 * 开关 volOn + volUnderCmt 没开（VolPanel.pageOn）时不挂这个钩子（没有结论，打开后回到前台再挂）。没有定时器 / 轮询。
 * 类没找到 → waitFail；类在但没有构造方法可挂 → miss。找不到页面根 / 不是 FrameLayout：安静失效，窗口那份照旧。
 */
final class LiveCardHook {
    private LiveCardHook() {}

    private static final String CLS = "com.ss.android.ugc.aweme.feed.ui.LivePreviewFrameLayout";
    private static final int MAX_ROOTS = 16;

    private static final XC_MethodHook HOOK = new CtorHook();
    private static volatile Class<?> cardCls;
    /** 见过的直播卡页面根（弱引用），回到前台按新配置重放；主线程用。 */
    private static final ArrayList<WeakReference<View>> ROOTS = new ArrayList<>();

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || HookGuard.settled(HookGuard.LIVE_CARD)) return;
        if (!VolPanel.pageOn()) return; // 音量条不放进页面里：不挂（没有结论，打开后回到前台再挂）
        Class<?> c = HookGuard.find(HookGuard.LIVE_CARD, CLS, cl);
        if (c == null) return;
        cardCls = c; // 先赋值再挂钩
        int n = 0;
        try {
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(k, HOOK);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) { // 构造方法签名里引用的类还没就绪
            cardCls = null;
            HookGuard.waitFail(HookGuard.LIVE_CARD);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.LIVE_CARD, "");
        else HookGuard.miss(HookGuard.LIVE_CARD, "LivePreviewFrameLayout 里没有构造方法可挂（直播预览卡的页面根变了，需要重新用 TextRec 导出推荐流直播卡的视图树）");
    }

    /** 回到前台（配置刚重读过）：见过的直播卡页面按新配置重放（VolPanel.page 里开关关了就撤掉、设置改了就重建）。 */
    static void onResume() {
        try {
            for (int i = ROOTS.size() - 1; i >= 0; i--) {
                View v = ROOTS.get(i).get();
                if (v == null) {
                    ROOTS.remove(i);
                    continue;
                }
                if (v.isAttachedToWindow()) VolPanel.page(v);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 直播卡的页面根构造完：挂上单例的挂载监听（不改控件、不改结果）。 */
    private static final class CtorHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object t = p.thisObject;
                if (t == null || t.getClass() != cardCls) return; // 子类 / 别的类：一次类比较就放行
                ((View) t).addOnAttachStateChangeListener(ATTACH);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            try {
                if (Looper.myLooper() != Looper.getMainLooper() || !(v instanceof FrameLayout)) return;
                boolean known = false;
                for (int i = ROOTS.size() - 1; i >= 0; i--) {
                    View r = ROOTS.get(i).get();
                    if (r == null) ROOTS.remove(i);
                    else if (r == v) known = true;
                }
                if (!known) {
                    if (ROOTS.size() >= MAX_ROOTS) ROOTS.remove(0); // 最早记下的那个
                    ROOTS.add(new WeakReference<>(v));
                }
                VolPanel.page(v);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
        }
    };
}
