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
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 首页长按弹出的面板（分享给朋友 / 不感兴趣 …）改成：长按什么都不做（忽略），面板只能点一个常驻按键弹出；
 * 按键放在右侧按钮列里、作者头像前面，和头像一起走。
 *
 * 1.40.2 起点按键用「这一页自己的长按计时任务」：不记长按数据、不记视频信息、不扫描视图树，换视频也不会错。
 * （1.40.0 / 1.40.1 记下用户上一次长按的数据再回放，数据带着旧视频，换视频就弹不对。）
 *
 * 依据（TextRec 40.9.0）：
 *  - 长按栈（全同步，textrec-tasks-20261025-144949）：Handler → X.0tdv.run → Y.AhaS2700S0100000_34.LIZ(float, float) → X.10du.LIZIZ → …
 *    → LongPressPanelParams.<init> → LongPressPanelServiceImpl.LJIIZILJ → … → DialogFragment#show。
 *    X.0tdv 是 LongPressLayout 构造里创建的 Runnable，字段只有指回控件的 LongPressLayout 和插桩字段（textrec-tasks-20261025-102152）。
 *  - 面板 = com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl（DialogFragment，类名没混淆），
 *    弹出的最终入口 DialogFragment#show(FragmentManager, String)，参数 (FragmentManager, "LongPressPanelFragment")。
 *  - 作者头像 = SmartAvatarBorderView（资源名 user_avatar）；右侧按钮列 = FeedRightScaleView；头像图片设置入口 = DraweeView#setController
 *    （textrec-tasks-20261025-131001，见 README 1.39.0）。
 *  - 视图树（textrec-tasks-20261025-205759，视图树 #1～#5，首页推荐页、划到下一页前后结构一样）：
 *    LongPressLayout #qbb 不是按键的祖先，而是旁边一支的后代，但两者在同一页的同一个 MonitorScrollFrameLayout #w1m 下面：
 *      LongPressLayout → FrameLayout #ran → MeasureOnceCheckDrawChildRelativeLayout #i+m → MonitorScrollFrameLayout #w1m
 *      FeedRightScaleView → MeasureOnceCheckDrawChildRelativeLayout #i+t → PenetrateTouchRelativeLayout #i6 → MonitorScrollFrameLayout #w1m
 *    每页一个 #w1m，里面一个 LongPressLayout、一个 FeedRightScaleView（常驻当前页和预加载页两页，坐标差 2840）。
 *    MonitorScrollFrameLayout 的类名没混淆（com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.interestselection.MonitorScrollFrameLayout）。
 *
 * 做法（3 个钩子）：
 *  1. lp.show = DialogFragment#show(FragmentManager, String)，before：this 的类名是 LongPressPanelFragmentImpl 才处理。
 *     点按键触发的那一次（armed）放行；用户自己的长按 setResult(null) 忽略。按键这条路没通（lp.col / lp.lay 没挂上）时不拦，长按照常弹面板。
 *  2. lp.lay = LongPressLayout 的所有构造方法，after：只给 this 的类正好是 LongPressLayout 的控件挂一个单例的 OnAttachStateChangeListener。
 *     控件挂到窗口时，往上找最近的 MonitorScrollFrameLayout（类名比较，最多 10 层），登记「这一页的 #w1m → 这个 LongPressLayout（弱引用）」。
 *     第一次登记时认出「长按计时任务」是它的哪个字段（见 resolveTimer，整个进程只认一次），认不出唯一的一个就把 lp.lay 记成未适配。
 *  3. lp.col = DraweeView#setController，after：把按键放进头像所在的那一项前面（一页一次）。
 *  点按键：从按键往上找最近的 MonitorScrollFrameLayout，取一次 Map 得到这一页的 LongPressLayout，读它的计时任务字段，armed = true，run()，
 *  钩子放行这一次 show。没有触摸、没有计时、没有遍历视图树、没有记视频数据。这一页没登记过 / 字段没认出来：什么都不做，没有任何退路。
 *
 * 局限（没编译、没上机）：
 *  - 直接 run() 计时任务没有真实触摸：如果计时任务（或它往下的 Y.AhaS2700S0100000_34.LIZ(float, float)）还要读控件上按下时记的坐标 / 状态，
 *    没有触摸时可能什么都不做，或者坐标是 0。（1.38.x / 1.39.x 就是这样触发的。）TextRec 没展开过这两个对象的字段。
 *  - 登记发生在 LongPressLayout 挂到窗口的那一刻：钩子装上之前就已经挂好的页（冷启动时当前页常见）不会登记，划一下换页或重开抖音才有。
 *  - LongPressLayout 有几个构造方法互相调用，监听器可能被加了好几次：每次挂窗口多登记几次同一个值，没有副作用。
 *  - 只在 40.9.0 一台手机上验证过结构；类名没混淆，但层级变了（最近的 MonitorScrollFrameLayout 不再同时是两者的祖先）会安静失效。
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

    /** lp.lay：长按层（类名没混淆，TextRec 视图树 textrec-tasks-20261025-205759）和「一页」的容器（同一份视图树，两者的最近公共祖先）。 */
    private static final String LAYER = "com.ss.android.ugc.aweme.feed.ui.LongPressLayout";
    private static final String PAGE = "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.interestselection.MonitorScrollFrameLayout";
    /** 往上找页面容器最多看几层（实测按键 4 层、长按层 3 层）。 */
    private static final int PAGE_UP = 10;

    private static final XC_MethodHook HOOK = new Hook();
    private static final XC_MethodHook COL_HOOK = new ColHook();
    private static final XC_MethodHook LAY_HOOK = new LayHook();

    /** 点按键触发的那一次长按放行（主线程读写）。 */
    private static boolean armed;

    /** lp.col 要认的两个类（先赋值再挂钩）；头像的资源 id（-1 = 还没解析，0 = 解析不到）。 */
    private static volatile Class<?> avatarCls, colCls;
    private static int avatarId = -1;
    /** 已经放进列里的按键（主线程读写）；回到前台时按开关显示 / 隐藏。 */
    private static final ArrayList<WeakReference<View>> BTNS = new ArrayList<>();

    /** lp.lay 要认的两个类（先赋值再挂钩）。 */
    private static volatile Class<?> layCls, pageCls;
    /** 页面容器 → 这一页的 LongPressLayout（弱引用；值不能强引用控件，否则弱键永远不会被回收）。主线程读写。 */
    private static final WeakHashMap<View, WeakReference<View>> PAGES = new WeakHashMap<>();
    /** LongPressLayout 里「长按计时任务」那个字段；timerState：0 = 还没认，1 = 认出了，2 = 认不出（已记成未适配）。主线程读写。 */
    private static volatile Field timerField;
    private static int timerState;

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
            int vis = DanmakuHook.cfg.lpBtn && HookGuard.isOk(HookGuard.LP_LAY) ? View.VISIBLE : View.GONE;
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
                // 点按键这条路没通（按键放不进去 / 长按层没登记的钩子没挂上）：长按照常弹出，不改抖音原来的行为
                if (!HookGuard.isOk(HookGuard.LP_COL) || !HookGuard.isOk(HookGuard.LP_LAY)) return;
                p.setResult(null);                                    // 用户自己长按：忽略
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 登记每一页的长按层（lp.lay）

    private static void tryLay(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.LP_LAY)) return;
        Class<?> c = HookGuard.find(HookGuard.LP_LAY, LAYER, cl);
        if (c == null) return;
        Class<?> pg = HookGuard.find(HookGuard.LP_LAY, PAGE, cl);
        if (pg == null) return;
        layCls = c;                                                       // 先赋值再挂钩
        pageCls = pg;
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
            layCls = null;
            pageCls = null;
            HookGuard.waitFail(HookGuard.LP_LAY);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.LP_LAY, "");
        else HookGuard.miss(HookGuard.LP_LAY, "LongPressLayout 里没有构造方法可挂（抖音改了，需要重新用 TextRec 导出首页视图树）");
    }

    /** 每个视频页的 LongPressLayout 构造完：挂上单例的「挂载监听」（不改控件、不改结果）。 */
    private static final class LayHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object t = p.thisObject;
                if (t == null || t.getClass() != layCls) return;       // 子类 / 别的类：一次类比较就放行
                ((View) t).addOnAttachStateChangeListener(ATTACH);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 控件挂到窗口（主线程）：登记「这一页的页面容器 → 这个 LongPressLayout」。 */
    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            try {
                if (!DanmakuHook.cfg.lpBtn) return;
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                if (!resolveTimer(v)) return;
                View page = pageOf(v);
                if (page == null) return;
                PAGES.put(page, new WeakReference<View>(v));
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
        }
    };

    /** 往上找最近的页面容器（类名完全一样），最多 PAGE_UP 层；找不到返回 null。按键和长按层都从自己往上找，找到同一个。 */
    private static View pageOf(View v) {
        Class<?> pc = pageCls;
        if (pc == null) return null;
        ViewParent q = v.getParent();
        for (int i = 0; i < PAGE_UP && q instanceof View; i++) {
            if (q.getClass() == pc) return (View) q;
            q = q.getParent();
        }
        return null;
    }

    /**
     * 第一次拿到 LongPressLayout 实例时，认出「长按计时任务」是它的哪个字段（整个进程只做一次，之后直接读缓存的 Field）。
     * 依据 TextRec：X.0tdv 在 LongPressLayout 构造里创建，字段只有指回控件的 LongPressLayout 和插桩字段。
     * 认法：LongPressLayout（及抖音自己的父类，不含 android.* 的）的实例字段里，值是 Runnable、并且它的类里有一个字段类型是 LongPressLayout、值正好是这个控件。
     * 只有一个才认；0 个或多个 = 抖音改了，记成未适配，什么都不登记。
     */
    private static boolean resolveTimer(View lay) {
        if (timerState == 1) return true;
        if (timerState == 2) return false;
        Field found = null;
        int n = 0;
        try {
            for (Class<?> c = lay.getClass(); c != null && !c.getName().startsWith("android.") && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(lay);
                        if (!(v instanceof Runnable) || !pointsBack(v, lay)) continue;
                        found = f;
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
            n = -1;
        }
        if (n == 1) {
            timerField = found;
            timerState = 1;
            return true;
        }
        timerState = 2;
        HookGuard.miss(HookGuard.LP_LAY, "LongPressLayout 里认不出唯一的长按计时任务字段（找到 " + n + " 个；抖音改了，需要重新用 TextRec 追踪 LongPressLayout 构造里创建的 Runnable）");
        return false;
    }

    /** r 的类里有一个字段，类型是 LongPressLayout，值就是 lay。 */
    private static boolean pointsBack(Object r, View lay) {
        for (Class<?> c = r.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field g : c.getDeclaredFields()) {
                if (Modifier.isStatic(g.getModifiers()) || g.getType() != layCls) continue;
                try {
                    g.setAccessible(true);
                    if (g.get(r) == lay) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
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
        if (!HookGuard.isOk(HookGuard.LP_SHOW) || !HookGuard.isOk(HookGuard.LP_LAY)) return; // 拦截 / 长按层登记没挂上，放按键没有意义
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
     * 点按键：从按键往上找最近的页面容器，取一次 Map 得到这一页自己的 LongPressLayout，读它的长按计时任务字段，放行下一次 show 后 run()。
     * 面板内容由抖音自己按这一页的视频生成。没有触摸、没有计时、没有遍历视图树。
     * 这一页没登记过 / 控件已不在窗口上 / 字段没认出来 / run() 出错，都什么都不做。
     */
    private static void fire(View b) {
        if (!(b instanceof PanelBtn) || !b.isAttachedToWindow()) return;
        View page = pageOf(b);
        if (page == null) return;
        WeakReference<View> w = PAGES.get(page);
        View lay = w == null ? null : w.get();
        Field f = timerField;
        if (lay == null || f == null || !lay.isAttachedToWindow()) return;
        Object r;
        try {
            r = f.get(lay);
        } catch (Throwable t) {
            return;
        }
        if (!(r instanceof Runnable)) return;
        armed = true;
        try {
            ((Runnable) r).run();
        } catch (Throwable ignored) {
        } finally {
            armed = false; // show 在计时任务里同步调用，走完就不留放行标记
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
