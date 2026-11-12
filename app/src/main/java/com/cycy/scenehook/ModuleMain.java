package com.cycy.scenehook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * SceneHook 入口（libxposed API 102）。
 *
 * 作用域：com.omarea.vtools（Scene 9.4.11）
 * 目标：恒处于「已激活的完整 Pro」，并且彻底不联网，变成一个纯本地工具。
 *
 * 装三组 hook：
 *   1. LicenseHooks —— 授权状态 + 守护进程 IPC（10 个）
 *   2. NetworkGate  —— java.net.URL#openConnection（2 个），把全部 HTTP 打成 IOException
 */
public final class ModuleMain extends XposedModule {

    private static final String TAG = "SceneHook";
    private static final String TARGET_PKG = "com.omarea.vtools";

    /** 一个进程只会装一次，防止重复 hook 串链 */
    private static volatile boolean sInstalled = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "模块加载: process=" + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + " " + getFrameworkVersion()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        if (sInstalled) return;
        sInstalled = true;

        log(Log.INFO, TAG, "目标包就绪: " + param.getPackageName()
                + ", classLoader=" + param.getClassLoader());

        Config c = Config.current();
        int net = 0;
        try {
            LicenseHooks.install(this, param.getClassLoader());
        } catch (Throwable t) {
            // 绝不能把目标 App 带崩
            log(Log.ERROR, TAG, "安装授权 hook 失败: " + t, t);
        }
        if (c.offline) {
            try {
                net = NetworkGate.install(this);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "安装断网 hook 失败: " + t, t);
            }
        } else {
            log(Log.INFO, TAG, "offline=false，跳过断网 hook");
        }

        log(Log.INFO, TAG, "断网 hook: " + net + "/2 生效, 闸门自检="
                + (c.offline ? NetworkGate.verify() : "skip"));
    }
}
