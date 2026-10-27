package com.danmaku.randcolor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 音量浮窗：盖在抖音当前页面窗口里的一组按键，可拖动位置（设置页的「固定位置」默认开着，关掉才能拖）。只取「怎么接到系统音量」：
 * AudioManager 的 STREAM_MUSIC（getStreamVolume / getStreamMaxVolume / setStreamVolume，flags = 0 不弹系统音量条），
 * 系统音量变了（实体键 / 别处改）靠广播 android.media.VOLUME_CHANGED_ACTION 刷新读数。
 *
 * 外观（1.42.0）：没有底板，每个键自己是独立的立体按键（渐变 + 描边 + 按下变深）。
 * 竖放从上到下 / 横放从左到右：当前音量（拖这里挪浮窗；数字 + 按音量比例填充的电平）、
 * 大步长一组（橙色，竖放上 ＋ 下 －，横放左 － 右 ＋，两键之间只隔 1 dp，外轮廓连在一起）、
 * ±1 一组（蓝色，同样的连体样式）。同一组加减一眼能看出是一对，两组靠颜色和间隔分开。
 * 大步长在设置页自定义（volStep，1～100）；普通那一对固定 ±1；横放 / 竖放在设置页选（volHoriz）。
 *
 * 不是钩子：不挂任何方法，只在 Activity.onResume（DanmakuHook.hookResume 已经挂着的框架方法）里被调到；没有定时器、没有轮询，
 * 没有长按连发。广播接收器只在浮窗所在窗口可见时注册（切后台 / 离开窗口就注销），只认音乐流的变化、读数没变不刷新（也不重绘）；
 * 拖动时只改 translation（不触发整页重新布局），抬手才落到 margin。画笔 / 路径 / 背景都在创建时一次建好，onDraw 里不分配对象。
 * 全部用代码画（抖音进程里读不到模块的资源）。开关关掉或没开：回到前台时把已经加上的浮窗撤掉。
 * 位置（dp，左 / 上）单独存在 dyin_pos.properties 里，和别的位置一样不被设置页冲掉。
 */
final class VolPanel {
    private VolPanel() {}

    private static final String TAG = "dyin_vol";
    // 沿条方向的各段按比例分配总长（设置页的「长」）：当前音量 44 : 间隔 8 : 一组 81 : 间隔 8 : 一组 81 = 222，默认长度 222 dp 时就是原来的尺寸
    private static final float VAL_W = 44f, SEP_W = 8f, PAIR_W = 81f;
    private static final int GAP_DP = 1;     // 同一组加减键之间的缝
    private static final int OUT_R = 12, IN_R = 4; // 同组键的外侧 / 内侧圆角（dp）
    private static final int ORANGE_TOP = 0xF0FFA24A, ORANGE_BOT = 0xF0F26A0A, ORANGE_DOWN = 0xFFB84A00;
    private static final int BLUE_TOP = 0xF05B9BFF, BLUE_BOT = 0xF02F63E0, BLUE_DOWN = 0xFF1E3FA8;
    private static final int STROKE = 0xB3FFFFFF;

    /** 每次回到前台：开着就确保这个窗口里有浮窗（有就只刷新位置和读数），关着就撤掉。主线程。 */
    static void onResume(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        View top = a.getWindow().getDecorView();
        if (!(top instanceof FrameLayout)) return;
        FrameLayout decor = (FrameLayout) top;
        View old = decor.findViewWithTag(TAG);
        if (!DanmakuHook.cfg.volOn) {
            if (old != null && old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
            return;
        }
        if (old instanceof Bar) {
            Bar ob = (Bar) old;
            if (ob.step == Math.max(1, Math.min(100, DanmakuHook.cfg.volStep)) && ob.horiz == DanmakuHook.cfg.volHoriz
                    && ob.wDp == DanmakuHook.cfg.volW && ob.lDp == DanmakuHook.cfg.volL) {
                ((Bar) old).placeAndRefresh();
                return;
            }
            decor.removeView(old); // 设置页改了步长 / 方向 / 大小：重建
        }
        Bar bar = new Bar(decor.getContext());
        decor.addView(bar, new FrameLayout.LayoutParams(bar.barW(), bar.barH(), Gravity.TOP | Gravity.START));
        bar.placeAndRefresh();
    }

    // ------------------------------------------------------------ 浮窗本体

    private static final class Bar extends LinearLayout {
        private final AudioManager am;
        private final Val tvVal;
        private final int step, max;
        private final boolean horiz;
        private final int wDp, lDp; // 建的时候的宽 / 长（dp），设置页改了就重建
        private float downRawX, downRawY;
        private int startL, startT;
        private boolean receiverOn;
        private int shown = -1; // 当前显示的音量，没变就不刷新

        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    // 只认音乐流（别的流变了不用刷新）
                    if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return;
                    refresh();
                } catch (Throwable ignored) {
                }
            }
        };

        Bar(final Context ctx) {
            super(ctx);
            am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            step = Math.max(1, Math.min(100, DanmakuHook.cfg.volStep));
            horiz = DanmakuHook.cfg.volHoriz;
            wDp = Math.max(DanmakuConfig.VOL_W_MIN, Math.min(DanmakuConfig.VOL_W_MAX, DanmakuHook.cfg.volW));
            lDp = Math.max(DanmakuConfig.VOL_L_MIN, Math.min(DanmakuConfig.VOL_L_MAX, DanmakuHook.cfg.volL));
            setTag(TAG);
            setOrientation(horiz ? HORIZONTAL : VERTICAL);
            // 没有底板、自己也不吃触摸：只有各个键和读数吃，键之间的空隙还是抖音页面的

            tvVal = new Val(ctx, horiz);
            tvVal.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    try {
                        return drag(e);
                    } catch (Throwable t) {
                        return true;
                    }
                }
            });
            addView(tvVal, lp(VAL_W));
            addView(spacer(ctx, SEP_W));
            addView(pair(ctx, step, true));
            addView(spacer(ctx, SEP_W));
            addView(pair(ctx, 1, false));
        }

        /** 沿条方向按权重 weight 分、另一方向撑满的布局参数。 */
        private LayoutParams lp(float weight) {
            return horiz ? new LayoutParams(0, -1, weight) : new LayoutParams(-1, 0, weight);
        }

        private View spacer(Context ctx, float weight) {
            View v = new View(ctx);
            v.setLayoutParams(lp(weight));
            return v;
        }

        /** 同组加减键之间的缝：固定 GAP_DP。 */
        private View gap(Context ctx) {
            View v = new View(ctx);
            v.setLayoutParams(horiz ? new LayoutParams(dp(ctx, GAP_DP), -1) : new LayoutParams(-1, dp(ctx, GAP_DP)));
            return v;
        }

        /** 一组加减：竖放上 ＋ 下 －，横放左 － 右 ＋；两个键连成一个外轮廓。 */
        private View pair(Context ctx, int n, boolean big) {
            LinearLayout g = new LinearLayout(ctx);
            g.setOrientation(horiz ? HORIZONTAL : VERTICAL);
            View plus = button(ctx, "＋" + n, n, big, horiz ? false : true);
            View minus = button(ctx, "－" + n, -n, big, horiz ? true : false);
            if (horiz) {
                g.addView(minus, lp(1f));
                g.addView(gap(ctx));
                g.addView(plus, lp(1f));
            } else {
                g.addView(plus, lp(1f));
                g.addView(gap(ctx));
                g.addView(minus, lp(1f));
            }
            g.setLayoutParams(lp(PAIR_W));
            return g;
        }

        /** 一个立体键。first = 这一组里排在前面的那个（竖放在上、横放在左），决定哪几个角是大圆角。 */
        private View button(Context ctx, String text, final int delta, boolean big, boolean first) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextColor(Color.WHITE);
            t.setTextSize(text.length() > 3 ? 12 : 16);
            t.setTypeface(null, Typeface.BOLD);
            t.setShadowLayer(dp(ctx, 2), 0, dp(ctx, 1), 0xAA000000);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            t.setClickable(true);
            // 圆角顺序：左上、右上、右下、左下
            float o = dp(ctx, OUT_R), n = dp(ctx, IN_R);
            float[] r;
            if (horiz) r = first ? new float[] {o, o, n, n, n, n, o, o} : new float[] {n, n, o, o, o, o, n, n};
            else r = first ? new float[] {o, o, o, o, n, n, n, n} : new float[] {n, n, n, n, o, o, o, o};
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[] {android.R.attr.state_pressed}, face(ctx, big ? ORANGE_DOWN : BLUE_DOWN, big ? ORANGE_DOWN : BLUE_DOWN, r));
            bg.addState(new int[] {}, face(ctx, big ? ORANGE_TOP : BLUE_TOP, big ? ORANGE_BOT : BLUE_BOT, r));
            t.setBackground(bg);
            t.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    try {
                        int a = e.getActionMasked();
                        if (a == MotionEvent.ACTION_DOWN) {
                            v.setPressed(true);
                            change(delta);
                        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                            v.setPressed(false);
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
            return t;
        }

        private static GradientDrawable face(Context ctx, int top, int bottom, float[] radii) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
            d.setCornerRadii(radii);
            d.setStroke(dp(ctx, 1), STROKE);
            return d;
        }

        private void change(int delta) {
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int next = Math.max(0, Math.min(max, cur + delta));
            if (next != cur) am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
            refresh();
        }

        void refresh() {
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v == shown) return;
            shown = v;
            tvVal.set(v, max);
        }

        /** 按配置里的位置（dp，左 / 上；-1 = 默认：靠右、偏上）摆好，并保证整块在窗口里。 */
        void placeAndRefresh() {
            ViewGroup p = (ViewGroup) getParent();
            if (p == null) return;
            DanmakuConfig c = DanmakuHook.cfg;
            Context ctx = getContext();
            int pw = p.getWidth() > 0 ? p.getWidth() : ctx.getResources().getDisplayMetrics().widthPixels;
            int ph = p.getHeight() > 0 ? p.getHeight() : ctx.getResources().getDisplayMetrics().heightPixels;
            int l = c.volX >= 0 ? dp(ctx, c.volX) : pw - barW() - dp(ctx, 8);
            int t = c.volY >= 0 ? dp(ctx, c.volY) : ph * 2 / 5;
            applyMargins(l, t, pw, ph);
            refresh();
        }

        private int barW() {
            return dp(getContext(), horiz ? lDp : wDp);
        }

        private int barH() {
            return dp(getContext(), horiz ? wDp : lDp);
        }

        private void applyMargins(int l, int t, int pw, int ph) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            int w = getWidth() > 0 ? getWidth() : barW();
            int h = getHeight() > 0 ? getHeight() : barH();
            lp.leftMargin = Math.max(0, Math.min(Math.max(0, pw - w), l));
            lp.topMargin = Math.max(0, Math.min(Math.max(0, ph - h), t));
            setLayoutParams(lp);
        }

        /** 拖中间的读数挪浮窗（设置页「固定位置」开着时不动）；抬手保存位置（dp）。 */
        private boolean drag(MotionEvent e) {
            ViewGroup p = (ViewGroup) getParent();
            if (p == null) return true;
            if (DanmakuHook.cfg.volLock) return true; // 固定位置：吃掉触摸，不挪
            int a = e.getActionMasked();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            if (a == MotionEvent.ACTION_DOWN) {
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startL = lp.leftMargin;
                startT = lp.topMargin;
            } else if (a == MotionEvent.ACTION_MOVE) {
                // 只改 translation，不触发整页重新布局；范围限制在窗口内
                int w = getWidth(), h = getHeight();
                int l = Math.max(0, Math.min(Math.max(0, p.getWidth() - w), startL + (int) (e.getRawX() - downRawX)));
                int t = Math.max(0, Math.min(Math.max(0, p.getHeight() - h), startT + (int) (e.getRawY() - downRawY)));
                setTranslationX(l - lp.leftMargin);
                setTranslationY(t - lp.topMargin);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                lp.leftMargin += Math.round(getTranslationX());
                lp.topMargin += Math.round(getTranslationY());
                setTranslationX(0);
                setTranslationY(0);
                setLayoutParams(lp);
                float d = getContext().getResources().getDisplayMetrics().density;
                DanmakuConfig c = DanmakuHook.cfg;
                c.volX = Math.round(lp.leftMargin / d);
                c.volY = Math.round(lp.topMargin / d);
                DanmakuHook.saveCfg();
            }
            return true;
        }

        private void register() {
            if (receiverOn) return;
            IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else getContext().registerReceiver(receiver, f);
            receiverOn = true;
            refresh(); // 不可见期间可能变过
        }

        private void unregister() {
            if (!receiverOn) return;
            try {
                getContext().unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiverOn = false;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (getWindowVisibility() == VISIBLE) register();
        }

        /** 窗口不可见（切后台 / 锁屏）就注销广播，回来再注册。 */
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) register();
            else unregister();
        }

        @Override
        protected void onDetachedFromWindow() {
            unregister();
            super.onDetachedFromWindow();
        }
    }

    /** 当前音量：圆角块，数字居中，按音量比例从下（横放从左）填充一层绿色电平；拖它挪浮窗。 */
    private static final class Val extends TextView {
        private final boolean horiz;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint level = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF box = new RectF();
        private final float radius;
        private float ratio;

        Val(Context ctx, boolean horiz) {
            super(ctx);
            this.horiz = horiz;
            radius = dp(ctx, OUT_R);
            track.setColor(0x4DFFFFFF);
            level.setColor(0xD934C759);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(ctx, 1));
            edge.setColor(STROKE);
            setTextColor(Color.WHITE);
            setTextSize(18);
            setTypeface(null, Typeface.BOLD);
            setShadowLayer(dp(ctx, 2), 0, dp(ctx, 1), 0xAA000000);
            setGravity(Gravity.CENTER);
            setIncludeFontPadding(false);
            setClickable(true);
        }

        void set(int v, int max) {
            ratio = Math.max(0f, Math.min(1f, v / (float) max));
            setText(String.valueOf(v));
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float half = edge.getStrokeWidth() / 2f;
            box.set(half, half, w - half, h - half);
            clip.reset();
            clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRoundRect(box, radius, radius, track);
            if (ratio > 0f) {
                c.save();
                c.clipPath(clip);
                if (horiz) c.drawRect(box.left, box.top, box.left + box.width() * ratio, box.bottom, level);
                else c.drawRect(box.left, box.bottom - box.height() * ratio, box.right, box.bottom, level);
                c.restore();
            }
            c.drawRoundRect(box, radius, radius, edge);
            super.onDraw(c);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
