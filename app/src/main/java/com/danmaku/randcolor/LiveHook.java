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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

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
 * 没有定时器 / 轮询：文字只在上面的回调、回到前台、根控件尺寸变化、人数推送这几个事件里更新。
 * 在线人数跟着抖音的人数推送实时变（live.online，见 ONLINE_HOOK 上面的依据）；累计观看每次刷新时读一次 RoomStats 的当前值，
 * 不会自己跳动（累计观看的推送处理在没拿到的 dex 里，还没有钩点；每次在线人数推送触发刷新时顺带重读一次）。
 * 钩子没挂上时功能安静失效，不改变抖音原有行为。
 */
final class LiveHook {
    private LiveHook() {}

    /** 版本相关（40.7.0），类名都没混淆，依据见文件头。 */
    private static final String FRAG = "com.bytedance.android.livesdk.chatroom.LivePlayFragmentV2";
    private static final String ROOM = "com.bytedance.android.livesdkapi.depend.model.live.Room";
    private static final String SESS = "com.bytedance.android.livesdk.chatroom.room.RoomSession";

    /**
     * 累计观看 / 音浪：Room#getStats() 返回的对象（RoomStats）按「协议字段名」读，不用任何混淆名。
     * 依据：TextRec 40.8.0 textrec-tasks-20261026-170321（RoomStats 的数据树，抖音 40.9.0 的 textrec-tasks-20261026-094600 里这几层的字段名一样）：
     *  - RoomStats.userCompositionDelegate.dataNode 是这个消息的数据节点（RoomStats.dataNodeLazy 的 lazy 值也指向它）；
     *  - dataNode.metaForSerialization.d 是 ConcurrentHashMap：编号 → 字段说明，字段说明里有 protoName（字符串）和 tag（int），
     *    实测 tag 1 = id、3 = fan_ticket、4 = money、5 = total_user、6 = gift_uv_count、15 = total_user_str、17 = comment_count；
     *  - dataNode.fieldValues.dataHolder 里是 keyTable（int[]，编号）和 valueTable（Object[]，值），size = 8，hasZeroValue / zeroValue / mask / shift / loadFactor
     *    是一张开放寻址的 int → 对象表（key 0 单独放 zeroValue，这里用到的编号都不是 0）；编号等于 tag 的那一格的 valueTable 就是值。
     *  - 同一个 RoomStats 对象上，混淆名读取方法在进房记录里返回过 22044 → 22084（同一房间里涨），40.9.0 的探针日志里 t3 = 170445；
     *    字段 totalUser 一直是 0（空壳），所以不读字段，读 dataHolder。
     * 没证据的部分：valueTable 里的值是不是 Integer / Long（记录里只展开到「Object; length=16」，没展开元素）：不是 Number 就当取不到，显示「-」；
     *    fan_ticket 在观众端有没有值（探针里 getTicket 一直是 0，这条路是看它在数据节点里到底有没有这一项）。
     * 取不到（路径上任何一层没有 / 编号不在表里 / 值不是数字）就返回 -1，那一项显示「-」，不换别的办法。
     */
    private static final String PROTO_TOTAL = "total_user";
    private static final String PROTO_TICKET = "fan_ticket";

    /**
     * 在线人数（1.40.8 起不再读 RoomStats 的 nm，改成钩人数推送，实时更新）。
     * 依据：反编译抖音 40.9.0（classes14 / 25 / 30 / 38 / 46 / 52 / 53.dex）+ TextRec 40.9.0 textrec-tasks-20261026-105113（任务 2 挂机，右上角在线人数 1330）：
     *  - 服务端推 WebcastRoomStatsMessage（RoomStatsMessage，类名没混淆），X.0AQo 的 LJIIJJI → LJIIJ → LJIIIZ 处理，
     *    最后调 LiveRoomAudienceWidget#P7(long, String, String, boolean)：第 2 个参数 setText 到右上角 StrokeTextView（id oip），也就是屏幕上的在线人数；
     *    第 1 个参数 j 是 data_room_pcu（日志里叫 realCount），第 4 个参数是 isHidden；同一方法里还把第 2 / 3 个参数存进 Room.viewStats._displayMiddle / _displayLong。
     *  - 更新频率约 0.4 次 / 秒（TextRec 同一位置 39 次，间隔 181ms～13s），主线程。
     *  - LiveRoomAudienceWidget 里「返回 void、参数正好是 (long, String, String, boolean)」的方法只有 P7 一个；
     *    「声明类型就是 IRoom」的字段只有 e 一个，值是这个直播间的 Room（onViewCreated 里 dataCenter.get("data_room")）。
     * 匹配规则（只用没混淆的类型，有歧义就一个都不挂）：方法必须刚好 1 个、IRoom 字段必须刚好 1 个，否则 miss。
     * 取值：用第 1 个参数 j（精确的 long，不是「1.2万」那种显示文本）；第 2 个参数是纯数字时必须等于 j（对不上就不显示，不拿别的顶上）；
     *       第 4 个参数（隐藏）为 true 或第 2 个参数为空，抖音自己也把人数藏起来了，这里显示「-」。
     * 没证据的部分：j 一定是「在线人数」（只有变量名 / DataCenter 键名 data_room_pcu 的旁证，没有用屏幕上的数逐个核对过）；
     *       widget 的 IRoom 字段和 RoomSession 交来的 Room 是不是同一个房间（按房间 id 对，对不上就不显示）。
     */
    private static final String WIDGET = "com.bytedance.android.live.room.widget.LiveRoomAudienceWidget";
    private static final String IROOM = "com.bytedance.android.livesdkapi.depend.model.live.IRoom";
    private static final XC_MethodHook ONLINE_HOOK = new OnlineHook();
    /** widget 里类型是 IRoom 的字段；安装阶段按类型找好，回调里只取值。 */
    private static Field fWidgetRoom;
    /** Room#getId()（没混淆，返回 long）；第一次用时找好。 */
    private static Method mRoomId;

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
        long online = -1;                         // 最近一次人数推送里的在线人数；还没收到 / 被隐藏 = -1
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
        hookOnline(cl);
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
            long sec = timeOf(pg, c.liveSrc), total = statOf(pg, PROTO_TOTAL), online = pg.online, ticket = statOf(pg, PROTO_TICKET);
            String text = sec > 0 || total >= 0 || online >= 0 || ticket >= 0 ? LiveFormat.render(c.liveFormat, sec, total, online, ticket) : null; // 几项都没取到就不显示
            if (text == null && adj) text = LiveFormat.render(c.liveFormat, System.currentTimeMillis() / 1000L, 170445L, 3878L, 5200L); // 调位置时什么都没取到也放示例
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

    // ------------------------------------------------------------ 累计观看 / 音浪（读 RoomStats 的协议字段，见 PROTO_TOTAL 上面的依据）

    /** Room#getStats()（名字没混淆），第一次用时按名字找好并缓存。 */
    private static Method mGetStats;
    /** 上一次见到的 RoomStats 运行时类；类变了就清掉下面的缓存。 */
    private static Class<?> statsCls;
    /** protoName → tag；一个 RoomStats 类里是固定的。 */
    private static final HashMap<String, Integer> TAGS = new HashMap<>();
    /** 按「类 → 字段名」缓存找好的 Field（类里没有这个名字的存 null 标记，不反复找）。 */
    private static final HashMap<Class<?>, HashMap<String, Field>> FIELDS = new HashMap<>();

    /**
     * 页面的 Room → getStats() → 数据节点里 protoName 对应编号的值；Room 还没交过来 / 路径上哪一层没有 / 编号不在表里 / 值不是数字，都返回 -1。
     * 主线程，只在刷新时调用，不在钩子回调里。
     */
    private static long statOf(Page pg, String proto) {
        try {
            Object room = pg.roomRef != null ? pg.roomRef.get() : null;
            Class<?> rc = roomCls;
            if (room == null || rc == null) return -1;
            if (mGetStats == null) mGetStats = rc.getMethod("getStats");
            Object st = mGetStats.invoke(room);
            if (st == null) return -1;
            if (st.getClass() != statsCls) {
                statsCls = st.getClass();
                TAGS.clear();
            }
            Object node = fieldOf(fieldOf(st, "userCompositionDelegate"), "dataNode");
            if (node == null) return -1;
            Integer tag = TAGS.get(proto);
            if (tag == null) {
                Object meta = fieldOf(node, "metaForSerialization");
                Object d = fieldOf(meta, "d");
                if (!(d instanceof Map)) return -1;
                for (Object fm : ((Map<?, ?>) d).values()) {
                    if (fm == null || !proto.equals(fieldOf(fm, "protoName"))) continue;
                    Object tg = fieldOf(fm, "tag");
                    if (tg instanceof Integer) {
                        tag = (Integer) tg;
                        TAGS.put(proto, tag);
                    }
                    break;
                }
                if (tag == null) return -1;
            }
            Object holder = fieldOf(fieldOf(node, "fieldValues"), "dataHolder");
            Object ks = fieldOf(holder, "keyTable"), vs = fieldOf(holder, "valueTable");
            if (!(ks instanceof int[]) || !(vs instanceof Object[])) return -1;
            int[] keys = (int[]) ks;
            Object[] vals = (Object[]) vs;
            int n = Math.min(keys.length, vals.length);
            int t = tag;
            for (int i = 0; i < n; i++) {
                if (keys[i] != t) continue;
                Object v = vals[i];
                if (!(v instanceof Number)) return -1;
                long x = ((Number) v).longValue();
                return x >= 0 ? x : -1;
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 按名字读对象上的字段（本类或父类里声明的，名字精确匹配）；对象为 null / 没有这个字段 / 读不出来返回 null。 */
    private static Object fieldOf(Object o, String name) {
        if (o == null) return null;
        try {
            Class<?> c = o.getClass();
            HashMap<String, Field> m = FIELDS.get(c);
            if (m == null) {
                m = new HashMap<>();
                FIELDS.put(c, m);
            }
            Field f = m.get(name);
            if (f == null && !m.containsKey(name)) {
                for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
                    try {
                        f = k.getDeclaredField(name);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (f != null) f.setAccessible(true);
                m.put(name, f);
            }
            return f == null ? null : f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 在线人数（钩人数推送）

    private static void hookOnline(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.LIVE_ONLINE)) return;
        if (!DanmakuHook.cfg.liveTime && !DanmakuHook.cfg.liveAdjust) return; // 直播间数据没开：不挂（没有结论，打开后回到前台再挂）
        Class<?> wc = HookGuard.find(HookGuard.LIVE_ONLINE, WIDGET, cl);
        if (wc == null) return;
        Method target = null;
        Field rf = null;
        int nm = 0, nf = 0;
        try {
            Class<?> ic = Class.forName(IROOM, false, cl);
            for (Method m : wc.getDeclaredMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (m.getReturnType() == void.class && !Modifier.isStatic(m.getModifiers())
                        && t.length == 4 && t[0] == long.class && t[1] == String.class && t[2] == String.class && t[3] == boolean.class) {
                    target = m;
                    nm++;
                }
            }
            for (Field f : wc.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == ic) {
                    rf = f;
                    nf++;
                }
            }
        } catch (Throwable t) { // 方法 / 字段签名里引用的类还没就绪
            HookGuard.waitFail(HookGuard.LIVE_ONLINE);
            return;
        }
        if (nm != 1) {
            HookGuard.miss(HookGuard.LIVE_ONLINE, "LiveRoomAudienceWidget 里「返回 void、参数是 (long, String, String, boolean)」的方法有 " + nm + " 个（要刚好 1 个；抖音改了人数推送的处理，需要重新追踪在线人数的 setText 栈）");
            return;
        }
        if (nf != 1) {
            HookGuard.miss(HookGuard.LIVE_ONLINE, "LiveRoomAudienceWidget 里类型是 IRoom 的字段有 " + nf + " 个（要刚好 1 个；抖音改了 widget 的房间字段）");
            return;
        }
        try {
            rf.setAccessible(true);
            fWidgetRoom = rf;
            XposedBridge.hookMethod(target, ONLINE_HOOK);
        } catch (Throwable t) {
            fWidgetRoom = null;
            HookGuard.miss(HookGuard.LIVE_ONLINE, "hook LiveRoomAudienceWidget 的人数方法失败：" + t);
            return;
        }
        HookGuard.ok(HookGuard.LIVE_ONLINE, "");
    }

    /**
     * 人数推送处理完（after）：args = (在线人数 long, 显示文本 String, 另一段文本 String, 是否隐藏 boolean)。
     * 回调里只做：几次类型判断、一次字段取值、一次 Room#getId 调用、一次整数比较；变了才请求刷新；不打日志。
     */
    private static final class OnlineHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.liveTime && !DanmakuHook.cfg.liveAdjust) return;
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                Field rf = fWidgetRoom;
                Object[] a = p.args;
                if (rf == null || p.thisObject == null || a == null || a.length != 4 || !(a[0] instanceof Long)) return;
                Object room = rf.get(p.thisObject);
                if (room == null) return;
                long cnt = ((Long) a[0]).longValue();
                String shown = a[1] instanceof String ? (String) a[1] : null;
                boolean hidden = a[3] instanceof Boolean && ((Boolean) a[3]).booleanValue();
                onOnline(room, hidden || shown == null || shown.isEmpty() || !consistent(shown, cnt) ? -1 : cnt);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 显示文本是纯数字时必须等于 cnt；带「万」之类的显示文本没法比，认。 */
    private static boolean consistent(String shown, long cnt) {
        int n = shown.length();
        if (cnt < 0 || n > 18) return false;
        long v = 0;
        for (int i = 0; i < n; i++) {
            char ch = shown.charAt(i);
            if (ch < '0' || ch > '9') return true;
            v = v * 10 + (ch - '0');
        }
        return v == cnt;
    }

    /** 这个房间（按房间 id 对）的页面记下新的在线人数；变了才请求刷新。主线程。 */
    private static void onOnline(Object room, long online) {
        long id = idOf(room);
        if (id == 0) return;
        for (Page pg : PAGES) {
            Object r = pg.roomRef != null ? pg.roomRef.get() : null;
            if (r == null || idOf(r) != id) continue;
            if (pg.online != online) {
                pg.online = online;
                requestRefresh();
            }
            return;
        }
    }

    /** Room#getId()；不是 Room / 取不到返回 0。 */
    private static long idOf(Object room) {
        try {
            Class<?> rc = roomCls;
            if (rc == null || !rc.isInstance(room)) return 0;
            if (mRoomId == null) {
                Method m = rc.getMethod("getId");
                if (m.getReturnType() != long.class) return 0;
                mRoomId = m;
            }
            Object v = mRoomId.invoke(room);
            return v instanceof Long ? ((Long) v).longValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
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
        VolPanel.live(root, true); // 音量浮窗「跟随显示」：这一页的数据块在显示
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
        ViewGroup root = rootOf(pg);
        if (root != null) VolPanel.live(root, false);
    }

    /** 撤掉这个页面上的文字和监听。 */
    private static void dispose(Page pg) {
        try {
            ViewGroup root = rootOf(pg);
            TextView t = tvOf(pg);
            if (root != null) {
                VolPanel.live(root, false);
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
