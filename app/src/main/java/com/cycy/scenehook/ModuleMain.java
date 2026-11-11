package com.cycy.scenehook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * SceneHook 入口（libxposed API 102）。
 *
 * 作用域：com.omarea.vtools（Scene 9.4.11）
 * 目标：让 App 恒处于「已激活的完整试用状态」，不再依赖改系统日期。
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

        try {
            LicenseHooks.install(this, param.getClassLoader());
        } catch (Throwable t) {
            // 绝不能把目标 App 带崩
            log(Log.ERROR, TAG, "安装 hook 失败: " + t, t);
        }
    }
}
