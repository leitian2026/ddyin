package com.danmaku.randcolor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 首页长按弹出的面板（分享给朋友 / 不感兴趣 …）改成：长按时先弹出一个小按键，点按键才弹出原来的面板。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261025-094241，首页长按两次、点空白处关一次、按返回关一次）：
 *  - 长按弹出的面板是 com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl（类名没混淆），是个 DialogFragment；
 *    弹出的入口 = androidx.fragment.app.DialogFragment#show(FragmentManager, String)，this = LongPressPanelFragmentImpl，
 *    参数 (FragmentManager{… in HostCallbacks{…}}, "LongPressPanelFragment")，两次长按各一次；返回 void（TextRec 标明可以 setResult(null) 整个跳过）。
 *  - show 之后才是 LongPressPanelFragmentImpl#onStart → Dialog#show（PadBaseAdaptDialogAdapter）→ WindowManagerGlobal#addView，面板才真正出现；
 *    关闭是 BottomSheetDialog#dismiss（点空白处）/ 返回键。
 *  - 长按没有走 View#performLongClick（那个钩点整个任务里 0 次），所以不在长按手势上做文章，只管面板自己的 show。
 *  - 追踪里只见到 (FragmentManager, String) 这一个重载，只挂这一个（没有证据的 (FragmentTransaction, String) 不挂）。
 *
 * 做法：
 *  1. lp.show = DialogFragment#show 的 (FragmentManager, String) 重载，before：this 的类名是 LongPressPanelFragmentImpl 才处理，别的 DialogFragment 一次字符串比较就放行。
 *     处理 = 记下 this / 这个方法 / 参数，setResult(null) 跳过原来的 show，在当前 Activity 的窗口最上层盖一层透明触摸层 + 一个按键。
 *  2. 点按键：拿掉触摸层，把记下的 show 原样再调一次（调用时 passing = true，钩子放行）→ 弹出原来的面板。
 *  3. 点按键以外的任何地方（按下就算）/ 按返回键 / 回到前台：拿掉触摸层，什么都不弹（和原来的面板「点空白处关掉」一样，这一下点击被吃掉）。
 *  4. 按键位置 lpX / lpY（dp，左 / 上，-1 = 默认在屏幕下方居中），在抖音里用位置浮窗（VidPosPanel）选「按键」拖，和视频时长一样。
 * 没有定时器、没有轮询、没有扫描；钩子回调只在长按弹面板时才走到（一次长按一次），不是每次触摸；
 * 回调里要建一个按键 / 一层触摸层，这是用户长按一次一次的事，不是热路径。没挂上 / 开关关着 = 什么都不改，面板照常弹出。
 *
 * 局限（没编译、没上机）：
 *  - 只在 40.9.0 一台手机上验证过钩点；类名 / 重载变了会安静失效（面板照常弹出），设置页「钩子状态」里 lp.show 会显示未适配。
 *  - 没有验证过个人主页、搜索等别的地方的长按是不是也走同一个 LongPressPanelFragmentImpl；是的话它们也会变成按键。
 *  - 跳过 show 后抖音自己的「面板正在显示」之类状态没有追踪过：如果抖音在 show 之前就记了「已弹出」，点空白处放弃之后再长按可能弹不出按键，
 *    遇到就要再抓一份「长按 → 点空白处放弃 → 再长按」的记录。
 *  - 返回键靠触摸层自己拿到焦点来收；拿不到焦点时返回键会走抖音原来的返回逻辑，按键留在屏幕上，点空白处或回到前台才消失。
 */
final class LongPressHook {
    private LongPressHook() {}

    private static final String DF = "androidx.fragment.app.DialogFragment";
    private static final String FM = "androidx.fragment.app.FragmentManager";
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl";

    private static final XC_MethodHook HOOK = new Hook();

    /** 现在在最前面的 Activity（回到前台时记；长按时按键盖在它的窗口上）。 */
    private static WeakReference<Activity> cur;
    /** 我们自己再调 show 时放行（主线程读写）。 */
    private static boolean passing;
    private static WeakReference<FrameLayout> layerRef;
    private static Object pendingFrag;
    private static Method pendingShow;
    private static Object[] pendingArgs;

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

    /** 回到前台：记下最前面的 Activity；上一次还没处理的按键拿掉。 */
    static void onResume(Activity a) {
        try {
            WeakReference<Activity> c = cur;
            if (c == null || c.get() != a) cur = new WeakReference<Activity>(a);
            if (layerRef != null) dismiss();
        } catch (Throwable ignored) {
        }
    }

    private static final class Hook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (passing) return;                                  // 我们自己再调的：放行
                if (!DanmakuHook.cfg.lpBtn) return;
                Object t = p.thisObject;
                if (t == null || !PANEL.equals(t.getClass().getName())) return; // 别的 DialogFragment：放行
                if (!(p.method instanceof Method) || p.args == null || p.args.length != 2) return;
                WeakReference<Activity> c = cur;
                Activity a = c == null ? null : c.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;   // 不知道盖在哪个窗口：放行，面板照常弹出
                if (!showButton(a, t, (Method) p.method, p.args)) return;
                p.setResult(null);                                    // 原来的 show 不执行；点按键再调
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 按键

    private static boolean showButton(Activity a, Object frag, Method m, Object[] args) {
        View top = a.getWindow().getDecorView();
        if (!(top instanceof FrameLayout)) return false;
        dismiss();
        Context ctx = top.getContext();
        FrameLayout layer = new FrameLayout(ctx);
        layer.setClickable(true);
        layer.setFocusableInTouchMode(true);
        layer.setOnTouchListener(OUTSIDE);
        layer.setOnKeyListener(BACK);
        TextView b = makeButton(ctx);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    open();
                } catch (Throwable ignored) {
                }
            }
        });
        DanmakuConfig c = DanmakuHook.cfg;
        layer.addView(b, place(ctx, c.lpX, c.lpY));
        ((FrameLayout) top).addView(layer, new FrameLayout.LayoutParams(-1, -1));
        layerRef = new WeakReference<FrameLayout>(layer);
        pendingFrag = frag;
        pendingShow = m;
        pendingArgs = args;
        layer.requestFocus();
        return true;
    }

    /** 点按键：拿掉按键层，把记下的 show 原样再调一次。 */
    private static void open() {
        Object f = pendingFrag;
        Method m = pendingShow;
        Object[] a = pendingArgs;
        dismiss();
        if (f == null || m == null || a == null) return;
        passing = true;
        try {
            m.invoke(f, a);
        } catch (Throwable ignored) {
        } finally {
            passing = false;
        }
    }

    private static void dismiss() {
        FrameLayout l = layerRef == null ? null : layerRef.get();
        layerRef = null;
        pendingFrag = null;
        pendingShow = null;
        pendingArgs = null;
        if (l != null && l.getParent() instanceof ViewGroup) ((ViewGroup) l.getParent()).removeView(l);
    }

    /** 按键以外的地方按下：什么都不弹，按键拿掉（这一下点击被吃掉）。 */
    private static final View.OnTouchListener OUTSIDE = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) dismiss();
            } catch (Throwable ignored) {
            }
            return true;
        }
    };

    private static final View.OnKeyListener BACK = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent e) {
            try {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    };

    // ------------------------------------------------------------ 样式 / 位置（位置浮窗的预览也用）

    /** 按键的样子（还没挂点击；位置浮窗里的预览直接用它）。 */
    static TextView makeButton(Context ctx) {
        TextView t = new TextView(ctx);
        t.setText("打开面板");
        t.setTextSize(15);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 18), dp(ctx, 11), dp(ctx, 18), dp(ctx, 11));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF0171A22);
        g.setCornerRadius(dp(ctx, 22));
        g.setStroke(dp(ctx, 1), 0x66FFFFFF);
        t.setBackground(g);
        return t;
    }

    /** 按键在窗口里的位置：x / y 是左 / 上（dp，相对整个窗口），任何一个 < 0 = 默认（屏幕下方居中）；不会放到屏幕外面。 */
    static FrameLayout.LayoutParams place(Context ctx, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        if (x < 0 || y < 0) {
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(ctx, 200);
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
