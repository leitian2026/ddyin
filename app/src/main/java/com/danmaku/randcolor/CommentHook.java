package com.danmaku.randcolor;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 把"双击点赞"改成"打开评论"（开关 dtapToComment）。1.29.0 起不再有「右侧爱心按钮改评论」（FeedDiggPresenter#LJJJJJL）。
 * 双击视频：在 MonitorScrollFrameLayout.onInterceptTouchEvent 里识别双击并拦住（dtap.seen + dtap.cut），改为点一下评论按钮；
 *     再用 dtap.click 跳过随后的单击处理（不暂停）。三个钩子的类名、方法名都没混淆。
 * 抖音的识别器漏判时（TextRec：两下点击 onInterceptTouchEvent 都收到了，但 onDoubleTap 没被调到）：dtap.cut 自己按「第一下抬起 → 第二下按下」认双击兜底，
 *     间隔 400ms、落点在系统 DoubleTapSlop 以内，第一下必须是一次点按；点在小的可点控件（按钮）上不算。没有定时器、没有新钩子，只在已有的拦截钩子里存两个数、做一次比较。
 *
 * 图文帖（1.45.16）：图文页里上面这条路认不出双击——MonitorScrollFrameLayout#onInterceptTouchEvent 在单击 / 双击时只收到按下、收不到抬起，onDoubleTap 一次都没被调到。
 *     图文页的触摸是 CommonPhotosInitPresenter#onTouchEvent 转给 LongPressLayout#dispatchTouchEvent 的，所以另外挂两个类名方法名都没混淆的钩子：
 *     dtap.photo（CommonPhotosInitPresenter#onTouchEvent）进来时置一个静态标记、出来时清掉；dtap.lpd（LongPressLayout#dispatchTouchEvent）只在标记置上时工作，
 *     按「第一下抬起 → 第二下按下」认双击（规则和 dtap.cut 自己认的那一条一样：400ms、DoubleTapSlop、第一下必须是点按），认到后往上找这一页的 MonitorScrollFrameLayout，
 *     用同一个 commentTarget / open 打开评论，并把第二下的整个手势（按下到抬起 / 取消）吞掉，LongPressLayout 收不到，红心和点赞不会发生。
 *     找不到评论按钮：不吞、不开，双击照常点赞。没有定时器、没有扫描；普通视频里 dtap.lpd 每次触摸只多一次静态读。
 *     依据（TextRec 41.4.0 (410400)，textrec-tasks-20261126-123958 / 123447；红心的时间见 textrec-tasks-20261126-095859，41.3.0）：
 *      - 图文帖里一次触摸的栈（3 份采样栈每份都有）：View.dispatchTouchEvent ← X.0QY7.onTouch ← X.0QQD.onTouch ← Y.ATListenerS1209S0100000_32.onTouch ← X.12wj.onTouchEvent
 *        ← CommonPhotosInitPresenter.onTouchEvent ← LongPressLayout.dispatchTouchEvent。TextRec 对这两个钩点评级：LongPressLayout#dispatchTouchEvent 是 A 级（覆写框架方法），
 *        CommonPhotosInitPresenter#onTouchEvent 稳定（名字没混淆）；
 *      - 图文帖双击两下，LongPressLayout#dispatchTouchEvent 都收到完整的按下和抬起；MonitorScrollFrameLayout#onInterceptTouchEvent 只收到两次按下（返回 false），没有抬起；onDoubleTap 0 次；
 *      - 红心：第二下按下之后 DiggLayout 里加红心 ImageView，时间上在 LongPressLayout#onTouchEvent 返回之前 1ms（49976 / 49977）。
 *     没有证据的部分：吞掉第二下之后点赞和红心确实不发生（时间对得上，但没有栈证明点赞是 LongPressLayout 里触发的）；LongPressLayout 是这一页 MonitorScrollFrameLayout 的后代（按 40.9.0 的视图树，
 *     41.4.0 的图文页没有抓过视图树）；两份记录里的图文帖都是从搜索进入的详情页（UltraDetailActivity），首页信息流里的图文没有追踪过；没编译、没上机。
 *     1.45.17：不改的时候图文双击会让 LongPressLayout 被点两次（41.3.0 的 View#performClick ×2、41.4.0 的 TYPE_VIEW_CLICKED ×2，都在抬起时），第一下点成暂停、第二下点回播放；
 *     1.45.16 吞掉第二下之后只剩第一下，视频停在暂停上。所以认到双击、吞掉第二下的同时，对这个 LongPressLayout 自己补点一次 performClick（同步调用，不分配）。
 *     没有证据的：补这一次是否真等于原来第二下的效果（只有「两次点击」的记录，没看到点击之后真正切换播放的那个方法）。
 * 1.26.4 起不再有混淆名的双击兜底（BaseListFragmentPanel#LLLIILIL）：它在前面这条路径失效时照样能打开评论，
 * 会把「前面的钩子到底有没有用」盖住，设置页里钩子状态再好看也不知道是不是真的在起作用。
 * 现在这条路径失效 = 双击恢复成点赞，一眼就能看出来。（LLLIILIL 的追踪证据：40.7.0 里一次双击 X.06y9.onTouch 只调它一次，红心和点赞都从它往下走。）
 *
 * 评论按钮的找法（按 id 名直接取，不再遍历 View 树）：
 *  - 在被双击的这一页里取 id 名 comment_container 的控件：afterHookedMethod 里的 this 就是这一页的 MonitorScrollFrameLayout，
 *    pv.findViewById(getIdentifier("comment_container", "id", 包名))；预加载的下一页有它自己的实例，不在这一页的子树里，不会混进来；
 *  - 点击监听就挂在 comment_container 上（TextRec：View#performClick 的 this = FeedDoctorFrameLayout#comment_container）；
 *  - 找不到（id 解析不出 / 这一页没有 / 不可见 / 没有点击监听）：放行，双击照常点赞。
 * 依据（TextRec 40.9.0，textrec-tasks-20261024-085052、textrec-tasks-20261024-085634）：
 *  - 点评论图标那一下的控件链：MonitorScrollFrameLayout#w1m → … → FeedRightScaleView → HPFrameLayout#comment_layout → LinearLayout#e9v
 *    → FeedDoctorFrameLayout#comment_container → com.ss.android.ugc.aweme.feed.widget.right.FeedCommentImageView；
 *    都是在首页信息流里追到的（TextRec 导出里「页面」标签偶尔会变成 FavProfileCommonTabFragment / PostProfileCommonTabFragment，那是后台预加载的个人主页，屏幕上的页面、文案和坐标都没变，不能当成「在喜欢列表里验证过」）；LongPressLayout#qbb 也在 MonitorScrollFrameLayout 的子树里；
 *  - 点图标正中间：View#performClick 的 this = FeedDoctorFrameLayout#comment_container（返回 true，走 PerformClick.run 的正常点击路径）；
 *    点图标下面的数字：this = TextSwitcher（另一个控件）；
 *  - 「评论16，按钮」这段描述同时挂在 LinearLayout#e9v 和 FeedCommentImageView 上；下一页的侧栏会提前预加载（屏幕外就已经设好描述）。
 * 没有证据的部分：只验证了 40.9.0 一个版本；只在首页信息流里验证过，从个人主页进入的视频流没有追踪过；没编译、没上机。
 */
final class CommentHook {
    private CommentHook() {}

    /**
     * 双击的稳定名路径（40.7.0，TextRec：textrec-tasks-20261020-203447 / 20261021-032906 / 20261021-093511）：
     *  - 双击在 MonitorScrollFrameLayout.onInterceptTouchEvent 里被识别：第二下 DOWN 时它的栈是
     *    onInterceptTouchEvent → ATListener.onTouch → FeedPinchPresenterV2 → LiveData → VideoPinchUtil$eventObserver$1
     *    → GestureDetector.onTouchEvent → SimpleOnGestureListener#onDoubleTap（监听器 AdS2S0100000_29 没有重写它，返回 false）。
     *    onDoubleTap 在两次双击里各命中 1 次（首页和个人主页视频流都有），单击、上滑里是 0 次。
     *  - 识别完 onInterceptTouchEvent 才返回，之后第二下 DOWN 才往下发给 LongPressLayout（点赞 LLLIIIL 在那里面）；
     *    点赞的界面更新（「已点赞」）在 onDoubleTap 之后约 42ms。所以在这个方法返回前把结果改成 true（拦截），
     *    LongPressLayout 就收不到第二下 DOWN。
     *  - VideoViewHolderRootView#onInterceptTouchEvent 在第二下的 MOVE 上返回 true 是抖音自己的取消，发生在点赞之后，拦不住，所以不用它。
     * 没有证据的部分：LongPressLayout 是 MonitorScrollFrameLayout 的后代（按栈上的 X.07Ut.dispatchTouchEvent 推断）；
     * 这里返回 true 之后点赞确实不会发生（按安卓的拦截规则推断）。第一下点击已经让视频暂停，这一步拦不住。
     */
    private static final String MONITOR = "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.interestselection.MonitorScrollFrameLayout";
    private static final String GESTURE_LISTENER = "android.view.GestureDetector$SimpleOnGestureListener";
    /**
     * 双击之后视频被暂停（TextRec，40.7.0，textrec-tasks-20261021-105717，开着 dyin 的双击改评论）：
     *  - 单击是「延迟处理」的：任务 2 单击（touch 18413）→ onPlayPause 在 18674，隔 261ms；任务 3 单击（touch 32279）→ 继续播放在 32467，隔 188ms。
     *    继续播放那次的调用栈是 Handler.dispatchMessage ← WeakHandler.handleMessage ← BaseListFragmentPanel.handleMsg ← X.06vk.run
     *    ← FeedComponentsRoot.handleSingleClick ← handleSingleClickInner ← handleVideoPauseAndPlay(Aweme) ← … ← OnUIPlayListenerDelegate（暂停 / 继续是同一个切换）。
     *    这四个 FeedComponentsRoot 方法 TextRec 都标 [稳定]（类名方法名没混淆）。
     *  - 任务 4 双击：第一下 touch 46492，评论页 46671 打开，第二下 touch 46763，onPlayPause 在 46844——比第二下还晚，调用栈和单击暂停那条
     *    完全相同（X.0FaC.run ← X.0FWv.run ← Handler.handleCallback）。时间上正好是「第一下点击的延迟单击处理」在双击之后才执行。
     *    推断：抖音自己的双击处理会取消这条延迟的单击，dyin 拦掉了双击，取消没发生，单击照常执行，把视频切成暂停。
     * 没有直接证据的部分：任务 4 的记录里没有出现 handleSingleClick 的栈（栈被合并成「与 #2 相同」），「单击处理是被延迟执行的」是按时间推的。
     * 做法：拦住双击时记下时刻，之后一小段时间内 handleSingleClick 被调到就跳过（不暂停）。
     */
    private static final String FEED_ROOT = "com.ss.android.ugc.aweme.feed.plato.core.FeedComponentsRoot";
    private static final String CLICK_METHOD = "handleSingleClick";
    /** 评论按钮（图片控件）。类名没混淆；点击监听在它的父级上（见类注释里的依据）。只在运行时按类名比较，不挂钩。 */
    private static final String BTN_CLASS = "com.ss.android.ugc.aweme.feed.widget.right.FeedCommentImageView";
    /** 拦住双击之后多久内，把单击处理跳过（单击处理比第一下点击晚 190~350ms，留够余量，又不影响之后真正的单击）。 */
    private static final long CLICK_SKIP_MS = 800;
    private static volatile long dtapAt;
    private static final XC_MethodHook CLICK_HOOK = new ClickHook();

    /** 同一次双击只打开一次评论。 */
    private static final long REOPEN_GAP_MS = 800;

    private static volatile long lastOpenAt;

    /** 主线程上的触摸分发：正在 MonitorScrollFrameLayout.onInterceptTouchEvent 里 / 这一次里 onDoubleTap 被调到了。 */
    private static boolean inIntercept, dtapSeen;

    /**
     * 自己认双击（抖音的识别器漏判时兜底；做法和系统 GestureDetector 一样：第二下 DOWN 在第一下抬起后 DTAP_GAP_MS 内、落点在 DoubleTapSlop 以内）：
     * 第一下要是「一次点按」（没超过长按时间、没移出 TouchSlop）才记下抬起的时刻和位置。
     * 都在主线程的触摸分发里读写；抖音自己识别到的双击（dtapSeen）和这个走同一条后续路径，同一次双击只打开一次评论。
     */
    private static final long DTAP_GAP_MS = 400;
    private static long downAt, upAt;
    private static float downX, downY, upX, upY, tapX, tapY;
    private static boolean selfDtap;

    private static final XC_MethodHook SEEN_HOOK = new DtapSeenHook();
    private static final XC_MethodHook CUT_HOOK = new DtapCutHook();

    /** 图文帖（见类注释）：图文页的触摸入口和 LongPressLayout，类名都没混淆。 */
    private static final String PHOTO_PRESENTER = "com.bytedance.ies.ugc.aweme.horizon.feed.presenter.CommonPhotosInitPresenter";
    private static final String LONG_PRESS = "com.ss.android.ugc.aweme.feed.ui.LongPressLayout";
    /** 往上找页面容器最多看几层（LongPressLayout 到 MonitorScrollFrameLayout 实测 3 层，留余量）。 */
    private static final int PAGE_UP = 10;
    /** 主线程上的触摸分发：正在 CommonPhotosInitPresenter#onTouchEvent 里 / 正在吞第二下的手势。 */
    private static boolean inPhotos, phSwallow;
    /** 图文页里自己认双击：第一下按下 / 抬起的时刻和位置（LongPressLayout 自己的坐标，两下用同一个控件，坐标系一致）。 */
    private static long phDownAt, phUpAt;
    private static float phDownX, phDownY, phUpX, phUpY;
    private static final XC_MethodHook PHOTO_HOOK = new PhotoMarkHook();
    private static final XC_MethodHook LPD_HOOK = new PhotoDtapHook();

    /** 三个钩子的类名、方法名都没混淆；找不到就放弃并记住（HookGuard），双击点赞照常。另外 dtap.btn 只确认评论按钮的类还在（不挂钩）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        // 名字没混淆的双击路径：识别双击（系统方法）+ 在点赞之前拦住（抖音的类，类名和方法名都没混淆，不放宽匹配）
        HookGuard.hook(HookGuard.DTAP_SEEN, GESTURE_LISTENER, motion("onDoubleTap"), SEEN_HOOK,
                "SimpleOnGestureListener 里没有 onDoubleTap(MotionEvent)（系统类，一般不会出现）", cl);
        HookGuard.hook(HookGuard.DTAP_CUT, MONITOR, motion("onInterceptTouchEvent"), CUT_HOOK,
                "MonitorScrollFrameLayout 里没有 onInterceptTouchEvent(MotionEvent)（抖音改了这个类，需要用 TextRec 重新追踪双击时的触摸分发）", cl);
        HookGuard.hook(HookGuard.DTAP_CLICK, FEED_ROOT, voidNamed(CLICK_METHOD), CLICK_HOOK,
                "FeedComponentsRoot 里没有 void handleSingleClick（抖音改了单击处理，需要用 TextRec 重新追踪单击暂停的调用栈）", cl);
        // 图文帖：LongPressLayout#dispatchTouchEvent 每次触摸都会走到，开关没开不挂（不调 HookGuard，状态保持「还没有结论」）；开了之后回到前台会补挂
        if (DanmakuHook.cfg.dtapToComment) {
            HookGuard.hook(HookGuard.DTAP_PHOTO, PHOTO_PRESENTER, motion("onTouchEvent"), PHOTO_HOOK,
                    "CommonPhotosInitPresenter 里没有 onTouchEvent(MotionEvent)（抖音改了图文页的触摸入口，需要用 TextRec 重新追踪图文双击时的调用栈）", cl);
            HookGuard.hook(HookGuard.DTAP_LPD, LONG_PRESS, motion("dispatchTouchEvent"), LPD_HOOK,
                    "LongPressLayout 里没有 dispatchTouchEvent(MotionEvent)（抖音改了这个类，需要用 TextRec 重新追踪图文双击时的触摸分发）", cl);
        }
        // 评论按钮的类还在不在（只找类不挂钩，和 pub.anchor 一样）：不在 = 双击会放行成点赞，设置页能看到
        if (HookGuard.find(HookGuard.DTAP_BTN, BTN_CLASS, cl) != null) HookGuard.ok(HookGuard.DTAP_BTN, "");
    }

    /** 名字匹配、返回 void 的所有重载（handleSingleClick() / handleSingleClick(Float, Float) 都挂，跳过时返回 null 即可）。 */
    private static HookGuard.Filter voidNamed(final String name) {
        return new HookGuard.Filter() {
            @Override
            public boolean ok(Method m) {
                return m.getName().equals(name) && m.getReturnType() == void.class;
            }
        };
    }

    /** 只挂参数是单个 MotionEvent、名字匹配的重载。 */
    private static HookGuard.Filter motion(final String name) {
        return new HookGuard.Filter() {
            @Override
            public boolean ok(Method m) {
                if (!m.getName().equals(name)) return false;
                Class<?>[] pt = m.getParameterTypes();
                return pt.length == 1 && pt[0] == MotionEvent.class;
            }
        };
    }

    /** onDoubleTap 被调到：只在 MonitorScrollFrameLayout 的拦截判断里才算（别的手势检测器不理）。热路径：两次静态读写，不分配。 */
    private static final class DtapSeenHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (inIntercept) dtapSeen = true;
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * MonitorScrollFrameLayout.onInterceptTouchEvent：进来时记下「正在拦截判断」，出来时如果这一次里识别到了双击，
     * 就把结果改成 true（子视图收不到这个 DOWN，LongPressLayout 里的红心和点赞都不会发生）并打开评论。
     * 找不到评论按钮（commentTarget 返回 null）：放行，不拦、不记 dtapAt，双击照常点赞。
     */
    private static final class DtapCutHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.dtapToComment) return;
                inIntercept = true;
                dtapSeen = false;
                selfDtap = false;
                MotionEvent e = (MotionEvent) p.args[0];
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        long t = e.getEventTime();
                        float x = e.getX(), y = e.getY();
                        if (upAt != 0 && t - upAt <= DTAP_GAP_MS && p.thisObject instanceof View) {
                            float sl = ViewConfiguration.get(((View) p.thisObject).getContext()).getScaledDoubleTapSlop();
                            float dx = x - upX, dy = y - upY;
                            if (dx * dx + dy * dy <= sl * sl) {
                                selfDtap = true;
                                tapX = x;
                                tapY = y;
                            }
                        }
                        upAt = 0;
                        downAt = selfDtap ? 0 : t; // 已经认成双击的这一下，后面的抬起不会再进拦截判断，不留半截记录
                        downX = x;
                        downY = y;
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (downAt != 0 && p.thisObject instanceof View) {
                            long t = e.getEventTime();
                            float sl = ViewConfiguration.get(((View) p.thisObject).getContext()).getScaledTouchSlop();
                            float dx = e.getX() - downX, dy = e.getY() - downY;
                            if (t - downAt < ViewConfiguration.getLongPressTimeout() && dx * dx + dy * dy <= sl * sl) {
                                upAt = t;
                                upX = e.getX();
                                upY = e.getY();
                            }
                        }
                        downAt = 0;
                        break;
                    }
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_CANCEL:
                        upAt = 0;
                        downAt = 0;
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!inIntercept) return;
                inIntercept = false;
                boolean self = selfDtap;
                selfDtap = false;
                if (!dtapSeen && !self) return;
                boolean seen = dtapSeen;
                dtapSeen = false;
                if (p.hasThrowable()) return;
                // 只有自己认出来的（抖音没识别到）才查落点：点在按钮之类小的可点控件上，是在连点按钮，不当双击
                if (!seen && p.thisObject instanceof View) {
                    View pv = (View) p.thisObject;
                    if (onButton(pv, tapX, tapY, pv.getWidth() * pv.getHeight())) return;
                }
                View target = commentTarget(p.thisObject);
                if (target == null) return; // 找不到评论按钮：放行，双击照常点赞（也不记 dtapAt，不跳过单击处理）
                long now = SystemClock.uptimeMillis();
                dtapAt = now;
                if (now - lastOpenAt >= REOPEN_GAP_MS) {
                    lastOpenAt = now;
                    open(target);
                }
                p.setResult(Boolean.TRUE);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 图文页的触摸入口：进来时标记「这一次触摸来自图文页」，出来时清掉（原方法抛异常也会清）。热路径：每次触摸两次静态写，不分配。 */
    private static final class PhotoMarkHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (DanmakuHook.cfg.dtapToComment) inPhotos = true;
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                inPhotos = false;
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 图文页里 LongPressLayout#dispatchTouchEvent：标记没置上（普通视频、直播等）一次静态读就返回。
     * 标记置上时按「第一下抬起 → 第二下按下」认双击；认到、并且这一页找得到评论按钮，就打开评论，把第二下的整个手势吞掉（返回 true，原方法不执行）。
     * 找不到评论按钮：放行，双击照常点赞。每次触摸只做几次比较；找页面和评论按钮、补点一次，只在认到双击时做一次。
     */
    private static final class PhotoDtapHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!inPhotos) return;
                MotionEvent e = (MotionEvent) p.args[0];
                int a = e.getActionMasked();
                if (phSwallow) {
                    if (a != MotionEvent.ACTION_DOWN) { // 第二下的 MOVE / UP / CANCEL：LongPressLayout 不收
                        p.setResult(Boolean.TRUE);
                        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) phSwallow = false;
                        return;
                    }
                    phSwallow = false; // 又来了一个按下 = 新的手势
                }
                if (!(p.thisObject instanceof View)) return;
                View v = (View) p.thisObject;
                switch (a) {
                    case MotionEvent.ACTION_DOWN: {
                        long t = e.getEventTime();
                        float x = e.getX(), y = e.getY();
                        if (phUpAt != 0 && t - phUpAt <= DTAP_GAP_MS) {
                            float sl = ViewConfiguration.get(v.getContext()).getScaledDoubleTapSlop();
                            float dx = x - phUpX, dy = y - phUpY;
                            if (dx * dx + dy * dy <= sl * sl) {
                                phUpAt = 0;
                                phDownAt = 0;
                                View target = commentTarget(pageOf(v));
                                if (target == null) return; // 找不到评论按钮：放行，双击照常点赞
                                long now = SystemClock.uptimeMillis();
                                if (now - lastOpenAt >= REOPEN_GAP_MS) {
                                    lastOpenAt = now;
                                    open(target);
                                }
                                phSwallow = true;
                                p.setResult(Boolean.TRUE);
                                v.performClick(); // 补回被吞掉的第二下本来会点的那一次：第一下已经点成暂停，第二下点回播放（见类注释）
                                return;
                            }
                        }
                        phUpAt = 0;
                        phDownAt = t;
                        phDownX = x;
                        phDownY = y;
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (phDownAt != 0) {
                            long t = e.getEventTime();
                            float sl = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                            float dx = e.getX() - phDownX, dy = e.getY() - phDownY;
                            if (t - phDownAt < ViewConfiguration.getLongPressTimeout() && dx * dx + dy * dy <= sl * sl) {
                                phUpAt = t;
                                phUpX = e.getX();
                                phUpY = e.getY();
                            }
                        }
                        phDownAt = 0;
                        break;
                    }
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_CANCEL:
                        phUpAt = 0;
                        phDownAt = 0;
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从 v 往上找这一页的 MonitorScrollFrameLayout（类名比较，最多 PAGE_UP 层）。只在认到双击时调用一次。 */
    private static View pageOf(View v) {
        ViewParent q = v.getParent();
        for (int i = 0; i < PAGE_UP && q instanceof View; i++) {
            if (q.getClass().getName().equals(MONITOR)) return (View) q;
            q = q.getParent();
        }
        return null;
    }

    /** 拦住双击之后的一小段时间里，抖音延迟执行的「单击处理」（切换暂停 / 播放）直接跳过。热路径：一次静态读、一次时间比较，不分配。 */
    private static final class ClickHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.dtapToComment) return;
                long t = dtapAt;
                if (t != 0 && SystemClock.uptimeMillis() - t < CLICK_SKIP_MS) p.setResult(null);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final Rect HIT = new Rect();

    /** (x, y) 落在 v 的子树里某个「小的、有点击监听」的控件上（面积不到 v 的一半，大的是整页的点击层）。只在自己认出双击时调用一次。 */
    private static boolean onButton(View v, float x, float y, int area) {
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        Rect r = HIT; // 主线程上用，递归前已经取完要用的值，共用一个不用每层 new
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            c.getHitRect(r);
            float cx = x + g.getScrollX() - r.left, cy = y + g.getScrollY() - r.top;
            if (cx < 0 || cy < 0 || cx >= r.width() || cy >= r.height()) continue;
            if (c.hasOnClickListeners() && (long) c.getWidth() * c.getHeight() * 2 < area) return true;
            if (onButton(c, cx, cy, area)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 打开评论

    /** 评论按钮的点击目标的资源 id 名（TextRec 里显示为 #comment_container）。 */
    private static final String TARGET_ID = "comment_container";

    /**
     * 被双击的这一页里的评论按钮的点击目标：page（MonitorScrollFrameLayout）的子树里 id 名为 comment_container 的控件。
     * 找不到 / 不可见 / 没有点击监听 → null（调用方放行双击）。只在双击时调用，不在每次触摸里跑。
     */
    private static View commentTarget(Object page) {
        if (!(page instanceof View)) return null;
        View pv = (View) page;
        int id = pv.getResources().getIdentifier(TARGET_ID, "id", pv.getContext().getPackageName());
        if (id == 0) return null;
        View t = pv.findViewById(id);
        return t != null && t.isShown() && t.hasOnClickListeners() ? t : null;
    }

    /** 等当前这次点击 / 手势处理完再点，避免在别人的回调里重入。 */
    private static void open(final View target) {
        target.post(new Runnable() {
            @Override
            public void run() {
                try {
                    target.performClick();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
