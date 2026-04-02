package io.github.zensu357.camswap;

import android.content.pm.ApplicationInfo;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class Api101PackageContext {
    public final XposedModule module;
    public final XposedModuleInterface.PackageReadyParam param;
    public final ClassLoader classLoader;
    public final String packageName;
    public final String processName;
    public final String hostPackageName;
    public final ApplicationInfo appInfo;
    public final boolean isFirstPackage;

    public Api101PackageContext(XposedModule module, XposedModuleInterface.PackageReadyParam param) {
        this.module = module;
        this.param = param;
        this.classLoader = param.getClassLoader();
        this.packageName = param.getPackageName();
        this.appInfo = param.getApplicationInfo();
        this.processName = this.appInfo != null ? this.appInfo.processName : this.packageName;
        this.hostPackageName = resolveHostPackageName(this.packageName, this.processName);
        this.isFirstPackage = param.isFirstPackage();
    }

    private static String resolveHostPackageName(String packageName, String processName) {
        String resolved = processName;
        if (resolved == null || resolved.isEmpty()) {
            resolved = packageName;
        }
        if (resolved == null || resolved.isEmpty()) {
            return resolved;
        }
        int separator = resolved.indexOf(':');
        return separator > 0 ? resolved.substring(0, separator) : resolved;
    }
}
