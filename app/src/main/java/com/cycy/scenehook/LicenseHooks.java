package com.cycy.scenehook;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Scene 授权 + 守护进程 IPC hook（v2）。
 *
 * ===== 逆向结论（Scene 9.4.11 / com.omarea.vtools，versionCode 920260809）=====
 *
 * 1) 授权判定链路（全在 Java 层，没有任何本地"当前时间 vs 到期时间"比较）：
 *
 *      ActivityStartSplash$h.run()
 *        └─ new a.dp0(ctx).d()               → ActivatedStateModel
 *             ├─ type = a.t01.b()             prefs["global"]["activate_v2_type"]
 *             └─ text = a.dp0.c(label)
 *                  └─ a.cp0.toString()        → wm0.b() → 内存缓存 / cp0.c()
 *                       └─ a.q50.A(prefs["global"]["random_id2"])
 *                            └─ O0("activate", code, 2500)   ← socket 问 scene-daemon
 *                                 └─ 守护进程发 HTTP，返回 "success@<到期毫秒>"
 *                                    / "expired" / "invalid" / "not-you"
 *
 *    —— 这就是"改系统日期能续试用期"的原因：服务端拿客户端本地时间做比较。
 *
 * 2) 状态消费点只有 3 个：
 *      ActivityStartSplash#h.run()   启动分流（未激活 → 提示页）
 *      a.v12#Z1()                    「关于」页
 *      a.q02#b()                     激活码兑换结果
 *    另外 ActivityMain 每次打开都会拿 random_id2 再验一次，
 *    返回 expired/invalid/not-you 就弹 toast 并**清空授权 prefs**。
 *
 * 3) 守护进程（scene-daemon）是 APK 内置的 2.28MB 全加密 ELF（res/raw/daemon），
 *    不需要联网下载；app 用 res/raw/up.sh + busybox 以 root 起它，
 *    监听 127.0.0.1:14754，协议是「4 字节长度 + 密文」，密钥是
 *    MD5(本 App 签名) —— 所以**重打包/重签名会让守护进程直接连不上**，
 *    必须走运行时 hook，不能改包。
 *
 * 4) 全程没有任何反调试 / 反 root / 反 Xposed 逻辑（Java 与 native 都没有），
 *    唯一的"防篡改"就是上面那条签名派生密钥。断网之后也没有服务端可检测。
 *
 * ===== 因此本模块做四件事 =====
 *   A. 授权状态恒为「已激活的完整 Pro」（hook 判决出口，等价于把日期改回试用期内）
 *   B. 掐断联网（URL 闸门 + 守护进程 IPC 层双保险），变成纯本地工具
 *   C. 修复 Scene 自己的启动竞态：daemon 没就绪时 h0() 恒为 "basic"
 *   D. 免费版标志位兜底，避免误装免费包时功能被砍
 */
final class LicenseHooks {

    private static final String TAG = "SceneHook";

    // 9.4.11 的混淆名，App 升级后可能变化；找不到时静默降级
    private static final String CLS_Q50 = "a.q50";        // scene-daemon IPC 客户端
    private static final String CLS_DP0 = "a.dp0";        // 授权状态机
    private static final String CLS_T01 = "a.t01";        // SharedPreferences 读写
    private static final String CLS_ZQ0 = "a.zq0";        // /release-activate2 同步
    private static final String CLS_A30 = "a.a30";        // 免费版 / 专业版标志
    private static final String CLS_STATE = "com.omarea.model.ActivatedStateModel";

    private static final String PREF_TYPE = "activate_v2_type";
    private static final String RES_EXPIRY = "license_expiry";
    private static final String RES_TRIAL = "license_trial";
    private static final String RES_PERPETUAL = "license_perpetual";

    /** 守护进程里会真正发 HTTP 的两个 API；其余都是本地 shell / 读取 */
    private static final String API_ACTIVATE = "activate";
    private static final String API_DOWNLOAD_PROFILE = "download-profile";

    private final XposedModule module;
    private final ClassLoader cl;

    private Class<?> stateCls;
    private int installed;

    // 资源查找按名字 getIdentifier 比较贵，而文案是固定的，缓存一份就够
    private static volatile String sExpiryLabel;
    private static volatile String sTrialName;
    private static volatile String sPerpetualName;
    private static volatile boolean sResolved;

    // traceIpc 去重
    private static final Set<String> sSeenApis = Collections.synchronizedSet(new HashSet<String>());

    private LicenseHooks(XposedModule module, ClassLoader cl) {
        this.module = module;
        this.cl = cl;
    }

    static void install(XposedModule module, ClassLoader cl) {
        new LicenseHooks(module, cl).run();
    }

    private void run() {
        Class<?> q50 = find(CLS_Q50);
        Class<?> dp0 = find(CLS_DP0);
        Class<?> t01 = find(CLS_T01);
        Class<?> zq0 = find(CLS_ZQ0);
        Class<?> a30 = find(CLS_A30);
        stateCls = find(CLS_STATE);

        hookVerdict(q50);
        hookDaemonIpc(q50);
        hookStateGetter(dp0);
        hookStateParser(dp0);
        hookLicenseType(t01);
        hookActivatedFlag(stateCls);
        hookCloudSync(zq0);
        hookWorkingMode(q50);
        hookFreeVersion(a30);

        Config c = Config.current();
        module.log(Log.INFO, TAG, "SceneHook 安装完成: 授权类 " + installed + "/10 个 hook 生效, "
                + "配置来源=" + Config.source()
                + ", enabled=" + c.enabled
                + ", mode=" + c.mode
                + ", expiry=" + c.expiry
                + ", offline=" + c.offline
                + ", blockUpdate=" + c.blockUpdate
                + ", assumeRoot=" + c.assumeRoot);
        selfCheck();
    }

    // ---------------------------------------------------------------- hooks

    /**
     * a.q50.A(String) → 授权判决。
     * 原实现是 O0("activate", code, 2500)，失败重试到 6 秒。这里直接短路。
     * 覆盖 ActivityMain 每次启动的复验、cp0/r02 的缓存刷新、激活码兑换。
     */
    private void hookVerdict(Class<?> q50) {
        if (q50 == null) {
            module.log(Log.WARN, TAG, "找不到 " + CLS_Q50 + "，判决 hook 跳过");
            return;
        }
        try {
            Method m = q50.getDeclaredMethod("A", String.class);
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_verdict")
                    .intercept(chain -> {
                        Config c = Config.current();
                        if (!c.enabled) return chain.proceed();
                        String verdict = "success@" + c.expiryMillis();
                        if (c.debug) {
                            module.log(Log.INFO, TAG, "A(" + chain.getArg(0) + ") -> " + verdict);
                        }
                        return verdict;
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_Q50 + "#A(String)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + CLS_Q50 + "#A 失败: " + t);
        }
    }

    /**
     * a.q50.O0(String api, String content, Long timeout) → 所有 app↔daemon 调用的唯一出口。
     *
     * 这里是「既要劫持发出去的请求、也要管住回来的结果」最合适的落点：
     *   · activate          → 直接给 success，连守护进程都不用问（它本来会去发 HTTP）
     *   · download-profile  → 给一个合法的空 JSON，保住本地 profile，也不再等 15 秒超时
     *   · trace_ipc         → 打印每个 API 名（去重），用来确认没有漏网的联网 API
     */
    private void hookDaemonIpc(Class<?> q50) {
        if (q50 == null) return;
        try {
            Method m = q50.getDeclaredMethod("O0", String.class, String.class, Long.class);
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_ipc")
                    .intercept(chain -> {
                        Config c = Config.current();
                        if (!c.enabled) return chain.proceed();

                        Object rawApi = chain.getArg(0);
                        String api = (rawApi instanceof String) ? (String) rawApi : "";

                        if (API_ACTIVATE.equals(api)) {
                            String verdict = "success@" + c.expiryMillis();
                            if (c.debug) module.log(Log.INFO, TAG, "O0(activate) -> " + verdict);
                            return verdict;
                        }

                        if (c.blockUpdate && API_DOWNLOAD_PROFILE.equals(api)) {
                            if (c.debug) {
                                module.log(Log.INFO, TAG, "O0(download-profile) 已拦截，保留本地 profile");
                            }
                            // 必须是合法 JSON：上层会 new k30(result)，空串会抛异常刷日志
                            return "{}";
                        }

                        if (c.traceIpc && sSeenApis.add(api)) {
                            module.log(Log.INFO, TAG, "IPC api=" + api);
                        }

                        if (!c.debug) return chain.proceed();

                        long start = System.nanoTime();
                        try {
                            Object r = chain.proceed();
                            long ms = (System.nanoTime() - start) / 1_000_000L;
                            module.log(Log.INFO, TAG, "O0(" + api + ") " + ms + "ms -> " + brief(r));
                            return r;
                        } catch (Throwable t) {
                            long ms = (System.nanoTime() - start) / 1_000_000L;
                            module.log(Log.WARN, TAG, "O0(" + api + ") " + ms + "ms 抛异常: " + t);
                            throw t;
                        }
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_Q50 + "#O0(String,String,Long)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + CLS_Q50 + "#O0 失败: " + t);
        }
    }

    /** a.dp0.d() → 状态机出口，把结果改写成已激活。 */
    private void hookStateGetter(Class<?> dp0) {
        if (dp0 == null) {
            module.log(Log.WARN, TAG, "找不到 " + CLS_DP0 + "，状态 hook 跳过");
            return;
        }
        try {
            Method m = dp0.getDeclaredMethod("d");
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_state_d")
                    .intercept(chain -> {
                        Config c = Config.current();
                        if (!c.enabled) return chain.proceed();
                        Object model;
                        try {
                            model = chain.proceed();
                        } catch (Throwable t) {
                            // prefs 里没有激活码时原实现会 NPE，这里兜住，绝不让启动页崩
                            module.log(Log.WARN, TAG, "dp0.d() 原实现抛异常，改用兜底状态: " + t);
                            model = null;
                        }
                        return forceActivated(model, chain.getThisObject(), c);
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_DP0 + "#d()");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + CLS_DP0 + "#d 失败: " + t);
        }
    }

    /** a.dp0.c(String) → 解析授权串的入口，同样改写。 */
    private void hookStateParser(Class<?> dp0) {
        if (dp0 == null) return;
        try {
            Method m = dp0.getDeclaredMethod("c", String.class);
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_state_c")
                    .intercept(chain -> {
                        Config c = Config.current();
                        if (!c.enabled) return chain.proceed();
                        Object model;
                        try {
                            model = chain.proceed();
                        } catch (Throwable t) {
                            module.log(Log.WARN, TAG, "dp0.c() 原实现抛异常，改用兜底状态: " + t);
                            model = null;
                        }
                        return forceActivated(model, chain.getThisObject(), c);
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_DP0 + "#c(String)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + CLS_DP0 + "#c 失败: " + t);
        }
    }

    /**
     * a.t01.b() → prefs["activate_v2_type"]。
     * 空值 / expired / free 一律纠正成 trial，保证 d() 走试用分支、界面显示 Pro(Trial)。
     */
    private void hookLicenseType(Class<?> t01) {
        if (t01 == null) return;
        try {
            Method m = t01.getDeclaredMethod("b");
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_type")
                    .intercept(chain -> {
                        Object raw = chain.proceed();
                        Config c = Config.current();
                        if (!c.enabled) return raw;
                        if (c.perpetual()) return "perpetual";
                        String s = (raw instanceof String) ? (String) raw : "";
                        if (s.length() == 0
                                || "expired".equals(s)
                                || "free".equals(s)
                                || "unknown".equals(s)) {
                            return "trial";
                        }
                        return s;
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_T01 + "#b()  [" + PREF_TYPE + "]");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + CLS_T01 + "#b 失败: " + t);
        }
    }

    /** 最后一道保险：任何地方读 getActivated() 都拿到 true。 */
    private void hookActivatedFlag(Class<?> state) {
        if (state == null) return;
        try {
            Method m = state.getDeclaredMethod("getActivated");
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_activated")
                    .intercept(chain -> {
                        if (!Config.current().enabled) return chain.proceed();
                        return Boolean.TRUE;
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_STATE + "#getActivated()");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook getActivated 失败: " + t);
        }
    }

    /**
     * a.zq0.A() → 启动时那次 GET /release-activate2?t=<本机时间>。
     *
     * 原实现自己在 h60 返回 null 时也 return null，所以这里返回 null 是「网络失败」
     * 的既有语义，调用方（Scene$attachBaseContext$6 / a.jp1）都有判空和 try/catch。
     * 省掉一次必然失败的 HTTP，也避免服务端据此把本地授权状态改差。
     */
    private void hookCloudSync(Class<?> zq0) {
        if (zq0 == null) {
            module.log(Log.WARN, TAG, "找不到 " + CLS_ZQ0 + "，云端同步 hook 跳过");
            return;
        }
        try {
            Method m = zq0.getDeclaredMethod("A");
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_cloud_sync")
                    .intercept(chain -> {
                        Config c = Config.current();
                        if (!c.enabled || !c.blockUpdate) return chain.proceed();
                        if (c.debug) {
                            module.log(Log.INFO, TAG, "zq0.A() 已拦截（跳过 /release-activate2）");
                        }
                        return null;
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_ZQ0 + "#A()");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + CLS_ZQ0 + "#A 失败: " + t);
        }
    }

    /**
     * a.q50.h0() → 工作模式（"root" / "adb" / "basic"）。
     *
     * 原实现：
     *     public final String h0() { return Y() ? e : "basic"; }
     * 而 Y() 判断的是 daemon 是否就绪（f2606a == STATUS_RUNNING），
     * f2606a 在每个新进程里都从 STATUS_STOP 开始 —— 于是启动初期 h0() 恒为 "basic"，
     * 界面就先渲染成"基础功能"；等异步的 root 探测完成才变 "root"。
     * 有些界面（如 vx1）还会在构造时把 isBasic 缓存成字段，所以"退出重进"才能恢复。
     *
     * 这是 Scene 自身的启动竞态，与本模块无关。这里在"上次成功过 root"的前提下
     * 直接把 basic 顶替成上次的模式，让首次进入就是完整功能。
     */
    private void hookWorkingMode(Class<?> q50) {
        if (q50 == null) return;
        try {
            Method m = q50.getDeclaredMethod("h0");
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_mode")
                    .intercept(chain -> {
                        Object raw = chain.proceed();
                        Config c = Config.current();
                        if (!c.enabled || !c.assumeRoot) return raw;
                        String mode = (raw instanceof String) ? (String) raw : "basic";
                        if (!"basic".equals(mode)) return mode;
                        String saved = savedWorkingMode();
                        if (saved != null && saved.length() > 0 && !"basic".equals(saved)) {
                            if (c.debug) {
                                module.log(Log.INFO, TAG, "h0() basic -> " + saved + "（沿用上次的工作模式）");
                            }
                            return saved;
                        }
                        return mode;
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_Q50 + "#h0()");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + CLS_Q50 + "#h0 失败: " + t);
        }
    }

    /**
     * a.a30.b() / a.a30.c() → 免费版判断。
     *
     *   c() = !meta-data["free_version"]   → true 表示专业包
     *   b() = !c()                          → true 表示免费包
     *
     * 免费包会隐藏「Root 模式」入口（ActivityStartSplash#H），所以万一装的是免费包，
     * 这里兜一下就能拿到完整功能。Pro 包本来就不会走到这条分支，属于零成本保险。
     */
    private void hookFreeVersion(Class<?> a30) {
        if (a30 == null) return;
        try {
            Method isFree = a30.getDeclaredMethod("b");
            isFree.setAccessible(true);
            module.hook(isFree)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_is_free")
                    .intercept(chain -> {
                        Config c = Config.current();
                        if (!c.enabled || !c.forcePro) return chain.proceed();
                        return Boolean.FALSE;
                    });
            installed++;

            Method isPro = a30.getDeclaredMethod("c");
            isPro.setAccessible(true);
            module.hook(isPro)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_is_pro")
                    .intercept(chain -> {
                        Config c = Config.current();
                        if (!c.enabled || !c.forcePro) return chain.proceed();
                        return Boolean.TRUE;
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: " + CLS_A30 + "#b()/#c()  [free_version]");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook " + CLS_A30 + " 免费版标志失败: " + t);
        }
    }

    // -------------------------------------------------------------- helpers

    private static volatile Application sApp;
    private static volatile String sSavedMode;
    private static volatile long sSavedAt = -1L;

    /** 拿当前进程的 Application（模块跑在目标进程里，这条路是通的）。 */
    private static Application app() {
        if (sApp != null) return sApp;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object o = m.invoke(null);
            if (o instanceof Application) sApp = (Application) o;
        } catch (Throwable ignored) {
        }
        return sApp;
    }

    /**
     * 读 Scene 自己记下的 working_mode。
     * 存储位置：SharedPreferences("global")，key 来自 a.t01.f3059a = "working_mode"，
     * 只有成功进过 root 才会被写成 "root"（ActivityStartSplash 里写入）。
     */
    private static String savedWorkingMode() {
        long now = System.currentTimeMillis();
        if (sSavedAt >= 0 && now - sSavedAt < 3000L && sSavedMode != null) {
            return sSavedMode;
        }
        Application a = app();
        if (a == null) return null;
        try {
            String v = a.getSharedPreferences("global", 0).getString("working_mode", "basic");
            sSavedMode = v;
            sSavedAt = now;
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把状态模型改写成"已激活"，顺便修好展示文案。 */
    private Object forceActivated(Object model, Object owner, Config c) {
        if (model == null) {
            model = newState();
            if (model == null) return null;
        }
        setBool(model, "setActivated", true);
        setBool(model, "setPermanent", c.perpetual());
        setStr(model, "setType", c.perpetual() ? "perpetual" : "trial");

        Context ctx = findContext(owner);
        resolveStrings(ctx);

        String typeName = c.perpetual() ? sPerpetualName : sTrialName;
        if (typeName != null) setStr(model, "setTypeName", typeName);

        if (sExpiryLabel != null) setStr(model, "setText", sExpiryLabel + c.expiry);
        return model;
    }

    /** 资源文案只解析一次，之后走缓存 —— 避免每次状态刷新都做一次 getIdentifier。 */
    private void resolveStrings(Context ctx) {
        if (sResolved || ctx == null) return;
        synchronized (LicenseHooks.class) {
            if (sResolved) return;
            sExpiryLabel = resString(ctx, RES_EXPIRY);
            sTrialName = resString(ctx, RES_TRIAL);
            sPerpetualName = resString(ctx, RES_PERPETUAL);
            sResolved = true;
        }
    }

    private Object newState() {
        if (stateCls == null) return null;
        try {
            return stateCls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "构造 ActivatedStateModel 失败: " + t);
            return null;
        }
    }

    private void setBool(Object target, String name, boolean value) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, boolean.class);
            m.setAccessible(true);
            m.invoke(target, value);
        } catch (Throwable ignored) {
        }
    }

    private void setStr(Object target, String name, String value) {
        if (value == null) return;
        try {
            Method m = target.getClass().getDeclaredMethod(name, String.class);
            m.setAccessible(true);
            m.invoke(target, value);
        } catch (Throwable ignored) {
        }
    }

    /** 从 a.dp0 实例上把 Context 字段摸出来（混淆后有两个同名 a 字段，按类型筛）。 */
    private Context findContext(Object owner) {
        if (owner == null) return null;
        Class<?> c = owner.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v instanceof Context) return (Context) v;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 按名字取字符串资源，不写死 resource id，App 换版本也不会错位。 */
    private static String resString(Context ctx, String name) {
        try {
            int id = ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
            if (id == 0) return null;
            return ctx.getString(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private Class<?> find(String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String brief(Object o) {
        if (o == null) return "null";
        String s = String.valueOf(o);
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** 装完就自检，不用等用户点进设置页也能从日志判断是否生效。 */
    private void selfCheck() {
        Config c = Config.current();
        Object model = newState();
        if (model == null) {
            module.log(Log.WARN, TAG, "自检失败: 无法构造 " + CLS_STATE);
            return;
        }
        forceActivated(model, null, c);
        module.log(Log.INFO, TAG, "自检: type=" + get(model, "getType")
                + ", typeName=" + get(model, "getTypeName")
                + ", activated=" + get(model, "getActivated")
                + ", permanent=" + get(model, "getPermanent")
                + ", 到期=" + c.expiry + " (" + c.expiryMillis() + ")"
                + ", text=" + get(model, "getText"));
    }

    private String get(Object target, String name) {
        try {
            Method m = target.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return String.valueOf(m.invoke(target));
        } catch (Throwable t) {
            return "?";
        }
    }
}
