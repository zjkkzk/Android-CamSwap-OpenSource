package com.example.camswap;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public interface ICameraHandler {
    void init(XC_LoadPackage.LoadPackageParam lpparam);
}
