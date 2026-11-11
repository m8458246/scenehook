package com.cycy.scenehook;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Scene 授权 hook（v1.1）。
 *
 * ===== 逆向结论（Scene 9.4.11 / com.omarea.vtools，versionCode 920260809）=====
 *
 * 1) 授权判定链路（全在 Java 层）：
 *      ActivityStartSplash$h.run()
 *        └─ new a.dp0(ctx).d()            → ActivatedStateModel
 *             ├─ type = a.t01.b()          prefs["activate_v2_type"]
 *             └─ text = a.dp0.c(label)     → a.cp0.c() → a.q50.A(prefs["random_id2"])
 *    a.q50.A(code) 本身不判断，它把 code 丢给 scene-daemon（127.0.0.1:14754），
 *    由 daemon 发 HTTP 到 omarea.com 拿 "success@<到期毫秒>" / "expired" / "invalid"。
 *    真正的"到期"比较发生在服务端，而请求时间由 daemon 用**本机时钟**生成
 *    —— 这就是改系统日期能续试用期的原因。
 *
 * 2) App 自身没有任何"按日期砍功能"的逻辑（全量 grep 过 currentTimeMillis），
 *    所以功能多寡不取决于 Java 层的时间判断。
 *
 * 3) 真正影响功能与速度的是网络：启动时会走
 *      · a.zq0.A() → GET /release-activate2?t=<本机时间>       （授权同步）
 *      · a.q50.F(branch) → O0("download-profile", …, 15000L)   （云端配置，超时 15 秒）
 *    日期一旦过期，这两个请求要么被服务端拒，要么长时间等超时 —— 表现为"变慢"，
 *    并且可能把本地那份完好的 profile/manifest 冲掉 —— 表现为"功能变少"。
 *
 * ===== 因此本模块做三件事 =====
 *   A. 授权状态恒为"已激活的完整试用"（5 个 hook，等价于把日期改回试用期内）
 *   B. 掐断上面两个联网刷新（可配置），保住本地已有的 profile，启动不再等超时
 *   C. daemon IPC 层统一兜底：O0("activate") 直接给成功，任何路径都绕不过
 */
final class LicenseHooks {

    private static final String TAG = "SceneHook";

    // 9.4.11 的混淆名，App 升级后可能变化；找不到时静默降级
    private static final String CLS_Q50 = "a.q50";        // scene-daemon IPC 客户端
    private static final String CLS_DP0 = "a.dp0";        // 授权状态机
    private static final String CLS_T01 = "a.t01";        // SharedPreferences 读写
    private static final String CLS_ZQ0 = "a.zq0";        // /release-activate2 同步
    private static final String CLS_STATE = "com.omarea.model.ActivatedStateModel";

    private static final String PREF_TYPE = "activate_v2_type";
    private static final String RES_EXPIRY = "license_expiry";
    private static final String RES_TRIAL = "license_trial";
    private static final String RES_PERPETUAL = "license_perpetual";

    private final XposedModule module;
    private final ClassLoader cl;

    private Class<?> stateCls;
    private int installed;

    // 资源查找按名字 getIdentifier 比较贵，而文案是固定的，缓存一份就够
    private static volatile String sExpiryLabel;
    private static volatile String sTrialName;
    private static volatile String sPerpetualName;
    private static volatile boolean sResolved;

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
        stateCls = find(CLS_STATE);

        hookVerdict(q50);
        hookDaemonIpc(q50);
        hookStateGetter(dp0);
        hookStateParser(dp0);
        hookLicenseType(t01);
        hookActivatedFlag(stateCls);
        hookCloudSync(zq0);
        hookWorkingMode(q50);

        Config c = Config.current();
        module.log(Log.INFO, TAG, "SceneHook 安装完成: " + installed + "/8 个 hook 生效, "
                + "配置来源=" + Config.source()
                + ", enabled=" + c.enabled
                + ", mode=" + c.mode
                + ", expiry=" + c.expiry
                + ", blockUpdate=" + c.blockUpdate);
        selfCheck();
    }

    // ---------------------------------------------------------------- hooks

    /**
     * a.q50.A(String) → 授权判决。
     * 原实现是 O0("activate", code, 2500)，失败重试到 6 秒。这里直接短路。
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
     * 这里是"既要劫持发出去的请求、也要管住回来的结果"最合适的落点：
     *   · activate          → 直接给 success，连 daemon 都不用问
     *   · download-profile  → 可配置拦掉，避免 15 秒超时
     *   · debug             → 打印每个 API 的耗时，用数据判断"慢在哪"
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

                        if ("activate".equals(api)) {
                            String verdict = "success@" + c.expiryMillis();
                            if (c.debug) module.log(Log.INFO, TAG, "O0(activate) -> " + verdict);
                            return verdict;
                        }

                        if (c.blockUpdate && "download-profile".equals(api)) {
                            module.log(Log.INFO, TAG, "O0(download-profile) 已拦截，保留本地 profile");
                            return "";
                        }

                        if (!c.debug) return chain.proceed();

                        long start = System.nanoTime();
                        try {
                            Object r = chain.proceed();
                            long ms = (System.nanoTime() - start) / 1_000_000L;
                            module.log(Log.INFO, TAG, "O0(" + api + ") " + ms + "ms -> "
                                    + brief(r));
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
     * a.zq0.A() → 启动时那次 GET /release-activate2?t=&lt;本机时间&gt;。
     * 直接返回 null 表示"没拿到数据"，走本地兜底；省掉一次必然失败的 HTTP，
     * 也避免服务端据此把本地授权状态改差。
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

    /** 读 Scene 自己记下的 working_mode（只有成功进过 root 才会是 "root"）。 */
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
        String text = null;
        try {
            Method g = model.getClass().getDeclaredMethod("getText");
            g.setAccessible(true);
            text = String.valueOf(g.invoke(model));
        } catch (Throwable ignored) {
        }
        module.log(Log.INFO, TAG, "自检: type=" + c.mode
                + ", activated=true, 到期=" + c.expiry
                + " (" + c.expiryMillis() + "), text=" + text);
    }
}
