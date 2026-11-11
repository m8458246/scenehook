package com.cycy.scenehook;

import android.content.Context;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Scene 授权 hook。
 *
 * 逆向结论（Scene 9.4.11 / com.omarea.vtools，versionCode 920260809）：
 *
 *   启动页 ActivityStartSplash$h.run()
 *     └─ new a.dp0(context).d()  → ActivatedStateModel
 *          └─ type = prefs["activate_v2_type"]        (a.t01.b())
 *          └─ text = c(prefs 里的授权串)               (a.dp0.c)
 *                └─ a.cp0.c() = a.q50.A(prefs["random_id2"])
 *                      └─ 把激活码发到服务端，返回 "success@<到期毫秒>" / "expired" / "invalid" ...
 *
 * 也就是说：到期与否是**服务端**判的，但请求里带的是本地时间（request_time = new Date()），
 * 所以把系统日期改到试用期内就能继续用 —— 这正是用户实测到的现象。
 *
 * 因此最小侵入的 hook 点就是那个"判决出口" a.q50.A(String)：
 * 直接返回 "success@<远期时间戳>"，整条链路的 activated 就恒为 true。
 * 另外再用 a.dp0.d() / a.t01.b() / getActivated() 兜底，防止任何分支把状态翻回去。
 */
final class LicenseHooks {

    private static final String TAG = "SceneHook";

    // 以下类名来自 9.4.11 的混淆结果，App 升级后可能变化；找不到时静默降级
    private static final String CLS_Q50 = "a.q50";                      // 授权判决（服务端交互）
    private static final String CLS_DP0 = "a.dp0";                      // 授权状态机
    private static final String CLS_T01 = "a.t01";                      // SharedPreferences 读写
    private static final String CLS_STATE = "com.omarea.model.ActivatedStateModel";

    private static final String PREF_TYPE = "activate_v2_type";
    private static final String RES_EXPIRY = "license_expiry";
    private static final String RES_TRIAL = "license_trial";
    private static final String RES_PERPETUAL = "license_perpetual";

    private final XposedModule module;
    private final ClassLoader cl;

    private Class<?> stateCls;
    private int installed;

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
        stateCls = find(CLS_STATE);

        hookVerdict(q50);
        hookStateGetter(dp0);
        hookStateParser(dp0);
        hookLicenseType(t01);
        hookActivatedFlag(stateCls);

        module.log(Log.INFO, TAG, "SceneHook 安装完成: " + installed + "/5 个 hook 生效, "
                + "配置来源=" + Config.source()
                + ", enabled=" + Config.current().enabled
                + ", mode=" + Config.current().mode
                + ", expiry=" + Config.current().expiry);
        selfCheck();
    }

    // ---------------------------------------------------------------- hooks

    /**
     * a.q50.A(String) → 授权判决。
     * 原实现是 O0("activate", code, 2500) 打到服务端，失败重试到 6 秒。
     * 这里直接短路，既让状态恒为成功，也顺手省掉了启动时的网络等待。
     */
    private void hookVerdict(Class<?> q50) {
        if (q50 == null) {
            module.log(Log.WARN, TAG, "找不到 " + CLS_Q50 + "，判决 hook 跳过");
            return;
        }
        try {
            Method m = q50.getDeclaredMethod("A", String.class);
            m.setAccessible(true);
            final String id = "scenehook_verdict";
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId(id)
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

    /** a.dp0.d() → 状态机出口，直接把结果改写成已激活。 */
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

    // -------------------------------------------------------------- helpers

    /** 把状态模型改写成"已激活"，顺便修好展示文案。 */
    private Object forceActivated(Object model, Object owner, Config c) {
        if (model == null) {
            model = newState();
            if (model == null) return null;
        }
        setBool(model, "setActivated", true);
        setBool(model, "setPermanent", c.perpetual());
        setStr(model, "setType", c.perpetual() ? "perpetual" : "trial");

        String typeName = resString(owner, c.perpetual() ? RES_PERPETUAL : RES_TRIAL);
        if (typeName != null) setStr(model, "setTypeName", typeName);

        String label = resString(owner, RES_EXPIRY);
        if (label != null) setStr(model, "setText", label + c.expiry);
        return model;
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
    private String resString(Object owner, String name) {
        Context ctx = findContext(owner);
        if (ctx == null) return null;
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

    /** 装完就自检，不用等用户点进设置页也能从 logcat 判断是否生效。 */
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
