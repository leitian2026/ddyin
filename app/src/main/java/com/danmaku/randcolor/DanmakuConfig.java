package com.danmaku.randcolor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** 配置：模块界面写出，目标 App 里的 Hook 读取。两边共用同一个类。默认值 = 开启 + 鲜艳随机 + 按弹幕对象固定颜色。 */
public final class DanmakuConfig {
    public boolean enabled = true;
    /** 0 = 鲜艳（高饱和高亮度）  1 = 任意（含浅色/粉彩） */
    public int mode = 0;
    /** 双击视频时，不点赞、不出红心，改为打开评论。 */
    public boolean dtapToComment = false;
    /** 点底部「我」进个人主页时默认停在「喜欢」标签（最后一页）。见 ProfHook。 */
    public boolean profFav = false;
    /** 首页长按弹出的面板改成长按忽略、右侧按钮列里（头像上方）放一个按键，点按键才弹面板。见 LongPressHook。 */
    public boolean lpBtn = false;
    /** 视频时长一直显示（放在发布信息那一行的下面，没显示发布信息就放在文案下面）。 */
    public boolean timeAlways = false;
    /** 发布日期和地点一直显示（在文案下面单独一行）。 */
    public boolean pubAlways = false;
    /** 发布信息那一行的模板，见 PubFormat：{日期} {时间} {地点} {省} {市} 可以随意排顺序、夹文字，不限长度。 */
    public String pubFormat = PubFormat.DEFAULT;
    /** 时长文字 / 发布信息那一行的字号，单位 sp（在设置页调）。默认 = 以前写死的 20 / 16。 */
    public int timeSize = 20, pubSize = 16;
    /** 时长文字 / 发布信息那一行的颜色（0xAARRGGBB，不透明）；0 = 默认（时长白色，发布信息半透明白）。和视频文案的颜色互不影响。 */
    public int timeColor = 0, pubColor = 0;
    /** 视频文案的颜色（0xAARRGGBB，不透明）；0 = 不改，用抖音原来的颜色。话题、@ 这类抖音自己上色的文字不受影响。 */
    public int capColor = 0;
    /** 视频作者名的颜色（0xAARRGGBB，不透明）；0 = 不改，用抖音原来的颜色。作者名的灰色底跟「文字灰色底」的不透明度 lineBg 走（0 = 不画）。见 PubHook.NameHook。 */
    public int nameColor = 0;
    /** 直播间里显示直播间数据：开播时间、累计观看、在线人数（只在直播间页面里画，别处不显示；键名 liveTime 沿用旧名）。 */
    public boolean liveTime = false;
    /** 取哪个时间：0 = 开播时间（没有就用创建时间）  1 = 房间创建时间。 */
    public int liveSrc = 0;
    /** 直播间数据文字的字号（sp）、颜色（0xAARRGGBB 不透明；0 = 默认白色）。 */
    public int liveSize = 14, liveColor = 0;
    /** 位置：文字左上角在「直播页剩余空间」里的百分比，0 = 最左 / 最上，100 = 最右 / 最下（文字始终完整显示在页面里）。 */
    public int liveX = 3, liveY = 10;
    /** 位置调节模式：设置页点「去抖音里调整」时置 true；进直播间后抖音里出现位置浮窗，点「完成 / 取消」时由抖音里的浮窗改回 false 并写回文件。设置页自己存的配置里永远是 false。 */
    public boolean liveAdjust = false;
    /** 视频文案 / 视频时长 / 发布日期地点每一行字后面的灰色底的不透明度（0～100，%）；0 = 不画。见 LineBg。 */
    public int lineBg = 100;
    /** 文字底条上面的字的投影不透明度（0～100，%，黑色）；0 = 不加投影。65% ≈ 音量块字投影的 0xAA。见 LineBg.shadow。 */
    public int lineShadow = 65;
    /** 字的投影的模糊半径 / 向下偏移，单位 0.5dp（4 = 2dp）：半径 1～10（0.5～5dp），偏移 0～10（0～5dp）。默认 2dp / 1dp，和音量块的字一样。见 LineBg.shadow。 */
    public int lineShadowR = 4, lineShadowDy = 2;
    /** 首页顶部标签的两层液态玻璃（见 TabGlass）：整行一层（tabRow*）、每个标签各一层（tabItem*）；不透明度 0～100%，0 = 不画；底色 0xAARRGGBB，0 = 默认（偏白的中浅灰）。 */
    public int tabRowBg = 100, tabRowColor = 0, tabItemBg = 100, tabItemColor = 0;
    /** 标签文字的投影（黑色）：不透明度 %、半径 / 下移（0.5dp 一档），含义同 lineShadow*。 */
    public int tabShadow = 65, tabShadowR = 4, tabShadowDy = 2;
    /** 标签玻璃的大小：高 = 标签框高的百分比（20～100）；相邻两个标签玻璃之间的缝（0.5dp 一档，0～16dp，宽 = 标签框宽 − 缝）。 */
    public int tabItemH = 60, tabGap = 12;
    /** 整行玻璃比标签玻璃多出的：左右 0～40dp / 上下 0～20dp（0.5dp 一档）。 */
    public int tabRowPadX = 14, tabRowPadY = 12;
    /** 标签文字之间额外撑开的距离（0.5dp 一档，0～30dp，0 = 不改抖音原来的）：每个标签的文字左右外边距各加一半、标签框宽度加整个值。见 TabGlass.applySpread。 */
    public int tabSpread = 0;
    /** 标签行放在屏幕水平正中间（把标签行整体平移，让当前看得见的标签的中点对准窗口中点）；见 TabGlass.center。 */
    public boolean tabCenter = true;
    /** 底部导航（首页 / 朋友 / 消息 / 我）也加和顶部标签同一套液态玻璃（用 tabRow* / tabItem* / tabGap / tabItemH 这些设置）。见 BotGlass。 */
    public boolean botGlass = true;
    /** 右侧按钮列（点赞 / 评论 / 收藏 / 分享）的液态玻璃图标：iconPlate = 每个图标后面垫一块圆形玻璃（不透明度 iconBg，0～100）；iconFill = 图标本身加玻璃的明暗。见 IconGlass。 */
    public boolean iconPlate = true, iconFill = true;
    public int iconBg = 35;
    /** 底板大小：占控件短边的百分比（20～100，底板画在控件范围内，不能比控件大）。 */
    public int iconSize = 100;
    /** 玻璃图标大小：占底板基准（103px 那一圈）的百分比（20～200，大于 100 时可能被图标框裁掉一部分）。 */
    public int iconGlyph = 56;
    /** 首页顶部标签的液态玻璃（整行 + 每个标签）总开关；关 = 顶部不画玻璃（字的投影、撑开间距、居中、文字颜色不受影响）。底部导航另有 botGlass。 */
    public boolean tabGlass = true;
    /** 标签文字颜色（0xAARRGGBB，不透明）：未选中 / 选中的标签各一个；0 = 不改抖音原来的。见 TabGlass.colorAll。 */
    public int tabTextColor = 0, tabTextSelColor = 0;
    /** 文字底条的填充颜色（0xAARRGGBB，不透明）；0 = 默认（白色，和音量块的底一样；1.42.17 前是近黑 #101010）。不透明度仍由 lineBg 管，描边始终白色。见 LineBg。 */
    public int lineBgColor = 0;
    /** 模块自己决定颜色的字（弹幕随机色）的最低亮度（0～100，%，每档 5）：比这暗的颜色往白色提亮；0 = 不提亮。见 LineBg.legible。 */
    public int textLift = 30;
    /** 空白行默认高度 = 这么多 sp 的字高（blankH 为 -1 时用）。 */
    public static final int BLANK_DEFAULT_SP = 37;
    /** 文案下面的空白行高度：每 1 = 2 像素（-1 = 默认，高度等于 BLANK_DEFAULT_SP 的字高；0 = 不放；最大 200）。 */
    public int blankH = -1;
    /** 文案下方固定位置的开关；选哪个（1 = 发布地，2 = 时长，3 = 两个都选）：选中的文字放在文案下方（上顶留出的空位的顶上），不再用自己的自由位置；没有色条。 */
    public boolean barOn = true;
    public int barSel = 1;
    /** 发布日期地点、视频时长两个浮层在页面里的位置（左 / 上，dp，0 ～ 4000）；-1 = 默认。在抖音里用位置浮窗拖，见 VidPosPanel。 */
    public int pubX = -1, pubY = -1, timeX = -1, timeY = -1;
    /** 位置调节模式（发布地 / 时长）：设置页点「去抖音里调整」时置 true，抖音里浮窗点完成 / 取消时改回 false。设置页自己存的配置里永远是 false。 */
    public boolean vidAdjust = false;
    /** 直播间数据那块文字的模板，见 LiveFormat。 */
    public String liveFormat = LiveFormat.DEFAULT;
    /** 音量浮窗（见 VolPanel）：开关；大步长那一对 ＋／－ 按一下加减多少（1～100，默认 6；普通那一对固定 ±1）。 */
    public boolean volOn = false;
    public int volStep = 6;
    /** 音量浮窗横放（默认竖放）：横放时从左到右 = 当前音量、大步长 ＋、＋1、－1、大步长 －。 */
    public boolean volHoriz = false;
    /** 音量浮窗固定位置（默认开）：开着时浮窗拖不动；关掉才能按住当前音量拖动挪位置。 */
    public boolean volLock = true;
    /** 音量浮窗跟随显示（默认关）：开着时，只有这个窗口里有视频时长浮层，或直播间开播时间那块文字在显示，浮窗才显示；都没有就藏起来。 */
    public boolean volFollow = false;
    /** 让评论盖住音量条（默认开，1.44.1）：音量条照视频时长行的做法加进每一页的根（pub.bind 拿到的页面根），评论面板画在页面上面所以盖住它；关着就只用窗口最上层那一份。见 VolPanel。 */
    public boolean volUnderCmt = true;
    /** 音量浮窗里每一块单独的开关（默认都显示）：当前音量块、大步长一对（橙）、±1 一对（蓝）。三个都关就不加浮窗。 */
    public boolean volShowVal = true, volShowBig = true, volShowOne = true;
    /** 当前音量块放在加减键的哪一边：-1 = 默认（横放在左、竖放在上，和以前一样）、0 左、1 右、2 上、3 下。见 VolPanel。 */
    public int volValPos = -1;
    /** 音量浮窗大小（dp）：宽 = 条的粗细（竖放是宽、横放是高），长 = 条沿方向的总长；设置页里每按一下 ±1。默认 40 × 222 就是原来的大小。 */
    public static final int VOL_W_MIN = 20, VOL_W_MAX = 120, VOL_L_MIN = 120, VOL_L_MAX = 800;
    public int volW = 40, volL = 222;
    /** 音量浮窗位置（左 / 上，dp）；-1 = 默认（靠右偏上）。在抖音里直接拖，只存在 dyin_pos.properties。 */
    public int volX = -1, volY = -1;
    public static final int SIZE_MIN = 8, SIZE_MAX = 40;
    /** 首页顶部标签行里要隐藏的标签（"热点"、"同城"……），取消勾选后要重启抖音才恢复显示。 */
    public String[] hiddenTabs = new String[0];
    /** 无操作多少分钟后结束抖音进程；0 = 关闭。回到前台、触摸、按键都算一次操作。 */
    public int idleExitMin = 0;

    public String toText() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("mode", String.valueOf(mode));
            p.setProperty("dtapToComment", String.valueOf(dtapToComment));
            p.setProperty("profFav", String.valueOf(profFav));
            p.setProperty("lpBtn", String.valueOf(lpBtn));
            p.setProperty("timeAlways", String.valueOf(timeAlways));
            p.setProperty("pubAlways", String.valueOf(pubAlways));
            p.setProperty("timeSize", String.valueOf(timeSize));
            p.setProperty("pubSize", String.valueOf(pubSize));
            p.setProperty("pubFormat", pubFormat == null ? PubFormat.DEFAULT : pubFormat);
            p.setProperty("timeColor", String.valueOf(timeColor));
            p.setProperty("pubColor", String.valueOf(pubColor));
            p.setProperty("capColor", String.valueOf(capColor));
            p.setProperty("nameColor", String.valueOf(nameColor));
            p.setProperty("idleExitMin", String.valueOf(idleExitMin));
            p.setProperty("volOn", String.valueOf(volOn));
            p.setProperty("volStep", String.valueOf(volStep));
            p.setProperty("volHoriz", String.valueOf(volHoriz));
            p.setProperty("volLock", String.valueOf(volLock));
            p.setProperty("volFollow", String.valueOf(volFollow));
            p.setProperty("volUnderCmt", String.valueOf(volUnderCmt));
            p.setProperty("volShowVal", String.valueOf(volShowVal));
            p.setProperty("volShowBig", String.valueOf(volShowBig));
            p.setProperty("volShowOne", String.valueOf(volShowOne));
            p.setProperty("volValPos", String.valueOf(volValPos));
            p.setProperty("volW", String.valueOf(volW));
            p.setProperty("volL", String.valueOf(volL));
            p.setProperty("liveTime", String.valueOf(liveTime));
            p.setProperty("liveSrc", String.valueOf(liveSrc));
            p.setProperty("liveSize", String.valueOf(liveSize));
            p.setProperty("liveColor", String.valueOf(liveColor));
            p.setProperty("liveX", String.valueOf(liveX));
            p.setProperty("liveY", String.valueOf(liveY));
            p.setProperty("liveAdjust", String.valueOf(liveAdjust));
            p.setProperty("lineBg", String.valueOf(lineBg));
            p.setProperty("lineShadow", String.valueOf(lineShadow));
            p.setProperty("lineShadowR", String.valueOf(lineShadowR));
            p.setProperty("lineShadowDy", String.valueOf(lineShadowDy));
            p.setProperty("tabRowBg", String.valueOf(tabRowBg));
            p.setProperty("tabRowColor", String.valueOf(tabRowColor));
            p.setProperty("tabItemBg", String.valueOf(tabItemBg));
            p.setProperty("tabItemColor", String.valueOf(tabItemColor));
            p.setProperty("tabShadow", String.valueOf(tabShadow));
            p.setProperty("tabShadowR", String.valueOf(tabShadowR));
            p.setProperty("tabShadowDy", String.valueOf(tabShadowDy));
            p.setProperty("tabItemH", String.valueOf(tabItemH));
            p.setProperty("tabGap", String.valueOf(tabGap));
            p.setProperty("tabRowPadX", String.valueOf(tabRowPadX));
            p.setProperty("tabRowPadY", String.valueOf(tabRowPadY));
            p.setProperty("tabSpread", String.valueOf(tabSpread));
            p.setProperty("tabCenter", String.valueOf(tabCenter));
            p.setProperty("botGlass", String.valueOf(botGlass));
            p.setProperty("iconPlate", String.valueOf(iconPlate));
            p.setProperty("iconFill", String.valueOf(iconFill));
            p.setProperty("iconBg", String.valueOf(iconBg));
            p.setProperty("iconSize", String.valueOf(iconSize));
            p.setProperty("iconGlyph", String.valueOf(iconGlyph));
            p.setProperty("tabGlass", String.valueOf(tabGlass));
            p.setProperty("tabTextColor", String.valueOf(tabTextColor));
            p.setProperty("tabTextSelColor", String.valueOf(tabTextSelColor));
            p.setProperty("lineBgColor", String.valueOf(lineBgColor));
            p.setProperty("textLift", String.valueOf(textLift));
            p.setProperty("blankH", String.valueOf(blankH));
            p.setProperty("barOn", String.valueOf(barOn));
            p.setProperty("barSel", String.valueOf(barSel));
            p.setProperty("pubX", String.valueOf(pubX));
            p.setProperty("pubY", String.valueOf(pubY));
            p.setProperty("timeX", String.valueOf(timeX));
            p.setProperty("timeY", String.valueOf(timeY));
            p.setProperty("vidAdjust", String.valueOf(vidAdjust));
            p.setProperty("liveFormat", liveFormat == null ? LiveFormat.DEFAULT : liveFormat);
            StringBuilder sb = new StringBuilder();
            for (String s : hiddenTabs) sb.append(sb.length() == 0 ? "" : ",").append(s);
            p.setProperty("hiddenTabs", sb.toString());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, null);
            return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 位置（直播间开播时间的百分比、发布地 / 时长的偏移）单独存在抖音自己数据目录里的 dyin_pos.properties：
     * 位置浮窗只写它，设置页的「保存并应用」不碰它（以前位置跟着配置文件走，设置页里的旧值会把抖音里调好的位置冲掉）。
     * 有这个文件时，它的值盖过配置文件里的同名项。
     */
    String posText() {
        try {
            Properties p = new Properties();
            p.setProperty("liveX", String.valueOf(liveX));
            p.setProperty("liveY", String.valueOf(liveY));
            p.setProperty("pubX", String.valueOf(pubX));
            p.setProperty("pubY", String.valueOf(pubY));
            p.setProperty("timeX", String.valueOf(timeX));
            p.setProperty("timeY", String.valueOf(timeY));
            p.setProperty("volX", String.valueOf(volX));
            p.setProperty("volY", String.valueOf(volY));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, null);
            return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return "";
        }
    }

    static void applyPos(DanmakuConfig c, Properties p) {
        c.liveX = parsePct(p.getProperty("liveX", String.valueOf(c.liveX)), c.liveX);
        c.liveY = parsePct(p.getProperty("liveY", String.valueOf(c.liveY)), c.liveY);
        c.pubX = parsePos(p.getProperty("pubX", String.valueOf(c.pubX)));
        c.pubY = parsePos(p.getProperty("pubY", String.valueOf(c.pubY)));
        c.timeX = parsePos(p.getProperty("timeX", String.valueOf(c.timeX)));
        c.timeY = parsePos(p.getProperty("timeY", String.valueOf(c.timeY)));
        c.volX = parsePos(p.getProperty("volX", String.valueOf(c.volX)));
        c.volY = parsePos(p.getProperty("volY", String.valueOf(c.volY)));
    }

    static void applyPos(DanmakuConfig c, File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            applyPos(c, p);
        } catch (Throwable ignored) {
        }
    }

    /** 设置页用：把 root 读回的 dyin_pos.properties 文本盖到 c 上。 */
    static void applyPosText(DanmakuConfig c, String text) {
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            applyPos(c, p);
        } catch (Throwable ignored) {
        }
    }

    public static DanmakuConfig fromText(String text) {
        DanmakuConfig c = new DanmakuConfig();
        if (text == null || text.isEmpty()) return c;
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    public static DanmakuConfig read(File f) {
        DanmakuConfig c = new DanmakuConfig();
        try (FileInputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static void fill(DanmakuConfig c, Properties p) {
        c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
        c.dtapToComment = Boolean.parseBoolean(p.getProperty("dtapToComment", "false").trim());
        c.profFav = Boolean.parseBoolean(p.getProperty("profFav", "false").trim());
        c.lpBtn = Boolean.parseBoolean(p.getProperty("lpBtn", "false").trim());
        c.timeAlways = Boolean.parseBoolean(p.getProperty("timeAlways", "false").trim());
        c.pubAlways = Boolean.parseBoolean(p.getProperty("pubAlways", "false").trim());
        c.timeSize = parseSize(p.getProperty("timeSize", "20"), 20);
        c.pubSize = parseSize(p.getProperty("pubSize", "16"), 16);
        c.capColor = parseColor(p.getProperty("capColor", "0"));
        c.nameColor = parseColor(p.getProperty("nameColor", "0"));
        c.timeColor = parseColor(p.getProperty("timeColor", "0"));
        c.pubColor = parseColor(p.getProperty("pubColor", "0"));
        c.barOn = Boolean.parseBoolean(p.getProperty("barOn", "true").trim());
        c.barSel = Math.max(1, Math.min(3, parseIntOr(p.getProperty("barSel", "1"), 1)));
        String fmt = p.getProperty("pubFormat", PubFormat.DEFAULT);
        c.pubFormat = fmt == null || fmt.trim().isEmpty() ? PubFormat.DEFAULT : fmt;
        c.liveTime = Boolean.parseBoolean(p.getProperty("liveTime", "false").trim());
        c.liveSrc = parsePct(p.getProperty("liveSrc", "0"), 0) == 1 ? 1 : 0;
        c.liveSize = parseSize(p.getProperty("liveSize", "14"), 14);
        c.liveColor = parseColor(p.getProperty("liveColor", "0"));
        c.liveX = parsePct(p.getProperty("liveX", "3"), 3);
        c.liveY = parsePct(p.getProperty("liveY", "10"), 10);
        c.lineBg = parsePct(p.getProperty("lineBg", "100"), 100);
        c.lineShadow = parsePct(p.getProperty("lineShadow", "65"), 65);
        c.lineShadowR = Math.max(1, Math.min(10, parseIntOr(p.getProperty("lineShadowR", "4"), 4)));
        c.lineShadowDy = Math.max(0, Math.min(10, parseIntOr(p.getProperty("lineShadowDy", "2"), 2)));
        c.tabRowBg = parsePct(p.getProperty("tabRowBg", "100"), 100);
        c.tabRowColor = parseColor(p.getProperty("tabRowColor", "0"));
        c.tabItemBg = parsePct(p.getProperty("tabItemBg", "100"), 100);
        c.tabItemColor = parseColor(p.getProperty("tabItemColor", "0"));
        c.tabShadow = parsePct(p.getProperty("tabShadow", "65"), 65);
        c.tabShadowR = Math.max(1, Math.min(10, parseIntOr(p.getProperty("tabShadowR", "4"), 4)));
        c.tabShadowDy = Math.max(0, Math.min(10, parseIntOr(p.getProperty("tabShadowDy", "2"), 2)));
        c.tabItemH = Math.max(20, Math.min(100, parseIntOr(p.getProperty("tabItemH", "60"), 60)));
        c.tabGap = Math.max(0, Math.min(32, parseIntOr(p.getProperty("tabGap", "12"), 12)));
        c.tabRowPadX = Math.max(0, Math.min(80, parseIntOr(p.getProperty("tabRowPadX", "14"), 14)));
        c.tabRowPadY = Math.max(0, Math.min(40, parseIntOr(p.getProperty("tabRowPadY", "12"), 12)));
        c.tabSpread = Math.max(0, Math.min(60, parseIntOr(p.getProperty("tabSpread", "0"), 0)));
        c.tabCenter = Boolean.parseBoolean(p.getProperty("tabCenter", "true").trim());
        c.botGlass = Boolean.parseBoolean(p.getProperty("botGlass", "true").trim());
        c.iconPlate = Boolean.parseBoolean(p.getProperty("iconPlate", "true").trim());
        c.iconFill = Boolean.parseBoolean(p.getProperty("iconFill", "true").trim());
        c.iconBg = Math.max(0, Math.min(100, parseIntOr(p.getProperty("iconBg", "35"), 35)));
        c.iconSize = Math.max(20, Math.min(100, parseIntOr(p.getProperty("iconSize", "100"), 100)));
        c.iconGlyph = Math.max(20, Math.min(200, parseIntOr(p.getProperty("iconGlyph", "56"), 56)));
        c.tabGlass = Boolean.parseBoolean(p.getProperty("tabGlass", "true").trim());
        c.tabTextColor = parseColor(p.getProperty("tabTextColor", "0"));
        c.tabTextSelColor = parseColor(p.getProperty("tabTextSelColor", "0"));
        c.lineBgColor = parseColor(p.getProperty("lineBgColor", "0"));
        c.textLift = parsePct(p.getProperty("textLift", "30"), 30);
        c.blankH = Math.max(-1, Math.min(200, parseIntOr(p.getProperty("blankH", "-1"), -1)));
        c.pubX = parsePos(p.getProperty("pubX", "-1"));
        c.pubY = parsePos(p.getProperty("pubY", "-1"));
        c.timeX = parsePos(p.getProperty("timeX", "-1"));
        c.timeY = parsePos(p.getProperty("timeY", "-1"));
        c.volOn = Boolean.parseBoolean(p.getProperty("volOn", "false").trim());
        c.volStep = Math.max(1, Math.min(100, parseIntOr(p.getProperty("volStep", "6"), 6)));
        c.volHoriz = Boolean.parseBoolean(p.getProperty("volHoriz", "false").trim());
        c.volLock = Boolean.parseBoolean(p.getProperty("volLock", "true").trim());
        c.volFollow = Boolean.parseBoolean(p.getProperty("volFollow", "false").trim());
        c.volUnderCmt = Boolean.parseBoolean(p.getProperty("volUnderCmt", "true").trim());
        c.volShowVal = Boolean.parseBoolean(p.getProperty("volShowVal", "true").trim());
        c.volShowBig = Boolean.parseBoolean(p.getProperty("volShowBig", "true").trim());
        c.volShowOne = Boolean.parseBoolean(p.getProperty("volShowOne", "true").trim());
        c.volValPos = Math.max(-1, Math.min(3, parseIntOr(p.getProperty("volValPos", "-1"), -1)));
        c.volW = Math.max(VOL_W_MIN, Math.min(VOL_W_MAX, parseIntOr(p.getProperty("volW", "40"), 40)));
        c.volL = Math.max(VOL_L_MIN, Math.min(VOL_L_MAX, parseIntOr(p.getProperty("volL", "222"), 222)));
        c.vidAdjust = Boolean.parseBoolean(p.getProperty("vidAdjust", "false").trim());
        c.liveAdjust = Boolean.parseBoolean(p.getProperty("liveAdjust", "false").trim());
        String lf = p.getProperty("liveFormat", LiveFormat.DEFAULT);
        c.liveFormat = lf == null || lf.trim().isEmpty() || lf.equals(LiveFormat.OLD_DEFAULT) || lf.equals(LiveFormat.OLD_DEFAULT2) ? LiveFormat.DEFAULT : lf; // 存的是旧默认模板（1.40.7 以前 / 1.40.8）：换成新默认（带累计 / 在线 / 音浪）
        List<String> tabs = split(p.getProperty("hiddenTabs", ""));
        c.hiddenTabs = tabs.toArray(new String[0]);
        try {
            c.idleExitMin = Math.max(0, Math.min(1440, Integer.parseInt(p.getProperty("idleExitMin", "0").trim())));
        } catch (Throwable t) {
            c.idleExitMin = 0;
        }
        try {
            c.mode = Integer.parseInt(p.getProperty("mode", "0").trim()) == 1 ? 1 : 0;
        } catch (Throwable t) {
            c.mode = 0;
        }
    }

    private static int parseSize(String v, int def) {
        try {
            return Math.max(SIZE_MIN, Math.min(SIZE_MAX, Integer.parseInt(v.trim())));
        } catch (Throwable t) {
            return def;
        }
    }

    /** 位置偏移（dp）：-400～400；读不懂当 0。 */
    /** 浮层位置（dp）：-1 = 默认；读不懂当默认。 */
    private static int parsePos(String v) {
        try {
            return Math.max(-1, Math.min(4000, Integer.parseInt(v.trim())));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int parseIntOr(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 0～100 的整数（位置百分比）；读不懂用默认值。 */
    private static int parsePct(String v, int def) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(v.trim())));
        } catch (Throwable t) {
            return def;
        }
    }

    /** 颜色：0 = 不改 / 默认；其他一律补成不透明。读不懂当 0。 */
    private static int parseColor(String v) {
        try {
            int col = (int) Long.parseLong(v.trim());
            return col == 0 ? 0 : (col | 0xFF000000);
        } catch (Throwable t) {
            return 0;
        }
    }

    static List<String> split(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("[,\\n\\r]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
