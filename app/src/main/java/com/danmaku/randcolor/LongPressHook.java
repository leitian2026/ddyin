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
import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 首页长按弹出的面板（分享给朋友 / 不感兴趣 …）改成：长按什么都不做（忽略），面板只能点一个常驻按键弹出；
 * 按键放在右侧按钮列里、作者头像前面，和头像一起走。
 *
 * 1.40.1 起点按键不再找计时任务、不再扫描、也不记视频信息：只记「长按数据」（抖音处理长按的入口收到的那个参数 X.10dv），
 * 点按键时把它交给同一个入口，抖音自己往下为当前视频造面板数据、弹面板，和真长按一样。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261025-144949，首页长按三次：任务 2、任务 4 换了视频、任务 5 回到上一个视频）：
 *  - 长按栈（全同步）：Handler → X.0tdv.run → Y.AhaS2700S0100000_34.LIZ(float, float) → X.10du.LIZIZ(X.10dv):void（栈第 2 层）
 *    → X.10du.LIZJ(FragmentActivity, X.10dv):void → LongPressPanelParams.<init>（每次 2 次）→ LongPressPanelServiceImpl.LJIIZILJ
 *    → … → DialogFragment#show。LongPressPanelParams 是 X.10du.LIZJ 往下为当前视频现造的，所以不记它。
 *  - 面板 = com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl（DialogFragment，类名没混淆），
 *    弹出的最终入口 DialogFragment#show(FragmentManager, String)，参数 (FragmentManager, "LongPressPanelFragment")，返回 void。
 *  - 作者头像 = com.ss.android.ugc.aweme.base.ui.SmartAvatarBorderView，资源名 user_avatar；右侧按钮列 = com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView；
 *    头像图片设置入口 = com.facebook.drawee.view.DraweeView#setController（依据 TextRec textrec-tasks-20261025-131001，见 README 1.39.0）。
 *
 * 做法（3 个钩子）：
 *  1. lp.show = DialogFragment#show(FragmentManager, String)，before：this 的类名是 LongPressPanelFragmentImpl 才处理。
 *     点按键触发的那一次（armed）放行；用户自己的长按 setResult(null) 忽略。按键这条路没通（lp.col / lp.go 没挂上）时不拦，长按照常弹面板。
 *  2. lp.go = X.10du#LIZIZ(X.10dv)（混淆名，按模板 A 写死名字和签名），before：记下长按数据 X.10dv（方法非静态时再记 this）。
 *     被 lp.show 忽略的那一次长按也会记下。
 *  3. lp.col = DraweeView#setController，after：把按键放进头像所在的那一项前面（一页一次）。
 *  点按键：armed = true，用挂钩时拿到的 Method 直接 invoke(记下的 this, X.10dv)，钩子放行这一次 show。没有触摸、没有计时、没有搜索。
 *  还没记到数据（用户一次长按都没做过）：什么都不做，没有任何退路。
 *
 * 局限（没编译、没上机）：
 *  - TextRec 没有展开过 X.10dv（只追了 LongPressPanelParams / DialogFragment#show / LongPressPanelServiceImpl），不知道它里面有没有指向「这一个视频 / 这一页」的东西。
 *    如果有，换了视频再点按键弹出来的会不对；上机第一件事就是换个视频点按键看面板是不是当前视频。
 *  - 不知道 X.10du#LIZIZ 是静态还是实例方法；代码两种都处理（实例方法记 this，静态方法 this 传 null）。
 *  - 记下的对象会一直被持有到下一次长按覆盖。
 *  - 只在 40.9.0 一台手机上验证过钩点；名字 / 签名变了会安静失效（lp.go 显示未适配，长按照常弹面板）。
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

    /** lp.go：长按的入口（全是混淆名，写死 TextRec 40.9.0 追到的名字和签名：X.10du#LIZIZ(X.10dv):void，栈第 2 层）。 */
    private static final String ENTRY = "X.10du";
    private static final String ENTRY_METHOD = "LIZIZ";
    private static final String ENTRY_ARG = "X.10dv";

    private static final XC_MethodHook HOOK = new Hook();
    private static final XC_MethodHook COL_HOOK = new ColHook();
    private static final XC_MethodHook GO_HOOK = new GoHook();

    /** 点按键触发的那一次长按放行（主线程读写）。 */
    private static boolean armed;

    /** lp.col 要认的两个类（先赋值再挂钩）；头像的资源 id（-1 = 还没解析，0 = 解析不到）。 */
    private static volatile Class<?> avatarCls, colCls;
    private static int avatarId = -1;
    /** 已经放进列里的按键（主线程读写）；回到前台时按开关显示 / 隐藏。 */
    private static final ArrayList<WeakReference<View>> BTNS = new ArrayList<>();

    /** lp.go 挂上的那个方法（挂钩之前先赋值）和记下的一次长按的数据（X.10dv；非静态方法才记 this）。主线程读写，下一次长按覆盖。 */
    private static volatile Method goMethod;
    private static Object goThis, goData;

    /** 启动时和每次回到前台都会被调用；功能没开就不挂（没有结论，开了之后回到前台再挂）。 */
    static synchronized void tryHook(ClassLoader cl) {
        if (cl == null || !DanmakuHook.cfg.lpBtn) return;
        tryShow(cl);
        tryCol(cl);
        tryGo(cl);
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
                // 点按键这条路没通（按键放不进去 / 弹面板入口没挂上）：长按照常弹出，不改抖音原来的行为
                if (!HookGuard.isOk(HookGuard.LP_COL) || !HookGuard.isOk(HookGuard.LP_GO)) return;
                p.setResult(null);                                    // 用户自己长按：忽略
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 记下一次长按的数据（lp.go）

    private static void tryGo(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.LP_GO)) return;
        Class<?> c = HookGuard.find(HookGuard.LP_GO, ENTRY, cl);
        if (c == null) return;
        int n = 0;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!ENTRY_METHOD.equals(m.getName()) || m.getReturnType() != void.class) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length != 1 || !ENTRY_ARG.equals(t[0].getName())) continue;
                m.setAccessible(true);
                goMethod = m;                                         // 先赋值再挂钩
                XposedBridge.hookMethod(m, GO_HOOK);
                n++;
            }
        } catch (Throwable t) { // 方法签名里引用的类还没就绪
            goMethod = null;
            HookGuard.waitFail(HookGuard.LP_GO);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.LP_GO, "");
        else HookGuard.miss(HookGuard.LP_GO, "X.10du 里没有 LIZIZ(X.10dv)（抖音改了，需要重新用 TextRec 追踪首页长按的入口）");
    }

    /** 用户长按走到入口时记下长按数据（不改结果、不改参数）。 */
    private static final class GoHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.lpBtn) return;
                Object[] a = p.args;
                if (a == null || a.length != 1 || a[0] == null) return;
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                goThis = p.thisObject;                                // 静态方法时是 null
                goData = a[0];
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
        if (!HookGuard.isOk(HookGuard.LP_SHOW) || !HookGuard.isOk(HookGuard.LP_GO)) return; // 拦截 / 弹面板入口没挂上，放按键没有意义
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
     * 点按键：把记下的长按数据直接交给抖音处理长按的入口（X.10du#LIZIZ），放行下一次 show；面板内容由抖音自己按当前视频生成。
     * 没有触摸、没有计时、没有任何搜索。还没记到数据 / 入口调用出错，都什么都不做。
     */
    private static void fire(View b) {
        if (!(b instanceof PanelBtn) || !b.isAttachedToWindow()) return;
        Method m = goMethod;
        Object t = goThis, d = goData;
        if (m == null || d == null) return;                                   // 用户还没长按过
        if (t == null && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) return;
        armed = true;
        try {
            m.invoke(t, d);
        } catch (Throwable ignored) {
        } finally {
            armed = false; // show 在入口里同步调用，走完就不留放行标记
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
