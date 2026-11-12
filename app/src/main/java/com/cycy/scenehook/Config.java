package com.cycy.scenehook;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块配置。模块跑在目标进程里，读不到自己 App 的私有目录，
 * 所以只能走共享存储：/sdcard/Download/scenehook/config.json。
 *
 * 配置文件是扁平的 JSON 对象：
 *   { "enabled": true, "mode": "trial", "expiry": "2099-12-31",
 *     "offline": true, "block_update": true, "assume_root": true,
 *     "force_pro": true, "trace_ipc": false, "debug": false }
 *
 * 这里没有用 org.json —— 只需要读几个标量字段，手写一个取值器就够，
 * 顺便把模块的依赖面压到零。
 * 带 3 秒 TTL，避免每次读状态都碰文件系统。
 */
final class Config {

    private static final String[] PATHS = {
            "/sdcard/Download/scenehook/config.json",
            "/storage/emulated/0/Download/scenehook/config.json",
            "/sdcard/scenehook.json",
            "/storage/emulated/0/scenehook.json",
    };

    private static final long TTL_MS = 3000L;

    /** 兜底到期时间：2099-12-31 23:59:59 */
    private static final long FALLBACK_EXPIRY_MS = 4_102_444_799_000L;

    boolean enabled = true;
    /** trial = 永远试用期；perpetual = 永久授权 */
    String mode = "trial";
    /** yyyy-MM-dd，既是展示的到期日，也是喂给授权判定的时间戳 */
    String expiry = "2099-12-31";

    /**
     * 真正断网：把目标进程里所有 http/https 的 URL.openConnection 打成 IOException。
     *
     * Scene 的全部联网都走 java.net.URL（HTTP 客户端 a.h60 和另外 4 个直接
     * openConnection 的类），所以在 URL 这一层设卡可以一次覆盖干净：
     *   · 授权同步 /release-activate2、账号接口
     *   · 云端配置 download-profile、cloud-scheduler
     *   · 版本检查 vi/lastversion.json、Scene9.json
     *   · Magisk 模块仓库 modules.json、广告跳过配置、pvp 上报
     * 守护进程自己的 HTTP 走 IPC 层拦截（见 LicenseHooks#hookDaemonIpc）。
     */
    boolean offline = true;

    /** 掐断云端授权同步 / 配置下载，保住本地已有 profile，也避免启动等超时 */
    boolean blockUpdate = true;

    /**
     * Scene 启动时 h0() 在 daemon 就绪前一律返回 "basic"，界面会先渲染成基础功能，
     * 退出重进才恢复。打开这个开关后，用上次成功的工作模式顶替掉这个 "basic"。
     */
    boolean assumeRoot = true;

    /**
     * 免费版标志位（meta-data free_version）强制为「专业版」。
     * 当前装的是 Pro 包，默认就是 false；这一项是为「万一是免费包」留的保险。
     */
    boolean forcePro = true;

    /** 打印每一次 app↔daemon IPC 的 API 名（去重），用来排查还有没有漏网的联网 API */
    boolean traceIpc = false;

    boolean debug = false;

    private static volatile Config cache;
    private static volatile long cachedAt = -1L;
    private static volatile String lastSource = "default";

    static Config current() {
        long now = SystemClock.elapsedRealtime();
        Config c = cache;
        if (c != null && now - cachedAt >= 0 && now - cachedAt < TTL_MS) {
            return c;
        }
        c = load();
        cache = c;
        cachedAt = now;
        return c;
    }

    static String source() {
        return lastSource;
    }

    private static Config load() {
        for (int i = 0; i < PATHS.length; i++) {
            String text = readText(PATHS[i]);
            if (text == null) continue;
            lastSource = PATHS[i];
            return parse(text);
        }
        lastSource = "default";
        return new Config();
    }

    private static Config parse(String text) {
        Config c = new Config();
        try {
            String v = extract(text, "enabled");
            if (v != null) c.enabled = "true".equalsIgnoreCase(v);

            v = extract(text, "mode");
            if (v != null && v.trim().length() > 0) c.mode = v.trim();

            v = extract(text, "expiry");
            if (v != null && v.trim().length() > 0) c.expiry = v.trim();

            v = extract(text, "offline");
            if (v != null) c.offline = "true".equalsIgnoreCase(v);

            v = extract(text, "block_update");
            if (v != null) c.blockUpdate = "true".equalsIgnoreCase(v);

            v = extract(text, "assume_root");
            if (v != null) c.assumeRoot = "true".equalsIgnoreCase(v);

            v = extract(text, "force_pro");
            if (v != null) c.forcePro = "true".equalsIgnoreCase(v);

            v = extract(text, "trace_ipc");
            if (v != null) c.traceIpc = "true".equalsIgnoreCase(v);

            v = extract(text, "debug");
            if (v != null) c.debug = "true".equalsIgnoreCase(v);
        } catch (Throwable ignored) {
            // 解析失败就用默认值，不影响主流程
        }
        return c;
    }

    /** 从扁平 JSON 里取一个标量值，支持字符串和裸字面量。取不到返回 null。 */
    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length() + 2);
        if (colon < 0) return null;

        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;

        if (json.charAt(p) == '"') {
            StringBuilder sb = new StringBuilder();
            int q = p + 1;
            while (q < json.length()) {
                char ch = json.charAt(q);
                if (ch == '\\' && q + 1 < json.length()) {
                    sb.append(json.charAt(q + 1));
                    q += 2;
                    continue;
                }
                if (ch == '"') break;
                sb.append(ch);
                q++;
            }
            return sb.toString();
        }

        int q = p;
        while (q < json.length()) {
            char ch = json.charAt(q);
            if (ch == ',' || ch == '}' || ch == '\n' || ch == '\r' || ch == '\t' || ch == ' ') break;
            q++;
        }
        return json.substring(p, q);
    }

    private static String readText(String path) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    boolean perpetual() {
        return "perpetual".equalsIgnoreCase(mode);
    }

    /** expiry 字符串 → 毫秒时间戳，取当天 23:59:59，避免差一天。 */
    long expiryMillis() {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            fmt.setLenient(false);
            Date d = fmt.parse(expiry);
            if (d == null) throw new IllegalStateException("parse null");
            return d.getTime() + 86_399_000L;
        } catch (Throwable t) {
            return FALLBACK_EXPIRY_MS;
        }
    }
}
