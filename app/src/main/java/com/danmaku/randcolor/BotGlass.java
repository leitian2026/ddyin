package com.danmaku.randcolor;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 首页底部导航（首页 / 朋友 / [+] / 消息 / 我）的液态玻璃：画法和顶部标签完全一样（TabGlass.ItemGlass 每个按钮一块、整行一块），
 * 用同一套设置（tabRowBg / tabRowColor / tabItemBg / tabItemColor / tabItemH / tabGap / tabRowPadX / tabRowPadY），只多一个总开关 botGlass。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261028-093215，点击视图树 + 状态记录）：
 *  - 底部导航 = MainBottomTabContainer > … > LinearLayout#root_view（横，5 个子）> 5 个 com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab（类名没混淆，各 216×122，权重 1）。
 *  - 每个文字按钮里：BottomTab > FrameLayout#1p2 > RelativeLayout#glv > RelativeLayout#content_layout > RelativeLayout#u2- > AntiBurnDuxTextView（文字「首页」「朋友」「消息」「我」）。
 *    文字离 BottomTab 正好 5 层。中间的「+」（拍摄）是图片，没有这个文字控件。
 *  - 选中状态 = 这个文字控件的透明度：选中 1.0，没选中 0.55 / 0.65；切换时有一段渐变（0.63 → 0.73 → 0.81 → 0.95 → 1.0）。
 *    谁改的：栈第 1 层是 com.bytedance.dux.text.DuxTextView#setAlpha(float)（通用控件方法，TextRec 标「稳定」），业务调用在 MPFBottomTabComponent$initObservers$1#onChanged（LiveData 通知，稳定）。
 *  - 底部这几个按钮没有 setContentDescription 记录，所以不能像顶部标签那样靠它。
 * 所以这里钩 DuxTextView#setAlpha：回调里先比较 this 是不是 AntiBurnDuxTextView，再沿父级数 5 层看是不是 BottomTab（顶部标签里的 AntiBurnDuxTextView 在 HomeTopTabItemViewNew 里，对不上就直接返回）。
 * 对上了：① BottomTab 还没背景就挂一块 ItemGlass；② 透明度 >= 1.0 当作选中（玻璃再亮一档，只在选中状态变了才重画）；③ 它的父级（LinearLayout#root_view）还没背景就挂整行玻璃。
 * 没有定时器、没有扫描、没有反射；回调里没有分配（对象只在第一次挂背景时建）；设置没开（botGlass 关、或两个不透明度都是 0）时不挂这个钩子。
 * 抖音自己已经给这个控件设了背景时不覆盖；类名、层级对不上都安静不画。
 *
 * 局限（没编译、没上机）：① 钩子是挂上之后才看得到 setAlpha，冷启动时第一批 setAlpha（首页 1.0 / 其余 0.55，约启动后 1.1 秒）如果在钩子挂上之前，要等下一次切底部的页才出玻璃；
 * ② 底部按钮 216×122、整行 1080 宽，默认大小（高 = 按钮高 60%，缝 6dp，整行上下 6dp / 左右 7dp）是按顶部标签的数估的，上机后可能要调；
 * ③ 整行玻璃左右比 root_view 多出来的部分靠关掉往上 8 层的裁剪（LineBg.unclip）才画得全；
 * ④ 「+」按钮那格也算在整行里（整行 = 5 个按钮的外框并起来），它自己没有单独的玻璃；
 * ⑤ 钩的是 DuxTextView#setAlpha，所有 DuxTextView 改透明度都会进回调，回调第一行比较类就返回，次数不多，但要看耗电。
 */
final class BotGlass {
    private BotGlass() {}

    private static final String DUX = "com.bytedance.dux.text.DuxTextView";
    private static final String TXT = "com.ss.android.ugc.aweme.homepage.antiburn.ui.AntiBurnDuxTextView";
    private static final String TAB = "com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab";
    private static final int TAG = 0x7e0b0c0b;
    private static final int LEVELS = 5; // 文字 → u2- → content_layout → glv → 1p2 → BottomTab
    private static final XC_MethodHook HOOK = new Hook();

    private static volatile Class<?> txtCls, tabCls;

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || HookGuard.settled(HookGuard.TAB_BOTTOM)) return;
        DanmakuConfig cf = DanmakuHook.cfg;
        if (!cf.botGlass || (cf.tabRowBg <= 0 && cf.tabItemBg <= 0)) return; // 没开：不挂（没有结论，打开后回到前台再挂）
        Class<?> dux = HookGuard.find(HookGuard.TAB_BOTTOM, DUX, cl);
        if (dux == null) return;
        Class<?> txt = HookGuard.find(HookGuard.TAB_BOTTOM, TXT, cl);
        if (txt == null) return;
        Class<?> tab = HookGuard.find(HookGuard.TAB_BOTTOM, TAB, cl);
        if (tab == null) return;
        Method m;
        try {
            m = dux.getDeclaredMethod("setAlpha", float.class);
        } catch (NoSuchMethodException e) {
            HookGuard.miss(HookGuard.TAB_BOTTOM, "DuxTextView 里没有 setAlpha(float)（抖音改了，需要重新用 TextRec 追踪底部导航文字的透明度）");
            return;
        } catch (Throwable t) { // 方法签名里引用的类还没就绪
            HookGuard.waitFail(HookGuard.TAB_BOTTOM);
            return;
        }
        try {
            txtCls = txt;
            tabCls = tab;
            XposedBridge.hookMethod(m, HOOK);
        } catch (Throwable t) {
            HookGuard.miss(HookGuard.TAB_BOTTOM, "DuxTextView#setAlpha 挂不上：" + t);
            return;
        }
        HookGuard.ok(HookGuard.TAB_BOTTOM, "");
    }

    private static final class Hook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                DanmakuConfig cf = DanmakuHook.cfg;
                if (!cf.botGlass || (cf.tabRowBg <= 0 && cf.tabItemBg <= 0)) return;
                Class<?> k = txtCls;
                if (k == null || !k.isInstance(p.thisObject)) return;
                ViewParent q = ((View) p.thisObject).getParent();
                for (int i = 1; i < LEVELS && q != null; i++) q = q.getParent();
                Class<?> t = tabCls;
                if (t == null || q == null || !t.isInstance(q)) return;
                attach((View) q, ((Float) p.args[0]) >= 0.995f);
            } catch (Throwable ignored) {
            }
        }
    }

    /** tab = BottomTab；selected = 文字透明度已经到 1.0。 */
    private static void attach(View tab, boolean selected) {
        if (tab.getTag(TAG) == null && tab.getBackground() == null) {
            tab.setTag(TAG, Boolean.TRUE);
            tab.setBackground(new TabGlass.ItemGlass());
        }
        Drawable bg = tab.getBackground();
        if (bg instanceof TabGlass.ItemGlass && ((TabGlass.ItemGlass) bg).selected != selected) { // 选中的玻璃更亮
            ((TabGlass.ItemGlass) bg).selected = selected;
            bg.invalidateSelf();
        }
        ViewParent pr = tab.getParent();
        if (!(pr instanceof LinearLayout)) return; // root_view 是横向 LinearLayout
        ViewGroup row = (ViewGroup) pr;
        if (row.getTag(TAG) == null && row.getBackground() == null) {
            row.setTag(TAG, Boolean.TRUE);
            row.setBackground(new RowGlass(row));
            LineBg.unclip(row); // 整行玻璃比按钮外框大一圈，关掉上面几层的裁剪才画得完整
        }
    }

    /** 整行的玻璃：把当前看得见的 BottomTab 的外框并起来，画法同 TabGlass.RowGlass（层级强度 0.75）。 */
    private static final class RowGlass extends TabGlass.Glass {
        private final ViewGroup row;

        RowGlass(ViewGroup row) {
            this.row = row;
        }

        @Override
        public void draw(Canvas cv) {
            try {
                DanmakuConfig c = DanmakuHook.cfg;
                if (c.tabRowBg <= 0) return;
                Class<?> k = tabCls;
                if (k == null) return;
                float L = Float.MAX_VALUE, T = Float.MAX_VALUE, R = -Float.MAX_VALUE, B = -Float.MAX_VALUE;
                for (int i = 0, n = row.getChildCount(); i < n; i++) {
                    View v = row.getChildAt(i);
                    if (!k.isInstance(v) || v.getVisibility() != View.VISIBLE) continue;
                    L = Math.min(L, v.getLeft());
                    R = Math.max(R, v.getRight());
                    T = Math.min(T, v.getTop());
                    B = Math.max(B, v.getBottom());
                }
                if (R <= L || B <= T) return;
                float d = TabGlass.dpf(0.5f);
                float cy = (T + B) / 2f, half = (B - T) * c.tabItemH / 100f / 2f + c.tabRowPadY * d;
                float gap = c.tabGap * d / 2f, px = c.tabRowPadX * d;
                TabGlass.glass(cv, L + gap - px, cy - half, R - gap + px, cy + half, Math.min(100, c.tabRowBg) / 100f, 0.75f, c.tabRowColor, false);
            } catch (Throwable ignored) {
            }
        }
    }
}
