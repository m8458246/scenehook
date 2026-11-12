package com.cycy.scenehook;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;

/**
 * 断网闸门（v2 新增）。
 *
 * ===== 为什么卡在 java.net.URL 这一层 =====
 *
 * 反编译 Scene 9.4.11 后，全 App 的联网入口只有 6 处，全部最终落到
 * `new URL(str).openConnection()`：
 *
 *   a.h60          HTTP 客户端基类（b/m/k/i 全部内部调 new URL(...).openConnection()）
 *                  └ 6 个具体实现：
 *                       a.aq0  基础地址 + 连通性探测
 *                       a.zq0  /release-activate2、/account-*（授权与账号）
 *                       a.tp0  download.omarea.com/toolkit、/cloud-scheduler*（配置/守护进程）
 *                       a.jp0  /scene-magisk-*（模块仓库）
 *                       a.op0  /pvp/*（跑分上报）
 *                       a.iq0  云端配置同步
 *   a.pk0          直接 openConnection（Magisk 仓库 modules.json）
 *   a.kq0          直接 openConnection（vtools.oss.../vi/Scene9.json 版本信息）
 *   a.rs1          直接 openConnection（vi/lastversion.json 更新检查）
 *   a.ep0          直接 openConnection（auto-skip-config-v1.json 广告跳过配置）
 *
 * 所以只要在 URL#openConnection 上设卡，就把上面所有路径一次掐干净，
 * 而且**不碰本地回环**（守护进程走的是 java.nio SocketChannel，不是 URL），
 * 也不影响 WebView（WebView 走 native 网络栈，用户主动点链接仍能打开网页）。
 *
 * 只拦 http/https：file:、jar: 等协议原样放行，避免影响类加载。
 */
final class NetworkGate {

    private static final String TAG = "SceneHook";

    /** 自检用：抛出的异常里带这个标记，用来证明闸门真的生效了 */
    static final String REASON = "scenehook:offline";

    private final XposedModule module;
    private int installed;

    private NetworkGate(XposedModule module) {
        this.module = module;
    }

    static int install(XposedModule module) {
        NetworkGate gate = new NetworkGate(module);
        gate.run();
        return gate.installed;
    }

    private void run() {
        hookNoArg();
        hookWithProxy();
    }

    /** URL#openConnection() —— 覆盖全部已知调用点 */
    private void hookNoArg() {
        try {
            Method m = URL.class.getDeclaredMethod("openConnection");
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_net_open")
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        Config c = Config.current();
                        if (c.enabled && c.offline && self instanceof URL) {
                            URL url = (URL) self;
                            if (isHttp(url)) {
                                throw new IOException(REASON + " " + url.getHost());
                            }
                        }
                        return chain.proceed();
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: java.net.URL#openConnection()");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook URL#openConnection 失败: " + t);
        }
    }

    /** URL#openConnection(Proxy) —— 有些库会显式走代理，一起拦 */
    private void hookWithProxy() {
        try {
            Method m = URL.class.getDeclaredMethod("openConnection", Proxy.class);
            m.setAccessible(true);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("scenehook_net_open_proxy")
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        Config c = Config.current();
                        if (c.enabled && c.offline && self instanceof URL) {
                            URL url = (URL) self;
                            if (isHttp(url)) {
                                throw new IOException(REASON + " " + url.getHost());
                            }
                        }
                        return chain.proceed();
                    });
            installed++;
            module.log(Log.INFO, TAG, "hook OK: java.net.URL#openConnection(Proxy)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "hook URL#openConnection(Proxy) 失败: " + t);
        }
    }

    private static boolean isHttp(URL url) {
        try {
            String p = url.getProtocol();
            if (p == null) return false;
            p = p.toLowerCase(Locale.ROOT);
            return "http".equals(p) || "https".equals(p);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 自检：从模块自己的代码里发起一次 http 连接，看会不会被自己的 hook 拦下。
     *
     * 目标固定成 127.0.0.1:1 —— 万一 hook 没装上，也只是一次本地端口拒绝，
     * 不会真的产生外网流量。
     */
    static boolean verify() {
        try {
            URL url = new URL("http://127.0.0.1:1/scenehook-selftest");
            url.openConnection();
            return false; // 没被拦，说明闸门没生效
        } catch (IOException e) {
            String msg = String.valueOf(e.getMessage());
            return msg.contains(REASON);
        } catch (Throwable t) {
            return false;
        }
    }
}
