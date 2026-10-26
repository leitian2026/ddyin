package com.danmaku.randcolor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * 音量浮窗（1.41.0）：竖条，盖在抖音当前页面的窗口里，可拖动位置。参考 VolFloat（root2）的音量调节，只取「怎么接到系统音量」：
 * AudioManager 的 STREAM_MUSIC（getStreamVolume / getStreamMaxVolume / setStreamVolume，flags = 0 不弹系统音量条），
 * 系统音量变了（实体键 / 别处改）靠广播 android.media.VOLUME_CHANGED_ACTION 刷新读数。
 *
 * 从上到下：当前音量（拖这里挪浮窗）、大步长 ＋（旁边大字 = 步长）、普通 ＋（旁边小字 1）、普通 －、大步长 －。
 * 大步长在设置页自定义（volStep，1～100）；普通那一对固定 ±1。
 *
 * 不是钩子：不挂任何方法，只在 Activity.onResume（DanmakuHook.hookResume 已经挂着的框架方法）里被调到；没有定时器、没有轮询，
 * 没有长按连发。广播接收器只在浮窗所在窗口可见时注册（切后台 / 离开窗口就注销），只认音乐流的变化、读数没变不刷新；
 * 拖动时只改 translation（不触发整页重新布局），抬手才落到 margin。浮窗不在时不占任何东西。全部用代码画（抖音进程里读不到模块的资源）。
 * 开关关掉或没开：回到前台时把已经加上的浮窗撤掉。位置（dp，左 / 上）单独存在 dyin_pos.properties 里，和别的位置一样不被设置页冲掉。
 */
final class VolPanel {
    private VolPanel() {}

    private static final String TAG = "dyin_vol";
    private static final int W_DP = 42, ROW_DP = 46, VAL_DP = 40;

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
            if (((Bar) old).step == Math.max(1, Math.min(100, DanmakuHook.cfg.volStep))) {
                ((Bar) old).placeAndRefresh();
                return;
            }
            decor.removeView(old); // 设置页改了步长：重建
        }
        Bar bar = new Bar(decor.getContext());
        decor.addView(bar, new FrameLayout.LayoutParams(dp(decor.getContext(), W_DP), -2, Gravity.TOP | Gravity.START));
        bar.placeAndRefresh();
    }

    // ------------------------------------------------------------ 浮窗本体

    private static final class Bar extends LinearLayout {
        private final AudioManager am;
        private final TextView tvVal;
        private final int step;
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
            step = Math.max(1, Math.min(100, DanmakuHook.cfg.volStep));
            setTag(TAG);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setClickable(true); // 自己吃触摸，不传给下面的抖音页面
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(210, 30, 30, 30));
            bg.setCornerRadius(dp(ctx, 16));
            setBackground(bg);
            setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 4));

            tvVal = new TextView(ctx);
            tvVal.setTextColor(Color.WHITE);
            tvVal.setTextSize(15);
            tvVal.setTypeface(null, Typeface.BOLD);
            tvVal.setGravity(Gravity.CENTER);
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
            addView(tvVal, new LayoutParams(-1, dp(ctx, VAL_DP)));

            addView(row(ctx, "＋", String.valueOf(step), true, step));
            addView(row(ctx, "＋", "1", false, 1));
            addView(row(ctx, "－", "1", false, -1));
            addView(row(ctx, "－", String.valueOf(step), true, -step));
        }

        /** 一行：符号 + 旁边的数字（大步长是大字，普通的是小字）；按一下 = 音量加 / 减 delta。 */
        private View row(Context ctx, String sign, String num, boolean big, final int delta) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(HORIZONTAL);
            r.setGravity(Gravity.CENTER);
            r.setClickable(true);
            TextView s = new TextView(ctx);
            s.setText(sign);
            s.setTextColor(Color.WHITE);
            s.setTextSize(big ? 16 : 14);
            s.setGravity(Gravity.CENTER);
            r.addView(s, new LayoutParams(dp(ctx, 16), -1));
            TextView n = new TextView(ctx);
            n.setText(num);
            n.setTextColor(big ? Color.WHITE : 0xFF9AA1B2);
            n.setTextSize(big ? 14 : 9);
            n.setTypeface(null, big ? Typeface.BOLD : Typeface.NORMAL);
            n.setGravity(Gravity.CENTER);
            r.addView(n, new LayoutParams(0, -1, 1f));
            r.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    try {
                        int a = e.getActionMasked();
                        if (a == MotionEvent.ACTION_DOWN) {
                            v.setAlpha(0.5f);
                            change(delta);
                        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                            v.setAlpha(1f);
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
            r.setLayoutParams(new LayoutParams(-1, dp(ctx, ROW_DP)));
            return r;
        }

        private void change(int delta) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int next = Math.max(0, Math.min(max, cur + delta));
            if (next != cur) am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
            refresh();
        }

        void refresh() {
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v == shown) return;
            shown = v;
            tvVal.setText(String.valueOf(v));
        }

        /** 按配置里的位置（dp，左 / 上；-1 = 默认：靠右、偏上）摆好，并保证整块在窗口里。 */
        void placeAndRefresh() {
            ViewGroup p = (ViewGroup) getParent();
            if (p == null) return;
            DanmakuConfig c = DanmakuHook.cfg;
            Context ctx = getContext();
            int pw = p.getWidth() > 0 ? p.getWidth() : ctx.getResources().getDisplayMetrics().widthPixels;
            int ph = p.getHeight() > 0 ? p.getHeight() : ctx.getResources().getDisplayMetrics().heightPixels;
            int l = c.volX >= 0 ? dp(ctx, c.volX) : pw - dp(ctx, W_DP) - dp(ctx, 8);
            int t = c.volY >= 0 ? dp(ctx, c.volY) : ph * 2 / 5;
            applyMargins(l, t, pw, ph);
            refresh();
        }

        private void applyMargins(int l, int t, int pw, int ph) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            int w = dp(getContext(), W_DP);
            int h = getHeight() > 0 ? getHeight() : dp(getContext(), ROW_DP * 4 + VAL_DP + 8);
            lp.leftMargin = Math.max(0, Math.min(Math.max(0, pw - w), l));
            lp.topMargin = Math.max(0, Math.min(Math.max(0, ph - h), t));
            setLayoutParams(lp);
        }

        /** 拖中间的读数挪浮窗；抬手保存位置（dp）。 */
        private boolean drag(MotionEvent e) {
            ViewGroup p = (ViewGroup) getParent();
            if (p == null) return true;
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

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
