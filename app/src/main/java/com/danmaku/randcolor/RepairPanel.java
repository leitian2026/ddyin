package com.danmaku.randcolor;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.WeakHashMap;

/**
 * 修复状态浮窗（设置页「显示修复状态浮窗」，repairStatus，默认关，1.45.19）：盖在抖音当前页面窗口里的一小块文字，
 * 让人看得到「自动修复」到底有没有在工作。显示：
 *  - 自动检查（autoCheckRepair）开没开；开着时上次检查的时刻、这次清掉了几个「进行中」的状态（名字见 NAMES）、累计检查次数和清理个数；
 *  - 手势收尾：双击改评论（图文帖）吞手势没有收尾、被后来的事件当场作废的累计次数（CommentHook.gestureExpired，和自动检查开不开无关）；
 *  - 钩子：已挂上几个 / 共几个，还没结论的（类还没找到；功能开关没开的钩子不会去挂，也停在这里，不是故障）、未适配的、类没找到的个数，以及没结论 / 没挂上的钩子 id（HookGuard 的状态）。
 * 浮窗底部有一个「复制」按钮（1.45.20）：把浮窗现在显示的全部文字放进剪贴板，方便发出来看。
 * 它只显示模块自己已经有的状态，不判断「已挂钩但行为异常」（做不到，见 README「运行时自动检查 / 修复」）：钩子显示已挂上不等于功能一定正常。
 *
 * 不是钩子：不挂任何方法。回到前台时（DanmakuHook.hookResume 里已经挂着的 Activity.onResume）确保这个窗口里有浮窗并刷新一次；
 * 之后只在「状态变了」时刷新：钩子状态变了（HookGuard.flush）、吞手势被作废（CommentHook）。没有定时器、没有轮询；
 * 关着开关时 changed() 一次静态读就返回。刷新合并成一条消息（pending），文字没变就不 setText。
 * 位置（dp，左 / 上）在抖音里按住浮窗拖，抬手存进 dyin_pos.properties（repairX / repairY），和别的浮窗一样不被设置页冲掉。
 * 开关关掉：回到前台时把这个窗口里已经加上的浮窗撤掉。
 *
 * 没有证据的部分：没上机；浮窗在抖音各个 Activity 的窗口根（FrameLayout）里加得上、不挡操作，是按 VolPanel 同样的做法推断的。
 */
final class RepairPanel {
    private RepairPanel() {}

    private static final String TAG = "dyin_repair";
    /** CommentHook.lastMask 各位对应的名字（位的含义见 CommentHook.M_*）。 */
    private static final String[] NAMES = {"拦截判断中", "双击已识别", "自认双击", "图文触摸中", "正在吞手势"};

    /** 开关开着（回到前台时按配置置）。关着时 changed() 一次静态读就返回。 */
    private static volatile boolean on;
    /** 已经有一条刷新消息在排队，不再重复排。 */
    private static volatile boolean pending;
    private static Handler handler;
    /** 窗口根 → 这个窗口里的浮窗。值用弱引用：浮窗的父控件就是窗口根。只在主线程用。 */
    private static final WeakHashMap<View, WeakReference<Panel>> PANELS = new WeakHashMap<>();
    private static final int[] COUNTS = new int[4];

    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            pending = false;
            try {
                if (!on) return;
                String t = build();
                for (WeakReference<Panel> w : PANELS.values()) {
                    Panel p = w == null ? null : w.get();
                    if (p != null) p.show(t);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    /** 每次回到前台：开着就确保这个窗口里有浮窗（有就只摆位置和刷新文字），关着就撤掉。主线程。 */
    static void onResume(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        boolean want = DanmakuHook.cfg.repairStatus;
        on = want;
        if (!want && PANELS.isEmpty()) return; // 关着、也没有旧浮窗要撤：一次静态读就返回
        View top = a.getWindow().getDecorView();
        if (!(top instanceof FrameLayout)) return;
        FrameLayout decor = (FrameLayout) top;
        WeakReference<Panel> w = PANELS.get(decor);
        Panel old = w == null ? null : w.get();
        if (old != null && old.getParent() != decor) old = null;
        if (!want) {
            if (old != null) decor.removeView(old);
            PANELS.remove(decor);
            return;
        }
        if (old == null) {
            old = new Panel(decor.getContext());
            decor.addView(old, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            PANELS.put(decor, new WeakReference<>(old));
        }
        old.place();
        old.show(build());
    }

    /** 状态变了（钩子状态 / 吞手势被作废）：开着就排一条刷新消息，关着或已经排着就什么都不做。可以在任何线程调。 */
    static void changed() {
        if (!on || pending) return;
        try {
            pending = true;
            Handler h = handler;
            if (h == null) handler = h = new Handler(Looper.getMainLooper());
            h.post(REFRESH);
        } catch (Throwable ignored) {
            pending = false;
        }
    }

    /** 拼要显示的文字。只在刷新时调（分配 StringBuilder 和几个字符串），不在热路径。 */
    private static String build() {
        DanmakuConfig c = DanmakuHook.cfg;
        StringBuilder b = new StringBuilder(220);
        b.append("修复状态 · 自动检查 ").append(c.autoCheckRepair ? "开" : "关（不清理）");
        if (c.autoCheckRepair) {
            b.append('\n');
            if (CommentHook.repairRuns == 0) {
                b.append("还没检查过（回到前台时检查）");
            } else {
                b.append("上次 ");
                clock(b, CommentHook.lastRepairAt);
                int m = CommentHook.lastMask;
                if (m == 0) {
                    b.append(" · 没有遗留状态");
                } else {
                    b.append(" · 清掉 ");
                    boolean first = true;
                    for (int i = 0; i < NAMES.length; i++) {
                        if ((m & (1 << i)) == 0) continue;
                        if (!first) b.append('、');
                        b.append(NAMES[i]);
                        first = false;
                    }
                }
                b.append("\n累计检查 ").append(CommentHook.repairRuns).append(" 次 · 清理 ").append(CommentHook.repairCleared).append(" 项");
            }
        }
        b.append("\n手势收尾 · 作废 ").append(CommentHook.gestureExpired).append(" 次");
        int total = HookGuard.counts(COUNTS);
        b.append("\n钩子 已挂 ").append(COUNTS[0]).append('/').append(total);
        if (COUNTS[1] > 0) b.append(" · 没结论 ").append(COUNTS[1]);
        if (COUNTS[2] > 0) b.append(" · 未适配 ").append(COUNTS[2]);
        if (COUNTS[3] > 0) b.append(" · 没找到 ").append(COUNTS[3]);
        String bad = HookGuard.problemIds();
        if (!bad.isEmpty()) b.append("\n未挂上：").append(bad);
        String wait = HookGuard.pendingIds();
        if (!wait.isEmpty()) b.append("\n没结论：").append(wait);
        return b.toString();
    }

    /** 本地时间 HH:mm:ss。 */
    private static void clock(StringBuilder b, long ms) {
        long t = ms + TimeZone.getDefault().getOffset(ms);
        int s = (int) ((t / 1000) % 86400);
        if (s < 0) s += 86400;
        two(b, s / 3600).append(':');
        two(b, s / 60 % 60).append(':');
        two(b, s % 60);
    }

    private static StringBuilder two(StringBuilder b, int v) {
        if (v < 10) b.append('0');
        return b.append(v);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static final class Panel extends LinearLayout {
        private final TextView tv;
        private String shown = "";
        private float downRawX, downRawY;
        private int startL, startT;

        Panel(Context ctx) {
            super(ctx);
            setTag(TAG);
            setOrientation(VERTICAL);
            setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xCC000000);
            g.setCornerRadius(dp(ctx, 6));
            setBackground(g);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            tv = new TextView(ctx);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTextColor(0xFFFFFFFF);
            tv.setMaxWidth(dp(ctx, 300));
            addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 复制按钮：把浮窗现在显示的全部文字放进剪贴板；按钮自己吃掉点击，文字区域的触摸仍然交给下面的拖动
            TextView btn = new TextView(ctx);
            btn.setText("复制");
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setTextColor(0xFFFFFFFF);
            btn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x55FFFFFF);
            bg.setCornerRadius(dp(ctx, 6));
            btn.setBackground(bg);
            btn.setOnClickListener(v -> copy());
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.gravity = Gravity.END;
            bl.topMargin = dp(ctx, 4);
            addView(btn, bl);
        }

        /** 点「复制」：把当前显示的文字放进剪贴板，用系统 Toast 提示一次。只在点击时做，没有定时器。 */
        private void copy() {
            try {
                Context c = getContext();
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                cm.setPrimaryClip(ClipData.newPlainText("dyin-repair", shown));
                Toast.makeText(c, "已复制", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }

        void show(String t) {
            if (t.equals(shown)) return; // 文字没变不 setText，不触发重新布局
            shown = t;
            tv.setText(t);
        }

        /** 按配置摆位置（dp，默认靠左偏上）；没变就不 setLayoutParams（它会让整个窗口根重新布局）。 */
        void place() {
            ViewGroup p = (ViewGroup) getParent();
            if (p == null) return;
            DanmakuConfig c = DanmakuHook.cfg;
            Context ctx = getContext();
            int pw = p.getWidth() > 0 ? p.getWidth() : ctx.getResources().getDisplayMetrics().widthPixels;
            int ph = p.getHeight() > 0 ? p.getHeight() : ctx.getResources().getDisplayMetrics().heightPixels;
            int l = c.repairX >= 0 ? dp(ctx, c.repairX) : dp(ctx, 8);
            int t = c.repairY >= 0 ? dp(ctx, c.repairY) : dp(ctx, 90);
            int w = getWidth(), h = getHeight();
            int nl = Math.max(0, Math.min(Math.max(0, pw - w), l));
            int nt = Math.max(0, Math.min(Math.max(0, ph - h), t));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            if (lp.leftMargin == nl && lp.topMargin == nt) return;
            lp.leftMargin = nl;
            lp.topMargin = nt;
            setLayoutParams(lp);
        }

        /** 按住拖动；拖的时候只改 translation（不触发整个窗口重新布局），抬手落到 margin 并保存位置（dp）。 */
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            ViewGroup p = (ViewGroup) getParent();
            if (p == null) return true;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startL = lp.leftMargin;
                startT = lp.topMargin;
            } else if (a == MotionEvent.ACTION_MOVE) {
                int l = Math.max(0, Math.min(Math.max(0, p.getWidth() - getWidth()), startL + (int) (e.getRawX() - downRawX)));
                int t = Math.max(0, Math.min(Math.max(0, p.getHeight() - getHeight()), startT + (int) (e.getRawY() - downRawY)));
                setTranslationX(l - lp.leftMargin);
                setTranslationY(t - lp.topMargin);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                int dx = Math.round(getTranslationX()), dy = Math.round(getTranslationY());
                setTranslationX(0);
                setTranslationY(0);
                if (dx != 0 || dy != 0) { // 只点了一下没拖：不改位置、不写文件
                    lp.leftMargin += dx;
                    lp.topMargin += dy;
                    setLayoutParams(lp);
                    float d = getContext().getResources().getDisplayMetrics().density;
                    DanmakuConfig c = DanmakuHook.cfg;
                    c.repairX = Math.round(lp.leftMargin / d);
                    c.repairY = Math.round(lp.topMargin / d);
                    DanmakuHook.saveCfg();
                }
            }
            return true;
        }
    }
}
