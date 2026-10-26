package com.danmaku.randcolor;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 直播间数据（开播时间、累计观看人数、在线人数）：在直播间页面上叠一块文字，字号、颜色、格式在设置页调，位置（左上角占「剩余空间」的百分比）在抖音的直播间里用浮窗拖（见下面「位置调节」）。
 * 只在直播间页面里画：文字挂在每个直播间页面（LivePlayFragmentV2）自己的根控件的 ViewOverlay 上，页面没了文字就没了，别的界面碰不到。
 * 挂在页面自己身上还有一个好处：上下滑切直播间时，每个房间的文字跟着自己的页面走，不用判断「当前是哪个房间」。
 *
 * 依据一（TextRec，抖音 40.7.0，textrec-tasks-20261022-073711，这台手机冷启动进直播间 / 切到另一个直播间 / 退出再进）：
 *  - 房间数据类 com.bytedance.android.livesdkapi.depend.model.live.Room，类名没混淆，TextRec 报「钩点可用」。另一个包名
 *    com.bytedance.android.live.base.model.live.Room 在 40.7.0 里不存在，不用管。
 *  - Room#getCreateTime() 被调用到，返回秒级时间戳（#13 / #14），和字段快照里的 this._createTime 一致（同一个房间都是 1792624951）。
 *  - 字段快照里同一个房间的时间字段（this 就是 Room）：
 *      this._createTime = 1792624951   （2026-10-22 07:22:31 +08:00）
 *      this.startTime   = 1792624956   （07:22:36，比创建时间晚 5 秒，只有这一个样本，别的房间差多少不知道）
 *      this._finishTime = 1792625786   this.nowTime = 1792625808   this._idStr = "7699265332413762338"
 *    没有 getStartTime 这样的 getter 的记录（这次只追踪了 getCreateTime），所以 startTime 是直接读字段。
 *  - 直播间页面：页面标签 LivePlayActivity > LivePlayFragmentV2；LivePlayFragmentV2#onViewCreated(View, Bundle) 在 #9 的调用栈里
 *    （LivePlayFragmentV2.onViewCreated ← Fragment.performViewCreated），类名方法名都没混淆，TextRec 标 [稳定]；#7 的栈
 *    LiveVerticalViewPager.finishUpdate → commitNowAllowingStateLoss → LivePlayFragmentV2.onCreate：上下滑的每个房间一个页面（fragment）。
 *
 * 做法（1.40.6 起，不再在对象图里搜 Room，也不再等 getCreateTime）：
 *  - live.page：LivePlayFragmentV2#onViewCreated 之后，记下这个页面（弱引用）和它的根控件。
 *  - live.room：RoomSession 里「参数是 Room 的 void 方法」（TextRec 里叫 LJII，EnterRoomTaskV1 进房时调用）之后，
 *    从 this 里按类型取出这个房间的 LivePlayFragmentV2、从参数取出 Room，记在对应页面上，请求刷新。
 *  - 刷新（主线程）：对每个已记下的页面，读它 Room 的 startTime / _createTime，套模板，设到叠在页面根控件上的 TextView 里，并按位置百分比放好。
 *
 * 依据二（TextRec 40.9.0，textrec-tasks-20261026-083921 / -084459 / -084821，冷启动进直播间）：
 *  - 页面建好（onViewCreated）7343ms；同一个房间的 Room 在 RoomSession#LJII(Room) 7640ms 到位：此时 Room 的 _createTime / startTime 已有值
 *    （-084821 的树里同一个房间 _createTime = 1792963217、startTime = 1792963222，arg0 就是 this.LJLLJ）。
 *    LJII 的调用栈是 EnterRoomTaskV1.LIZ → X.12Qf.LIZ → X.12Qf.LJ，主线程，耗时 5ms。
 *  - 旧触发点太晚：这个房间的 Room#getCreateTime 第一次被调用是 9617ms（主线程，jsbridge BaseInfoMethod），比 LJII 晚约 2 秒；
 *    更早的几次 getCreateTime 在后台线程，返回的是别的房间（预加载）的时间。LJII 之前的 RoomSession#LJIILJJIL(Room) 里 Room 还是空壳（startTime = 0），不能用。
 *  - 同一个页面（LivePlayFragmentV2）在 RoomSession 里有一个字段（TextRec 里叫 LJLJLLL），值就是这个房间的页面；字段名混淆，所以按类型找。
 *  - 上下滑预加载的相邻页面也有 RoomSession，但它们没进房（没有 LJII 调用），进房时才会触发，所以每个房间的文字跟着自己的页面走。
 *  - Room 类名没混淆；_createTime、startTime 字段名没混淆，startTime 比创建时间晚几秒（上面是 5 秒，一台手机、几个样本）。
 *
 * 匹配规则（都只用没混淆的类型，有歧义就一个都不挂）：RoomSession 里「返回 void、只有一个参数、参数类型就是 Room」的方法必须刚好一个，
 * 「类型就是 LivePlayFragmentV2」的字段刚好一个就按字段取页面；不是刚好一个（40.9.0 起 0 个）不再 miss，进房时改用屏幕上正在显示的直播页兜底（onRoomVisible，没上机验证）。
 *
 * 没有证据的部分（找不到就安静地不显示，不换别的办法）：
 *  ①LJII 的参数 / 字段的声明类型是不是正好是 Room / LivePlayFragmentV2（TextRec 只给出运行时类型）；不是的话 miss；
 *  ②页面根控件是不是 ViewGroup（不是就不显示）；叠在根控件上的文字会不会被页面里别的全屏控件盖住；
 *  ③startTime 是不是「观众看到的开播时间」——所以设置页里可以改成显示房间创建时间。
 *
 * 位置调节（1.24.0）：设置页不再调位置。设置页点「去抖音里调整」后配置里 liveAdjust = true，进直播间时这里照常放文字
 * （时间还没取到 / 开关没开也放一行示例文字），并把当前在屏幕上的页面交给 LivePosPanel：它在窗口最上层盖一层触摸层和一个小浮窗，
 * 拖动 = moveTo（改 cfg.liveX / liveY 再 placeAll），完成 / 取消 = 写回配置文件、撤掉浮窗。不加钩子，不加定时器。
 *
 * 没有定时器 / 轮询：文字只在上面的回调、回到前台、根控件尺寸变化这几个事件里更新。累计观看 / 在线人数每次刷新时读一次当前值，
 * 所以不会自己跳动，要等下一次刷新事件才变（没有定时器就没有别的办法，也没有证据能钩到抖音更新人数的那个地方）。
 * 钩子没挂上时功能安静失效，不改变抖音原有行为。
 */
final class LiveHook {
    private LiveHook() {}

    /** 版本相关（40.7.0），类名都没混淆，依据见文件头。 */
    private static final String FRAG = "com.bytedance.android.livesdk.chatroom.LivePlayFragmentV2";
    private static final String ROOM = "com.bytedance.android.livesdkapi.depend.model.live.Room";
    private static final String SESS = "com.bytedance.android.livesdk.chatroom.room.RoomSession";

    /**
     * 房间人数：Room#getStats() 返回的对象（RoomStats）里的两个读取方法，名字是混淆的，只按这两个名字精确找，不放宽。
     * 依据：用户装 1.40.6 取证日志版在抖音 40.9.0 直播间里的日志，同一行里 t3:int=170445、mj:String=10万+、nm:String=3878、o3:String=3878；
     * 用户对着屏幕核对过：t3 = 累计观看人数（mj 是它的显示文本，不用），nm / o3 = 在线人数（两个值一样，这里只用 nm）。
     * 同一个房间里 t3 在多次日志里一路涨（426、2090、10142、164393），nm 在推荐页里是 17、29、47、50 这样的小数。
     * 抖音更新后这两个名字会变：找不到那一项就显示「-」，其余照常，不会换别的方法顶上；需要重新取证（读 RoomStats 里的方法）。
     */
    private static final String STAT_TOTAL = "t3";
    private static final String STAT_ONLINE = "nm";

    private static final int MAX_PAGES = 6;       // 同时记住的直播间页面（上下滑时前后几个页面都在）

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            PENDING.set(false);
            try {
                refreshAll();
            } catch (Throwable ignored) {
            }
        }
    };

    private static final XC_MethodHook PAGE_HOOK = new PageHook();
    private static final XC_MethodHook SESS_HOOK = new SessionHook();

    private static final HookGuard.Filter VIEW_CREATED = new HookGuard.Filter() {
        @Override
        public boolean ok(Method m) {
            if (!m.getName().equals("onViewCreated")) return false;
            Class<?>[] t = m.getParameterTypes();
            return t.length == 2 && t[0] == View.class;
        }
    };

    /** Room 类和两个时间字段：安装阶段按名字找好并缓存，回调里只做取值。 */
    private static Class<?> roomCls;
    private static Field fStart, fCreate;
    /** RoomSession 里类型就是 LivePlayFragmentV2 的那个字段：安装阶段按类型找好，回调里只取值。 */
    private static Field fSessFrag;

    /** 已记下的直播间页面。只在主线程用。 */
    private static final ArrayList<Page> PAGES = new ArrayList<>();

    private static final class Page {
        final WeakReference<Object> frag;
        // 都用弱引用：页面根控件（onViewCreated 的参数）和叠在它上面的文字。强引用会把已经退出的直播间界面（连带 Activity）留在内存里。
        WeakReference<ViewGroup> rootRef;
        WeakReference<TextView> tvRef;
        View.OnLayoutChangeListener ll;
        WeakReference<Object> roomRef;        // 这个页面的 Room（进房时 RoomSession 交过来的）；弱引用
        int lx = Integer.MIN_VALUE, ly, lw, lh;   // 上次放的位置
        int size = -1, color = Integer.MIN_VALUE; // 上次套的字号 / 颜色
        boolean adj;                              // 文字上现在是不是带着「调节模式」的底色

        Page(Object frag) {
            this.frag = new WeakReference<>(frag);
        }
    }

    // ------------------------------------------------------------ 安装

    /** 启动时试一次，之后每次回到前台再试，直到有结论（见 HookGuard）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        hookRoom(cl);
        HookGuard.hook(HookGuard.LIVE_PAGE, FRAG, VIEW_CREATED, PAGE_HOOK,
                "LivePlayFragmentV2 里没有 onViewCreated(View, Bundle)（直播间页面改了，需要重新追踪页面和调用栈）", cl);
    }

    private static void hookRoom(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.LIVE_ROOM)) return;
        if (!DanmakuHook.cfg.liveTime && !DanmakuHook.cfg.liveAdjust) return; // 直播间数据没开：不挂（没有结论，打开后回到前台再挂）
        Class<?> sc = HookGuard.find(HookGuard.LIVE_ROOM, SESS, cl);
        if (sc == null) return;
        Class<?> rc, fc;
        try {
            rc = Class.forName(ROOM, false, cl);
            fc = Class.forName(FRAG, false, cl);
        } catch (Throwable t) {
            HookGuard.waitFail(HookGuard.LIVE_ROOM); // Room / 页面类还没就绪：下次回到前台再试
            return;
        }
        Field ct = null, st = null, ff = null;
        int nm = 0, nf = 0;
        try {
            ct = timeField(rc, "_createTime");
            st = timeField(rc, "startTime");
            for (Method m : sc.getDeclaredMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (m.getReturnType() == void.class && t.length == 1 && t[0] == rc) nm++;
            }
            for (Field f : sc.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == fc) {
                    ff = f;
                    nf++;
                }
            }
        } catch (Throwable t) { // 方法 / 字段签名里引用的类还没就绪
            HookGuard.waitFail(HookGuard.LIVE_ROOM);
            return;
        }
        if (ct == null && st == null) {
            HookGuard.miss(HookGuard.LIVE_ROOM, "Room 里没有 _createTime / startTime 这两个时间字段（房间数据类改了，需要重新追踪字段快照）");
            return;
        }
        if (nm != 1) {
            HookGuard.miss(HookGuard.LIVE_ROOM, "RoomSession 里「返回 void、参数只有一个 Room」的方法有 " + nm + " 个（要刚好 1 个；抖音改了进房流程，需要重新追踪 RoomSession 进房时收到 Room 的那个方法）");
            return;
        }
        // 页面字段按类型找：正好 1 个就用；0 个或多个（抖音改了字段）不放弃，进房时改用「屏幕上正在显示的直播页」兜底，不依赖 RoomSession 的字段。
        if (nf == 1) {
            try {
                ff.setAccessible(true);
            } catch (Throwable t) {
                ff = null;
            }
        } else {
            ff = null;
        }
        roomCls = rc;
        fCreate = ct;
        fStart = st;
        fSessFrag = ff;
        final Class<?> room = rc;
        HookGuard.hook(HookGuard.LIVE_ROOM, SESS, new HookGuard.Filter() {
            @Override
            public boolean ok(Method m) {
                Class<?>[] t = m.getParameterTypes();
                return m.getReturnType() == void.class && t.length == 1 && t[0] == room;
            }
        }, SESS_HOOK, "RoomSession 里没有进房时收到 Room 的方法（抖音改了进房流程，需要重新追踪）", cl);
    }

    /** 按名字在类（和父类）里找一个数值字段；找不到或不是数值返回 null。 */
    private static Field timeField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                Class<?> t = f.getType();
                if (t != long.class && t != int.class && !Number.class.isAssignableFrom(t)) return null;
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 钩子

    /** 直播间页面的视图建好了：记下页面，请求刷新。 */
    private static final class PageHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                Object[] a = p.args;
                if (p.thisObject == null || a == null || a.length < 1 || !(a[0] instanceof ViewGroup)) return;
                onPage(p.thisObject, (ViewGroup) a[0]);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 进房：RoomSession 收到这个房间的 Room（此时 startTime / _createTime 已有值）。取出它的页面，把 Room 记在页面上，请求刷新。
     * 回调里只做：两次类型判断、一次字段取值、一次弱引用分配、一条（合并过的）主线程消息；不搜对象图、不打日志。
     */
    private static final class SessionHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.liveTime && !DanmakuHook.cfg.liveAdjust) return;
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                Object[] a = p.args;
                Field ff = fSessFrag;
                if (p.thisObject == null || a == null || a.length != 1 || a[0] == null) return;
                Object frag = ff == null ? null : ff.get(p.thisObject);
                if (frag != null) onRoom(frag, a[0]);
                else onRoomVisible(a[0]); // 字段没找到 / 值为空：交给屏幕上正在显示的直播页
            } catch (Throwable ignored) {
            }
        }
    }

    static void requestRefresh() {
        if (PENDING.compareAndSet(false, true)) MAIN.post(REFRESH);
    }

    // ------------------------------------------------------------ 页面

    private static void onPage(Object frag, ViewGroup root) {
        Page pg = null;
        for (Iterator<Page> it = PAGES.iterator(); it.hasNext(); ) {
            Page q = it.next();
            Object f = q.frag.get();
            if (f == null) {
                dispose(q);
                it.remove();
            } else if (f == frag) {
                pg = q;
            }
        }
        if (pg == null) {
            if (PAGES.size() >= MAX_PAGES) dispose(PAGES.remove(0)); // 最早记下的那个
            pg = new Page(frag);
            PAGES.add(pg);
        } else {
            dispose(pg); // 同一个页面重建了视图：旧根控件上的文字撤掉
        }
        pg.rootRef = new WeakReference<>(root);
        requestRefresh();
    }

    /** 这个页面的房间数据到了：记在页面上（页面还没记下就什么都不做），请求刷新。主线程。 */
    private static void onRoom(Object frag, Object room) {
        for (Page pg : PAGES) {
            if (pg.frag.get() == frag) {
                pg.roomRef = new WeakReference<>(room);
                requestRefresh();
                return;
            }
        }
    }

    /**
     * 兜底：不知道 Room 属于哪个页面时，交给屏幕上露出面积最大的直播页；都不在屏幕上就交给最后记下的那个页面。
     * 进房方法只在真正进房的那个房间触发（预加载的相邻页面不触发），所以进房时的当前页就是这个房间的页面。主线程。
     */
    private static void onRoomVisible(Object room) {
        Page best = null;
        int bestArea = 0;
        for (Page pg : PAGES) {
            ViewGroup r = rootOf(pg);
            if (r == null || pg.frag.get() == null) continue;
            int a = visibleArea(r);
            if (a > bestArea) {
                best = pg;
                bestArea = a;
            }
        }
        if (best == null && !PAGES.isEmpty()) best = PAGES.get(PAGES.size() - 1);
        if (best == null) return;
        best.roomRef = new WeakReference<>(room);
        requestRefresh();
    }

    /** 回到前台（配置刚重读过）：按新的开关 / 位置 / 字号 / 颜色 / 格式重新套一遍。 */
    static void onResume() {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return;
            refreshAll();
        } catch (Throwable ignored) {
        }
    }

    private static void refreshAll() {
        if (PAGES.isEmpty()) {
            LivePosPanel.end();
            return;
        }
        DanmakuConfig c = DanmakuHook.cfg;
        boolean adj = c.liveAdjust;
        ViewGroup best = null;
        int bestArea = 0;
        for (Iterator<Page> it = PAGES.iterator(); it.hasNext(); ) {
            Page pg = it.next();
            Object frag = pg.frag.get();
            ViewGroup root = frag == null ? null : rootOf(pg);
            if (root == null) {
                dispose(pg);
                it.remove();
                continue;
            }
            if (!c.liveTime && !adj) {
                hide(pg);
                continue;
            }
            ensureListener(pg, root);
            long sec = timeOf(pg, c.liveSrc), total = totalOf(pg), online = onlineOf(pg);
            String text = sec > 0 || total >= 0 || online >= 0 ? LiveFormat.render(c.liveFormat, sec, total, online) : null; // 三项都没取到就不显示
            if (text == null && adj) text = LiveFormat.render(c.liveFormat, System.currentTimeMillis() / 1000L, 170445L, 3878L); // 调位置时什么都没取到也放示例
            if (text == null) {
                hide(pg);
                continue;
            }
            show(pg, text, c);
            if (adj) {
                int a = visibleArea(root);
                if (a > bestArea) {
                    best = root;
                    bestArea = a;
                }
            }
        }
        if (adj) LivePosPanel.sync(best);
        else LivePosPanel.end();
    }

    private static final Rect TMP = new Rect();

    /** 页面根控件在屏幕上露出来的面积；不在屏幕上 / 被隐藏返回 0。 */
    private static int visibleArea(ViewGroup r) {
        if (!r.isShown() || !r.getGlobalVisibleRect(TMP)) return 0;
        return TMP.width() * TMP.height();
    }

    // ------------------------------------------------------------ 读人数（累计观看 / 在线）

    /** Room#getStats()（名字没混淆），第一次用时按名字找好并缓存。 */
    private static Method mGetStats;
    /** getStats() 返回对象的运行时类，以及这个类里认出的两个读取方法；类变了才重新找。 */
    private static Class<?> statsCls;
    private static Method mTotal, mOnline;

    /** 累计观看人数；Room 还没交过来 / 方法没找到 / 值读不出来都返回 -1。 */
    private static long totalOf(Page pg) {
        return countOf(pg, true);
    }

    /** 在线人数；取不到返回 -1。 */
    private static long onlineOf(Page pg) {
        return countOf(pg, false);
    }

    private static long countOf(Page pg, boolean total) {
        try {
            Object room = pg.roomRef != null ? pg.roomRef.get() : null;
            Class<?> rc = roomCls;
            if (room == null || rc == null) return -1;
            if (mGetStats == null) mGetStats = rc.getMethod("getStats");
            Object st = mGetStats.invoke(room);
            if (st == null) return -1;
            if (st.getClass() != statsCls) {
                statsCls = st.getClass();
                mTotal = statsMethod(statsCls, STAT_TOTAL, false);
                mOnline = statsMethod(statsCls, STAT_ONLINE, true);
            }
            Method m = total ? mTotal : mOnline;
            if (m == null) return -1;
            Object v = m.invoke(st);
            if (v instanceof Number) {
                long n = ((Number) v).longValue();
                return n >= 0 ? n : -1;
            }
            if (v instanceof String) { // 在线人数是字符串；必须全是数字（「1.2万」这种显示文本不认，不估算）
                String t = ((String) v).trim();
                if (t.isEmpty() || t.length() > 18) return -1;
                for (int i = 0; i < t.length(); i++) {
                    char ch = t.charAt(i);
                    if (ch < '0' || ch > '9') return -1;
                }
                return Long.parseLong(t);
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 按名字、无参、非静态、返回类型对得上，在类里找一个读取方法；找不到返回 null（那一项显示「-」，不换别的方法）。 */
    private static Method statsMethod(Class<?> c, String name, boolean str) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != 0 || Modifier.isStatic(m.getModifiers())) continue;
                Class<?> r = m.getReturnType();
                boolean ok = str ? r == String.class : (r == int.class || r == long.class);
                if (!ok) continue;
                m.setAccessible(true);
                return m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------ 找 Room、读时间

    /** 这个页面的开播时间（秒）；Room 还没交过来 / 数据不合理都返回 0。 */
    private static long timeOf(Page pg, int src) {
        Object room = pg.roomRef != null ? pg.roomRef.get() : null;
        if (room == null) return 0;
        long st = secOf(fStart, room), ct = secOf(fCreate, room);
        if (src == 1) return ct;
        return st > 0 ? st : ct;
    }

    /** 读一个时间字段，换算成秒；读不到、不是合理的时间戳（2001 年到 2065 年之外）返回 0。 */
    private static long secOf(Field f, Object room) {
        if (f == null) return 0;
        try {
            Object v = f.get(room);
            if (!(v instanceof Number)) return 0;
            long s = ((Number) v).longValue();
            if (s > 100000000000L) s /= 1000L; // 毫秒
            return s >= 1000000000L && s <= 3000000000L ? s : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 显示

    private static ViewGroup rootOf(Page pg) {
        return pg.rootRef == null ? null : pg.rootRef.get();
    }

    private static TextView tvOf(Page pg) {
        return pg.tvRef == null ? null : pg.tvRef.get();
    }

    private static void show(Page pg, String text, DanmakuConfig c) {
        ViewGroup root = rootOf(pg);
        if (root == null) return;
        TextView t = tvOf(pg);
        if (t == null) {
            t = new LineBg.Row(root.getContext()); // 带文字灰色底（lineBg）；四边的内边距给底留地方
            t.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)); // TextView 重新布局时会读它
            t.setIncludeFontPadding(false);
            t.setShadowLayer(dp(root, 3), 0f, 0f, 0xFF000000); // 一圈黑边，亮背景上也看得清
            pg.tvRef = new WeakReference<>(t);
            root.getOverlay().add(t);
        }
        if (pg.size != c.liveSize) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.liveSize);
            pg.size = c.liveSize;
        }
        int col = c.liveColor != 0 ? c.liveColor : 0xFFFFFFFF;
        if (pg.color != col) {
            t.setTextColor(col);
            pg.color = col;
        }
        if (pg.adj != c.liveAdjust) {
            t.setBackgroundColor(c.liveAdjust ? 0x66000000 : 0); // 调位置时给文字一块底色，看得出它有多大
            pg.adj = c.liveAdjust;
        }
        if (!text.contentEquals(t.getText())) t.setText(text);
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        place(pg);
    }

    /** 页面根控件的布局监听：页面尺寸变了重放文字（调位置时再请求一次刷新，给浮窗）。第一次见到页面就注册，不等文字显示。 */
    private static void ensureListener(final Page pg, ViewGroup root) {
        if (pg.ll != null) return;
        pg.ll = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int tp, int r, int b, int ol, int ot, int or, int ob) {
                try {
                    place(pg);
                    if (DanmakuHook.cfg.liveAdjust) requestRefresh();
                } catch (Throwable ignored) {
                }
            }
        };
        root.addOnLayoutChangeListener(pg.ll);
    }

    private static void hide(Page pg) {
        TextView t = tvOf(pg);
        if (t != null && t.getVisibility() != View.GONE) t.setVisibility(View.GONE);
    }

    /** 撤掉这个页面上的文字和监听。 */
    private static void dispose(Page pg) {
        try {
            ViewGroup root = rootOf(pg);
            TextView t = tvOf(pg);
            if (root != null) {
                if (t != null) root.getOverlay().remove(t);
                if (pg.ll != null) root.removeOnLayoutChangeListener(pg.ll);
            }
        } catch (Throwable ignored) {
        }
        pg.tvRef = null;
        pg.ll = null;
        pg.lx = Integer.MIN_VALUE;
        pg.size = -1;
        pg.color = Integer.MIN_VALUE;
        pg.adj = false;
    }

    /**
     * 按位置百分比放好：左 = (页面宽 − 文字宽) × x%，上 = (页面高 − 文字高) × y%，所以 0～100% 文字都完整在页面里。
     * 叠在 ViewOverlay 上的控件系统不会替它测量 / 布局，这里自己做；页面尺寸变了由根控件的布局监听再放一次。
     */
    private static void place(Page pg) {
        ViewGroup root = rootOf(pg);
        TextView t = tvOf(pg);
        if (root == null || t == null || t.getVisibility() != View.VISIBLE) return;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        t.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
        int tw = t.getMeasuredWidth(), th = t.getMeasuredHeight();
        DanmakuConfig c = DanmakuHook.cfg;
        int l = Math.max(0, (int) ((w - tw) * (c.liveX / 100f)));
        int tp = Math.max(0, (int) ((h - th) * (c.liveY / 100f)));
        t.layout(l, tp, l + tw, tp + th);
        if (l != pg.lx || tp != pg.ly || tw != pg.lw || th != pg.lh) {
            pg.lx = l;
            pg.ly = tp;
            pg.lw = tw;
            pg.lh = th;
            root.invalidate();
        }
    }

    /** 位置变了（浮窗里拖动 / 微调）：每个页面上的文字重新放一遍。 */
    static void placeAll() {
        for (Page pg : PAGES) place(pg);
    }

    /**
     * 浮窗里拖动：文字从 (sx%, sy%) 起跟着手指移动 (dx, dy) 像素，换算回百分比写进 cfg 再放。
     * 位置公式是 左 = (页面宽 − 文字宽) × x%，所以手指移动 dx 像素 = 百分比变 dx / (页面宽 − 文字宽) × 100，文字正好跟手；
     * 文字比页面还宽 / 高的那个方向不动。
     */
    static void moveTo(ViewGroup root, float sx, float sy, float dx, float dy) {
        for (Page pg : PAGES) {
            if (rootOf(pg) != root) continue;
            TextView t = tvOf(pg);
            if (t == null) return;
            float rx = root.getWidth() - t.getMeasuredWidth(), ry = root.getHeight() - t.getMeasuredHeight();
            DanmakuConfig c = DanmakuHook.cfg;
            if (rx > 0) c.liveX = Math.max(0, Math.min(100, Math.round(sx + dx * 100f / rx)));
            if (ry > 0) c.liveY = Math.max(0, Math.min(100, Math.round(sy + dy * 100f / ry)));
            break;
        }
        placeAll();
    }

    private static float dp(View v, int n) {
        return n * v.getResources().getDisplayMetrics().density;
    }
}
