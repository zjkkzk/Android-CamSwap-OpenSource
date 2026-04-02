package io.github.zensu357.camswap;

import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.LogUtil;

public class Api101ModuleMain extends XposedModule {
    private static final String TAG = "CamSwap-API101";

    private final Set<String> initializedPackages = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Api101Runtime.setModule(this);
        LogUtil.log("【CS】LibXposed 已加载 framework=" + getFrameworkName()
                + " version=" + getFrameworkVersion()
                + " api=" + getApiVersion()
                + " process=" + param.getProcessName()
                + " props=" + getFrameworkProperties());
    }

claude
@Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String hostPackage = resolveRuntimeHostPackage();
        if (hostPackage != null && !hostPackage.isEmpty() && !hostPackage.equals(param.getPackageName())) {
            LogUtil.log("【CS】跳过非宿主包 onPackageReady: " + param.getPackageName()
                    + " host=" + hostPackage);
            return;
        }

        // Deduplicate by package name only — LINE (and some other apps) trigger
        // onPackageReady multiple times with different ClassLoader instances,
        // causing all hooks to be installed twice and every interceptor to fire
        // double, which leads to resource corruption and native crashes.
        if (!initializedPackages.add(param.getPackageName())) {
            LogUtil.log("【CS】跳过重复 onPackageReady: " + param.getPackageName());
            return;
        }

        try {
            new HookMain().handleLoadPackage(new Api101PackageContext(this, param));
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook package failed: " + param.getPackageName(), t);
        }
    }

    private static String resolveRuntimeHostPackage() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object processName = activityThread.getMethod("currentProcessName").invoke(null);
            if (processName instanceof String) {
                String process = (String) processName;
                int separator = process.indexOf(':');
                return separator > 0 ? process.substring(0, separator) : process;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
