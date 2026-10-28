package com.danmaku.randcolor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocketFactory;

/**
 * 备份到坚果云（WebDAV）。只在模块自己的设置页里、用户点按钮时联网；不进抖音进程，没有任何定时 / 轮询。
 * <p>
 * 备份文件 = 一个 Properties 文本（纯 ASCII）：设置页配置的全部键原样放着，另加
 * meta.format / meta.version / meta.time / meta.pkg（备份时的包名列表）和 pos.*（浮窗位置，即 dyin_pos.properties 的全部键）。
 * 恢复一律按备份里的值算：备份里有的键原样还原，没有的键走 DanmakuConfig 读取时的默认值；不做任何「猜」和「近似替代」。
 * <p>
 * 不依赖任何第三方库：HttpURLConnection 不能发 PROPFIND，所以自己用 Socket 说最小的 HTTP/1.1。
 * 任何一步失败都直接抛出错误让界面显示，不重试、不换别的办法。
 */
final class Backup {
    static final String HOST = "dav.jianguoyun.com";
    static final String DEFAULT_FOLDER = "dyin";
    static final String FORMAT = "1";
    private static final Pattern NAME = Pattern.compile("dyin-\\d{8}-\\d{6}\\.properties");

    private Backup() {
    }

    /** 一份读回来的备份。posText = null 表示备份里没有位置。 */
    static final class Parsed {
        String cfgText = "";
        String pkgs = "";
        String posText;
        String version = "";
        String time = "";
    }

    // ------------------------------------------------------------ 备份文件的内容

    static String build(String cfgText, String pkgs, String posText, String version, String time) throws IOException {
        Properties out = new Properties();
        Properties cfg = new Properties();
        cfg.load(new StringReader(cfgText));
        for (String k : cfg.stringPropertyNames()) out.setProperty(k, cfg.getProperty(k));
        if (posText != null) {
            Properties pos = new Properties();
            pos.load(new StringReader(posText));
            for (String k : pos.stringPropertyNames()) out.setProperty("pos." + k, pos.getProperty(k));
        }
        out.setProperty("meta.format", FORMAT);
        out.setProperty("meta.version", version);
        out.setProperty("meta.time", time);
        out.setProperty("meta.pkg", pkgs);
        return store(out);
    }

    static Parsed parse(String text) throws IOException {
        Properties all = new Properties();
        all.load(new StringReader(text));
        String fmt = all.getProperty("meta.format");
        if (fmt == null) throw new IOException("这不是 dyin 的备份文件（没有 meta.format）");
        if (!FORMAT.equals(fmt.trim())) throw new IOException("备份文件的格式版本是 " + fmt.trim() + "，这个版本只认 " + FORMAT);
        Properties cfg = new Properties();
        Properties pos = new Properties();
        for (String k : all.stringPropertyNames()) {
            String v = all.getProperty(k);
            if (k.startsWith("meta.")) continue;
            if (k.startsWith("pos.")) pos.setProperty(k.substring(4), v);
            else cfg.setProperty(k, v);
        }
        Parsed b = new Parsed();
        b.cfgText = store(cfg);
        b.posText = pos.isEmpty() ? null : store(pos);
        b.pkgs = all.getProperty("meta.pkg", "");
        b.version = all.getProperty("meta.version", "");
        b.time = all.getProperty("meta.time", "");
        return b;
    }

    private static String store(Properties p) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        p.store(bo, null);
        return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------ 上传 / 列表 / 下载

    /** 坚果云里的备份文件夹（根目录下的一级文件夹，多台手机各用各的名字）→ WebDAV 路径 /dav/&lt;名字&gt;/。名字不合法就报错，不改写。 */
    static String dir(String folder) throws IOException {
        String f = folder == null ? "" : folder.trim();
        if (f.isEmpty()) throw new IOException("文件夹名不能为空");
        if (f.equals(".") || f.equals("..") || f.matches(".*[/\\\\:*?\"<>|\\p{Cntrl}].*")) throw new IOException("文件夹名不合法（不能含 / \\ : * ? \" < > | 和控制字符，不能是 . 或 ..）：" + f);
        try {
            return "/dav/" + java.net.URLEncoder.encode(f, "UTF-8").replace("+", "%20") + "/";
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** 上传一份新备份，再把超出 keep 份的旧备份删掉。返回给界面显示的结果（失败抛 IOException）。 */
    static String upload(Dav dav, String folder, String name, String text, int keep) throws IOException {
        final String DIR = dir(folder);
        Dav.Resp r = dav.req("MKCOL", DIR, null);
        if (r.code != 201 && r.code != 405) throw fail("创建备份文件夹 " + folder, r); // 405 = 文件夹已经在
        r = dav.req("PUT", DIR + name, text.getBytes(StandardCharsets.ISO_8859_1));
        if (r.code != 200 && r.code != 201 && r.code != 204) throw fail("上传", r);
        StringBuilder sb = new StringBuilder("✓ 已上传 ").append(name);
        List<String> names = list(dav, folder);
        int del = 0;
        for (int i = keep; i < names.size(); i++) {
            Dav.Resp d = dav.req("DELETE", DIR + names.get(i), null);
            if (d.code != 200 && d.code != 204 && d.code != 404) throw new IOException(sb + "\n✗ 清理旧备份 " + names.get(i) + " 失败：" + describe(d));
            del++;
        }
        if (del > 0) sb.append("\n已删除最旧的 ").append(del).append(" 份（只留最近 ").append(keep).append(" 份）");
        return sb.toString();
    }

    /** 坚果云里的备份文件名，新的在前。 */
    static List<String> list(Dav dav, String folder) throws IOException {
        final String DIR = dir(folder);
        Dav.Resp r = dav.req("PROPFIND", DIR, "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getlastmodified/></d:prop></d:propfind>".getBytes(StandardCharsets.UTF_8), "Depth: 1", "Content-Type: application/xml");
        if (r.code == 404) return new ArrayList<>(); // 还没备份过，文件夹都没有
        if (r.code != 207) throw fail("列出备份", r);
        TreeSet<String> set = new TreeSet<>();
        Matcher m = Pattern.compile("<[^>]*href[^>]*>([^<]+)</", Pattern.CASE_INSENSITIVE).matcher(r.text());
        while (m.find()) {
            String h = m.group(1).trim();
            int i = h.lastIndexOf('/');
            String n = i >= 0 ? h.substring(i + 1) : h;
            try {
                n = URLDecoder.decode(n, "UTF-8");
            } catch (Exception ignored) {
            }
            if (NAME.matcher(n).matches()) set.add(n);
        }
        ArrayList<String> out = new ArrayList<>(set);
        Collections.reverse(out); // 文件名里的时间从小到大，倒过来 = 新的在前
        return out;
    }

    static String download(Dav dav, String folder, String name) throws IOException {
        final String DIR = dir(folder);
        if (!NAME.matcher(name).matches()) throw new IOException("备份文件名不对：" + name);
        Dav.Resp r = dav.req("GET", DIR + name, null);
        if (r.code != 200) throw fail("下载", r);
        return new String(r.body, StandardCharsets.ISO_8859_1);
    }

    private static IOException fail(String what, Dav.Resp r) {
        return new IOException(what + "失败：" + describe(r));
    }

    static String describe(Dav.Resp r) {
        if (r.code == 401 || r.code == 403) return "HTTP " + r.code + "，账号或应用密码不对（要用坚果云「账户信息 → 安全选项 → 第三方应用管理」里生成的应用密码，不是登录密码）";
        return "HTTP " + r.code;
    }

    // ------------------------------------------------------------ 最小的 WebDAV 客户端

    static final class Dav {
        final String host;
        final int port;
        final boolean tls;
        final String auth;

        Dav(String host, int port, boolean tls, String user, String pass) {
            this.host = host;
            this.port = port;
            this.tls = tls;
            this.auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        }

        static Dav jianguoyun(String user, String pass) {
            return new Dav(HOST, 443, true, user, pass);
        }

        static final class Resp {
            int code;
            byte[] body = new byte[0];

            String text() {
                return new String(body, StandardCharsets.UTF_8);
            }
        }

        Resp req(String method, String path, byte[] body, String... headers) throws IOException {
            try (Socket s = tls ? SSLSocketFactory.getDefault().createSocket(host, port) : new Socket(host, port)) {
                s.setSoTimeout(30000);
                StringBuilder h = new StringBuilder();
                h.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                h.append("Host: ").append(host).append("\r\n");
                h.append("Authorization: ").append(auth).append("\r\n");
                h.append("User-Agent: dyin\r\n");
                h.append("Connection: close\r\n");
                for (String x : headers) h.append(x).append("\r\n");
                h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
                OutputStream os = s.getOutputStream();
                os.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (body != null) os.write(body);
                os.flush();
                return readResp(s.getInputStream());
            }
        }

        private static Resp readResp(InputStream in) throws IOException {
            String status = line(in);
            String[] sp = status.split(" ", 3);
            if (sp.length < 2 || !sp[0].startsWith("HTTP/")) throw new IOException("服务器回应读不懂：" + status);
            Resp r = new Resp();
            try {
                r.code = Integer.parseInt(sp[1].trim());
            } catch (NumberFormatException e) {
                throw new IOException("服务器回应读不懂：" + status);
            }
            long len = -1;
            boolean chunked = false;
            for (String l = line(in); !l.isEmpty(); l = line(in)) {
                int c = l.indexOf(':');
                if (c < 0) continue;
                String k = l.substring(0, c).trim().toLowerCase();
                String v = l.substring(c + 1).trim();
                if (k.equals("content-length")) {
                    try {
                        len = Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        throw new IOException("服务器回应读不懂：Content-Length " + v);
                    }
                } else if (k.equals("transfer-encoding") && v.toLowerCase().contains("chunked")) {
                    chunked = true;
                }
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (chunked) {
                for (; ; ) {
                    String cl = line(in);
                    int semi = cl.indexOf(';');
                    if (semi >= 0) cl = cl.substring(0, semi);
                    int n;
                    try {
                        n = Integer.parseInt(cl.trim(), 16);
                    } catch (NumberFormatException e) {
                        throw new IOException("服务器回应读不懂：分块长度 " + cl);
                    }
                    if (n == 0) break;
                    copy(in, bo, n);
                    line(in); // 块后的 CRLF
                }
            } else if (len >= 0) {
                copy(in, bo, len);
            } else {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            r.body = bo.toByteArray();
            return r;
        }

        private static void copy(InputStream in, ByteArrayOutputStream out, long n) throws IOException {
            byte[] buf = new byte[8192];
            while (n > 0) {
                int k = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (k < 0) throw new IOException("服务器回应被截断");
                out.write(buf, 0, k);
                n -= k;
            }
        }

        private static String line(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (; ; ) {
                int c = in.read();
                if (c < 0) {
                    if (sb.length() == 0) throw new IOException("服务器没有回应就断开了");
                    break;
                }
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
            }
            return sb.toString();
        }
    }
}
