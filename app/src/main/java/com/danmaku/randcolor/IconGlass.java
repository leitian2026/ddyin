package com.danmaku.randcolor;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 右侧按钮列（点赞 / 评论 / 收藏 / 分享）的液态玻璃图标（1.45.0）：两种，各有开关。
 *  ① 底板（iconPlate + iconBg）：每个图标后面垫一块圆形玻璃，就是这个控件的背景（View.setBackground），画法同顶部标签的玻璃（TabGlass.drawGlass，缓存成硬件位图，一次 drawBitmap）。
 *  ② 图标玻璃（iconFill，1.45.2 起自己画）：原图标 setImageAlpha(0) 藏起来，前景里画心 / 气泡 / 星 / 箭头的玻璃图标（缓存的硬件位图，一次 drawBitmap）；已点赞变红、已收藏变黄（读外层按钮的无障碍描述）。
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
    private static final float COLLECT_RATIO = 103f / 174f; // 四个底板一样大
    private static final int MAX_VIEWS = 24;
    private static final int ATTACH_TAG = 0x7e0b0c0c, HID_TAG = 0x7e0b0c0d, HOLDER_TAG = 0x7e0b0c0e;

    private static final XC_MethodHook HOOK = new CtorHook();
    private static volatile Class<?>[] classes;
    private static volatile Class<?> collectCls;
    /** 见过的图标控件（弱引用），回到前台按新配置重套；主线程用。 */
    private static final ArrayList<WeakReference<View>> VIEWS = new ArrayList<>();

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        DanmakuConfig cf = DanmakuHook.cfg;
        if (!cf.iconPlate && !cf.iconFill) return; // 都没开：不挂（没有结论，打开后回到前台再挂）
        hookViews(cl);
    }

    private static void hookViews(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.ICON_GLASS)) return;
        Class<?>[] cs = new Class<?>[CLS.length];
        for (int i = 0; i < CLS.length; i++) {
            cs[i] = HookGuard.find(HookGuard.ICON_GLASS, CLS[i], cl);
            if (cs[i] == null) return; // 有一个类还没找到：下次回到前台再试
        }
        classes = cs; // 先赋值再挂钩
        collectCls = cs[COLLECT];
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
            // 抖音点赞时会自己 setImageAlpha(254)，把我们藏图标的 0 盖掉：只对已经被我们藏起来的控件，把传进来的值改回 0
            XposedBridge.hookMethod(ImageView.class.getDeclaredMethod("setImageAlpha", int.class), ALPHA);
        } catch (Throwable t) { // 构造方法签名里引用的类还没就绪
            classes = null;
            collectCls = null;
            HookGuard.waitFail(HookGuard.ICON_GLASS);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.ICON_GLASS, "");
        else HookGuard.miss(HookGuard.ICON_GLASS, "右侧图标的类里没有构造方法可挂（点赞 / 评论 / 收藏 / 分享图标的类变了，需要重新用 TextRec 导出右侧按钮列的视图树）");
    }

    private static final XC_MethodHook ALPHA = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (p.thisObject instanceof View && ((View) p.thisObject).getTag(HID_TAG) != null) p.args[0] = 0;
            } catch (Throwable ignored) {
            }
        }

        // 抖音点赞动画收尾时的 setImageAlpha 就是「爆发动画结束」的信号：此时让这个控件重画一次，把玻璃图标补出来（不用定时器）
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (p.thisObject instanceof View && ((View) p.thisObject).getTag(HID_TAG) != null) ((View) p.thisObject).invalidate();
            } catch (Throwable ignored) {
            }
        }
    };

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
        if (v.getClass() == collectCls) return; // 收藏图标（Lottie）这一版不处理，下一版再做
        Drawable bg = v.getBackground();
        if (c.iconPlate && c.iconBg > 0) {
            if (bg == null) {
                String n = v.getClass().getName();
                v.setBackground(new Plate(n.equals(CLS[COLLECT]) ? COLLECT_RATIO : 1f, 0));
            }
        } else if (bg instanceof Plate) {
            v.setBackground(null);
        }
        Drawable fg = v.getForeground();
        if (c.iconFill) {
            if (fg == null) {
                v.setForeground(new Glyph(kindOf(v)));
                ViewParent p = v.getParent(); // 玻璃图标比图标框大时不被父级裁掉（最多 3 层）
                for (int i = 0; i < 3 && p instanceof ViewGroup; i++) {
                    ((ViewGroup) p).setClipChildren(false);
                    ((ViewGroup) p).setClipToPadding(false);
                    p = p.getParent();
                }
            }
        } else if (fg instanceof Glyph) {
            v.setForeground(null);
            v.setTag(HID_TAG, null);
            if (v instanceof ImageView) ((ImageView) v).setImageAlpha(255);
        }
    }

    private static final int K_DIGG = 0, K_COMMENT = 1, K_COLLECT = 2, K_SHARE = 3;

    private static int kindOf(View v) {
        String n = v.getClass().getName();
        for (int i = 0; i < CLS.length; i++) if (CLS[i].equals(n)) return i;
        return K_SHARE;
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
        private final int color;

        Plate(float ratio, int color) {
            this.ratio = ratio;
            this.color = color;
        }

        @Override
        public void draw(Canvas cv) {
            drawPlate(cv, getBounds(), ratio, color);
        }
    }

    static void drawPlate(Canvas cv, Rect bd, float ratio, int color) {
        try {
            DanmakuConfig c = DanmakuHook.cfg;
            int pct = Math.min(100, c.iconBg);
            if (!c.iconPlate || pct <= 0) return;
            float r = Math.min(bd.width(), bd.height()) * ratio * Math.max(20, Math.min(100, c.iconSize)) / 200f;
            if (r < 1f) return;
            float cx = bd.exactCenterX(), cy = bd.exactCenterY();
            TabGlass.drawGlass(cv, cx - r, cy - r, cx + r, cy + r, pct / 100f, 1f, color, false, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 图标玻璃（1.45.2 起自己画）：前景里画心形 / 气泡 / 星星 / 箭头的玻璃图标（偏白的半透明 + 柔和内发光 + 亮边 + 柔影），原图标 setImageAlpha(0) 藏起来。
     * 玻璃画一次缓存成硬件位图（按 种类 + 状态 + 大小），之后每次只是一次 drawBitmap。
     * 状态（已点赞 / 已收藏）从外层 LinearLayout 的无障碍描述读：「已点赞，喜欢N」「已选中，收藏N」（TextRec 导出里见过），点赞变红、收藏变黄；
     * 只在 draw 时向上找最多 4 层父级的描述，字符串前缀比较，没有分配。读不到就按未选中画。
     */
    private static final class Glyph extends TabGlass.Glass {
        private static final int N = 8;
        private static final Bitmap[] BM = new Bitmap[N];
        private static final int[] KEY = new int[N];
        private static int next;
        private final int kind;

        Glyph(int kind) {
            this.kind = kind;
        }

        @Override
        public void draw(Canvas cv) {
            Callback cb = getCallback();
            if (cb instanceof View) paint(cv, getBounds(), kind, (View) cb);
        }

        /** 画一个玻璃图标（bd = 控件范围）；画出来之后才把原图标藏起来（画不出来时原图标还在）。 */
        static void paint(Canvas cv, Rect bd, int kind, View v) {
            try {
                DanmakuConfig c = DanmakuHook.cfg;
                if (!c.iconFill) return;
                float unit = Math.min(bd.width(), bd.height()) * (kind == K_COLLECT ? COLLECT_RATIO : 1f);
                int s = Math.round(unit * Math.max(20, Math.min(200, c.iconGlyph)) / 100f);
                if (s < 8) return;
                int on = 0;
                if (kind == K_DIGG) {
                    if (v.isSelected()) on = 1; // TextRec：点赞时 DiggAnimationView / DiggAnimationLayout 都会 setSelected(true)
                } else if (kind == K_COLLECT) {
                    if (isOn(v, "已选中")) on = 1;
                }
                int key = (kind << 28) | (on << 24) | s;
                Bitmap bm = null;
                for (int i = 0; i < N; i++) {
                    if (BM[i] != null && KEY[i] == key) {
                        bm = BM[i];
                        break;
                    }
                }
                if (bm == null) {
                    bm = render(kind, on == 1, s);
                    if (bm == null) return;
                    BM[next] = bm;
                    KEY[next] = key;
                    next = (next + 1) % N;
                }
                int m = (bm.getWidth() - s) / 2;
                cv.drawBitmap(bm, Math.round(bd.exactCenterX() - s / 2f) - m, Math.round(bd.exactCenterY() - s / 2f) - m, null);
                if (v.getTag(HID_TAG) == null) {
                    v.setTag(HID_TAG, Boolean.TRUE);
                    if (v instanceof ImageView) ((ImageView) v).setImageAlpha(0);
                }
            } catch (Throwable ignored) {
            }
        }

        /** 描述所在的那层父级只找一次（挂到窗口之后第一次画的时候），之后每次只读这一个控件的描述，不再向上找。 */
        private static boolean isOn(View v, String prefix) {
            View h = null;
            Object t = v.getTag(HOLDER_TAG);
            if (t instanceof WeakReference) h = (View) ((WeakReference<?>) t).get();
            if (h == null) {
                ViewParent p = v.getParent();
                for (int i = 0; i < 4 && p instanceof View; i++) {
                    CharSequence d = ((View) p).getContentDescription();
                    if (d != null && d.length() > 0) {
                        h = (View) p;
                        break;
                    }
                    p = p.getParent();
                }
                if (h == null) return false;
                v.setTag(HOLDER_TAG, new WeakReference<>(h));
            }
            CharSequence d = h.getContentDescription();
            return d != null && d.toString().startsWith(prefix);
        }

        private static Path shape(int kind, float s) {
            Path p = new Path();
            switch (kind) {
                case K_DIGG:
                    p.moveTo(.5f * s, .92f * s);
                    p.cubicTo(.1f * s, .62f * s, .02f * s, .38f * s, .16f * s, .2f * s);
                    p.cubicTo(.28f * s, .05f * s, .45f * s, .1f * s, .5f * s, .26f * s);
                    p.cubicTo(.55f * s, .1f * s, .72f * s, .05f * s, .84f * s, .2f * s);
                    p.cubicTo(.98f * s, .38f * s, .9f * s, .62f * s, .5f * s, .92f * s);
                    p.close();
                    break;
                case K_COMMENT: {
                    p.addRoundRect(new RectF(.06f * s, .1f * s, .94f * s, .76f * s), .34f * s, .34f * s, Path.Direction.CW);
                    Path tail = new Path();
                    tail.moveTo(.2f * s, .68f * s);
                    tail.lineTo(.14f * s, .95f * s);
                    tail.lineTo(.44f * s, .74f * s);
                    tail.close();
                    p.op(tail, Path.Op.UNION);
                    Path dots = new Path();
                    float r = .065f * s;
                    dots.addCircle(.30f * s, .43f * s, r, Path.Direction.CW);
                    dots.addCircle(.50f * s, .43f * s, r, Path.Direction.CW);
                    dots.addCircle(.70f * s, .43f * s, r, Path.Direction.CW);
                    p.op(dots, Path.Op.DIFFERENCE);
                    break;
                }
                case K_COLLECT: {
                    float cx = .5f * s, cy = .55f * s, ro = .47f * s, ri = .2f * s;
                    for (int i = 0; i < 10; i++) {
                        double a = -Math.PI / 2 + i * Math.PI / 5;
                        float r = (i & 1) == 0 ? ro : ri;
                        float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                        if (i == 0) p.moveTo(x, y);
                        else p.lineTo(x, y);
                    }
                    p.close();
                    break;
                }
                default:
                    p.moveTo(.6f * s, .1f * s);
                    p.lineTo(.96f * s, .44f * s);
                    p.lineTo(.6f * s, .78f * s);
                    p.lineTo(.6f * s, .6f * s);
                    p.cubicTo(.3f * s, .6f * s, .14f * s, .72f * s, .06f * s, .92f * s);
                    p.cubicTo(.08f * s, .52f * s, .3f * s, .32f * s, .6f * s, .32f * s);
                    p.close();
            }
            return p;
        }

        /** 玻璃图标位图：软件画一次，画法和顶部标签的玻璃（TabGlass.glass）是同一套：薄雾本体 → 竖向高光 → 柔和内发光 → 清晰亮线 → 左上高光 → 下缘弧光和色散；再转成硬件位图。四周留 s×0.14 给影子和模糊。 */
        private static Bitmap render(int kind, boolean on, int s) {
            int m = Math.max(2, Math.round(s * .14f));
            int w = s + 2 * m;
            Bitmap sw = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(sw);
            cv.translate(m, m);
            Path p = shape(kind, s);
            float d = TabGlass.dpf(1f);
            int tint = on ? (kind == K_DIGG ? 0xFF2D55 : 0xFFC83A) : 0xFFFFFF;
            Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 柔影（很淡，只为在亮画面上托住形状）
            cv.save();
            cv.translate(0f, s * .03f);
            pt.setStyle(Paint.Style.FILL);
            pt.setColor(0x33101828);
            pt.setMaskFilter(new BlurMaskFilter(Math.max(1f, s * .05f), BlurMaskFilter.Blur.NORMAL));
            cv.drawPath(p, pt);
            cv.restore();
            pt.setMaskFilter(null);
            // 本体：薄雾（已点赞 / 已收藏时掺上颜色）
            pt.setColor(((on ? 0x80 : 0x38) << 24) | tint);
            cv.drawPath(p, pt);
            // 竖向高光
            pt.setShader(new LinearGradient(0f, 0f, 0f, s, new int[]{0x99FFFFFF, 0x24FFFFFF, 0x10FFFFFF, 0x4DFFFFFF}, new float[]{0f, .45f, .7f, 1f}, Shader.TileMode.CLAMP));
            cv.drawPath(p, pt);
            pt.setShader(null);
            // 柔和内发光（左上宽而亮）
            float gw = Math.max(3f * d, s * .18f);
            LinearGradient glowG = new LinearGradient(s * .15f, 0f, s * .15f + s * .9f, s,
                    new int[]{0xFFFFFFFF, 0x80FFFFFF, 0x1AFFFFFF, 0x66FFFFFF}, new float[]{0f, .32f, .66f, 1f}, Shader.TileMode.CLAMP);
            Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(gw);
            gp.setStrokeJoin(Paint.Join.ROUND);
            gp.setShader(glowG);
            gp.setAlpha(170);
            gp.setMaskFilter(new BlurMaskFilter(Math.max(1f, gw * .5f), BlurMaskFilter.Blur.NORMAL));
            cv.save();
            cv.clipPath(p);
            cv.drawPath(p, gp);
            // 左上一条高光
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setShader(new LinearGradient(s * .14f, 0f, s * .55f, 0f, new int[]{0xCCFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            cv.drawRoundRect(new RectF(s * .14f, s * .1f, s * .55f, s * .1f + s * .1f), s * .05f, s * .05f, hp);
            cv.restore();
            // 清晰亮线
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
            lp.setStyle(Paint.Style.STROKE);
            lp.setStrokeJoin(Paint.Join.ROUND);
            lp.setStrokeWidth(Math.max(1f, d * 1.2f));
            lp.setShader(new LinearGradient(s * .15f, 0f, s * .15f + s * .9f, s,
                    new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x33FFFFFF, 0xB3FFFFFF}, new float[]{0f, .3f, .62f, 1f}, Shader.TileMode.CLAMP));
            cv.drawPath(p, lp);
            // 下缘折射亮弧、左上偏青 / 右下偏暖的色散细边（月牙：把形状错开再抠掉）
            crescent(cv, p, 0f, -1.8f * d, 0x4DFFFFFF, s);
            crescent(cv, p, d, d, 0x3880E0FF, s);
            crescent(cv, p, -d, -d, 0x38FFB070, s);
            try {
                return sw.copy(Bitmap.Config.HARDWARE, false);
            } catch (Throwable t) {
                return null;
            }
        }

        private static void crescent(Canvas cv, Path p, float dx, float dy, int argb, int s) {
            Path q = new Path(p);
            q.offset(dx, dy);
            Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            pt.setColor(argb);
            cv.save();
            cv.clipPath(p);
            cv.clipOutPath(q);
            cv.drawRect(-s, -s, 2f * s, 2f * s, pt);
            cv.restore();
        }
    }
}
