package com.danmaku.randcolor;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 右侧按钮列（点赞 / 评论 / 收藏 / 分享）的液态玻璃图标（1.45.0）：两种，各有开关。
 *  ① 底板（iconPlate + iconBg）：每个图标后面垫一块圆形玻璃，就是这个控件的背景（View.setBackground），画法同顶部标签的玻璃（TabGlass.drawGlass，缓存成硬件位图，一次 drawBitmap）。
 *  ② 图标玻璃（iconFill）：给控件加一个前景（View.setForeground），用 SRC_ATOP 在图标已经画出的像素上叠一层「上亮下暗」的渐变，图标就有玻璃的明暗；
 *     SRC_ATOP 只作用在这个控件自己的图层里，所以给它开了硬件图层（setLayerType）。图层只在图标内容变了才重画，平时是一张纹理。
 *
 * 依据（TextRec 40.9.0 / 41.0.0，textrec-tasks-20261030-140928 和 141422：点击「点哪查哪」+ 图标记录）：这四个图标都是图片控件（有 setImageDrawable / setImageResource 记录），
 * 类名都没混淆：com.ss.android.ugc.aweme.feed.widget.DiggAnimationView（点赞，103×103）、feed.widget.right.FeedCommentImageView（评论，103×103）、
 * FeedCollectAnimationView（收藏，174×174，图标比控件小）、FeedShareImageView（分享，103×103）。
 *
 * 做法：钩这四个类的所有构造方法（after，类正好是它们才处理；和 LiveCardHook 同一个写法），给实例挂一个单例的挂载监听；挂到窗口时（主线程）按开关套上底板 / 前景。
 * 没有钩 setImageDrawable / setImageResource / onDraw（那些是 ImageView 的全局方法，每个图片控件都会走），也没有每次换图时的任何处理。
 * 回到前台（配置刚重读过）：IconGlass.onResume 把见过的控件按新配置重套（开关关了就撤掉，只撤我们自己加的）。
 *
 * 开关 iconPlate / iconFill 都没开时不挂这个钩子（没有结论，打开后回到前台再挂）。没有定时器 / 轮询 / 扫描。
 * 类没找到 → waitFail；类在但没有构造方法可挂 → miss。控件已经有自己的背景 / 前景时不覆盖（安静失效，没有兜底）。
 * 没验证的（没上机）：① 图标自己如果在控件范围外画东西（点赞爆发之类），硬件图层会把它裁掉；② 底板和图标玻璃的位置、大小、明暗是估的。
 */
final class IconGlass {
    private IconGlass() {}

    private static final String[] CLS = {
            "com.ss.android.ugc.aweme.feed.widget.DiggAnimationView",
            "com.ss.android.ugc.aweme.feed.widget.right.FeedCommentImageView",
            "com.ss.android.ugc.aweme.feed.widget.right.FeedCollectAnimationView",
            "com.ss.android.ugc.aweme.feed.widget.right.FeedShareImageView"};
    /** 收藏控件 174×174，图标只占中间一块（比另外三个的 103×103 大 1.7 倍），底板按 103/174 缩小，四个底板一样大。 */
    private static final int COLLECT = 2;
    private static final float COLLECT_RATIO = 103f / 174f;
    private static final int MAX_VIEWS = 24;
    private static final int ATTACH_TAG = 0x7e0b0c0c, LAYER_TAG = 0x7e0b0c0d;

    private static final XC_MethodHook HOOK = new CtorHook();
    private static volatile Class<?>[] classes;
    /** 见过的图标控件（弱引用），回到前台按新配置重套；主线程用。 */
    private static final ArrayList<WeakReference<View>> VIEWS = new ArrayList<>();

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || HookGuard.settled(HookGuard.ICON_GLASS)) return;
        DanmakuConfig cf = DanmakuHook.cfg;
        if (!cf.iconPlate && !cf.iconFill) return; // 都没开：不挂（没有结论，打开后回到前台再挂）
        Class<?>[] cs = new Class<?>[CLS.length];
        for (int i = 0; i < CLS.length; i++) {
            cs[i] = HookGuard.find(HookGuard.ICON_GLASS, CLS[i], cl);
            if (cs[i] == null) return; // 有一个类还没找到：下次回到前台再试
        }
        classes = cs; // 先赋值再挂钩
        int n = 0;
        try {
            for (Class<?> c : cs) {
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    try {
                        XposedBridge.hookMethod(k, HOOK);
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) { // 构造方法签名里引用的类还没就绪
            classes = null;
            HookGuard.waitFail(HookGuard.ICON_GLASS);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.ICON_GLASS, "");
        else HookGuard.miss(HookGuard.ICON_GLASS, "右侧图标的类里没有构造方法可挂（点赞 / 评论 / 收藏 / 分享图标的类变了，需要重新用 TextRec 导出右侧按钮列的视图树）");
    }

    private static final class CtorHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object t = p.thisObject;
                Class<?>[] cs = classes;
                if (cs == null || !(t instanceof View)) return;
                Class<?> k = t.getClass();
                boolean hit = false;
                for (Class<?> c : cs) {
                    if (c == k) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) return; // 子类 / 别的类：一次类比较就放行
                View v = (View) t;
                if (v.getTag(ATTACH_TAG) != null) return; // 构造方法链里会进来好几次，只挂一次
                v.setTag(ATTACH_TAG, Boolean.TRUE);
                v.addOnAttachStateChangeListener(ATTACH);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            try {
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                register(v);
                apply(v);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
        }
    };

    private static void register(View v) {
        for (int i = VIEWS.size() - 1; i >= 0; i--) {
            View x = VIEWS.get(i).get();
            if (x == null) VIEWS.remove(i);
            else if (x == v) return;
        }
        if (VIEWS.size() >= MAX_VIEWS) VIEWS.remove(0);
        VIEWS.add(new WeakReference<>(v));
    }

    /** 按当前配置套上 / 撤掉底板和图标玻璃（只动我们自己加的；控件原来就有背景 / 前景时不覆盖）。主线程。 */
    private static void apply(View v) {
        DanmakuConfig c = DanmakuHook.cfg;
        Drawable bg = v.getBackground();
        if (c.iconPlate && c.iconBg > 0) {
            if (bg == null) v.setBackground(new Plate(v.getClass().getName().equals(CLS[COLLECT]) ? COLLECT_RATIO : 1f));
        } else if (bg instanceof Plate) {
            v.setBackground(null);
        }
        Drawable fg = v.getForeground();
        if (c.iconFill) {
            if (fg == null) {
                if (v.getLayerType() == View.LAYER_TYPE_NONE) {
                    v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    v.setTag(LAYER_TAG, Boolean.TRUE);
                }
                v.setForeground(new Fill());
            }
        } else if (fg instanceof Fill) {
            v.setForeground(null);
            if (v.getTag(LAYER_TAG) != null) {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                v.setTag(LAYER_TAG, null);
            }
        }
    }

    /** 回到前台（配置刚重读过）：见过的图标控件按新配置重套并重画一次（不透明度、开关变了马上生效）。只对登记过的几个控件做事，没有扫描。 */
    static void onResume() {
        for (int i = VIEWS.size() - 1; i >= 0; i--) {
            View v = VIEWS.get(i).get();
            if (v == null) {
                VIEWS.remove(i);
                continue;
            }
            if (!v.isAttachedToWindow()) continue;
            apply(v);
            v.invalidate();
        }
    }

    /** 底板：控件范围内居中的一块圆形玻璃（直径 = 控件短边 × ratio），玻璃本身是缓存的硬件位图。 */
    private static final class Plate extends TabGlass.Glass {
        private final float ratio;

        Plate(float ratio) {
            this.ratio = ratio;
        }

        @Override
        public void draw(Canvas cv) {
            try {
                DanmakuConfig c = DanmakuHook.cfg;
                int pct = Math.min(100, c.iconBg);
                if (!c.iconPlate || pct <= 0) return;
                Rect bd = getBounds();
                float r = Math.min(bd.width(), bd.height()) * ratio / 2f;
                if (r < 1f) return;
                float cx = bd.exactCenterX(), cy = bd.exactCenterY();
                TabGlass.drawGlass(cv, cx - r, cy - r, cx + r, cy + r, pct / 100f, 1f, 0, false, false);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 图标玻璃：前景，SRC_ATOP 只在图标已经画出的像素上叠渐变（上面一层白色高光，下面一层偏冷的暗影，中间不动）。渐变和画笔每个实例各一份，只在大小变了才重建。 */
    private static final class Fill extends TabGlass.Glass {
        private final Paint paint = new Paint();
        private LinearGradient grad;

        Fill() {
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        }

        @Override
        protected void onBoundsChange(Rect b) {
            grad = new LinearGradient(0f, b.top, 0f, b.bottom,
                    new int[]{0x99FFFFFF, 0x1AFFFFFF, 0x00FFFFFF, 0x38203860}, new float[]{0f, 0.34f, 0.62f, 1f}, Shader.TileMode.CLAMP);
            paint.setShader(grad);
        }

        @Override
        public void draw(Canvas cv) {
            try {
                if (!DanmakuHook.cfg.iconFill || grad == null) return;
                cv.drawRect(getBounds(), paint);
            } catch (Throwable ignored) {
            }
        }
    }
}
