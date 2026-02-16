package com.example.camswap;

import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.File;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.example.camswap.utils.VideoManager;
import com.example.camswap.utils.PermissionHelper;
import com.example.camswap.utils.LogUtil;

public class Camera2Handler implements ICameraHandler {

    @Override
    public void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args[1] == null) {
                    return;
                }
                if (param.args[1].equals(HookMain.c2_state_cb)) {
                    return;
                }
                HookMain.c2_state_cb = (CameraDevice.StateCallback) param.args[1];
                HookMain.c2_state_callback = param.args[1].getClass();
                if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                    return;
                }
                VideoManager.updateVideoPath(true);
                File file = new File(VideoManager.getCurrentVideoPath());
                HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                if (!file.exists()) {
                    if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                        try {
                            PermissionHelper.showToast(HookMain.toast_content, "不存在替换视频\n" + lpparam.packageName + "\n当前路径：" + file.getAbsolutePath());
                        } catch (Exception ee) {
                            LogUtil.log("【CS】[toast]" + ee.toString());
                        }
                    }
                    return;
                }
                LogUtil.log("【CS】1位参数初始化相机，类：" + HookMain.c2_state_callback.toString());
                HookMain.is_first_hook_build = true;
                HookMain.process_camera2_init(HookMain.c2_state_callback);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args[2] == null) {
                        return;
                    }
                    if (param.args[2].equals(HookMain.c2_state_cb)) {
                        return;
                    }
                    HookMain.c2_state_cb = (CameraDevice.StateCallback) param.args[2];
                    if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                        return;
                    }
                    VideoManager.updateVideoPath(true);
                    File file = new File(VideoManager.getCurrentVideoPath());
                    HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                    if (!file.exists()) {
                        if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                            try {
                                PermissionHelper.showToast(HookMain.toast_content, "不存在替换视频\n" + lpparam.packageName + "当前路径：" + VideoManager.video_path);
                            } catch (Exception ee) {
                                LogUtil.log("【CS】[toast]" + ee.toString());
                            }
                        }
                        return;
                    }
                    HookMain.c2_state_callback = param.args[2].getClass();
                    LogUtil.log("【CS】2位参数初始化相机，类：" + HookMain.c2_state_callback.toString());
                    HookMain.is_first_hook_build = true;
                    HookMain.process_camera2_init(HookMain.c2_state_callback);
                }
            });
        }

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "addTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {

                if (param.args[0] == null) {
                    return;
                }
                if (param.thisObject == null) {
                    return;
                }
                File file = new File(VideoManager.getCurrentVideoPath());
                HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                if (!file.exists()) {
                    if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                        try {
                            PermissionHelper.showToast(HookMain.toast_content, "不存在替换视频\n" + lpparam.packageName + "\n当前路径：" + file.getAbsolutePath());
                        } catch (Exception ee) {
                            LogUtil.log("【CS】[toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (param.args[0].equals(HookMain.c2_virtual_surface)) {
                    return;
                }
                
                // Check disable module
                if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                    return;
                }
                
                String surfaceInfo = param.args[0].toString();
                if (surfaceInfo.contains("Surface(name=null)")) {
                    if (HookMain.c2_reader_Surfcae == null) {
                        HookMain.c2_reader_Surfcae = (Surface) param.args[0];
                    } else {
                        if ((!HookMain.c2_reader_Surfcae.equals(param.args[0])) && HookMain.c2_reader_Surfcae_1 == null) {
                            HookMain.c2_reader_Surfcae_1 = (Surface) param.args[0];
                        }
                    }
                } else {
                    if (HookMain.c2_preview_Surfcae == null) {
                        HookMain.c2_preview_Surfcae = (Surface) param.args[0];
                    } else {
                        if ((!HookMain.c2_preview_Surfcae.equals(param.args[0])) && HookMain.c2_preview_Surfcae_1 == null) {
                            HookMain.c2_preview_Surfcae_1 = (Surface) param.args[0];
                        }
                    }
                }
                LogUtil.log("【CS】添加目标：" + param.args[0].toString());
                param.args[0] = HookMain.c2_virtual_surface;

            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "removeTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {

                if (param.args[0] == null) {
                    return;
                }
                if (param.thisObject == null) {
                    return;
                }
                File file = new File(VideoManager.getCurrentVideoPath());
                HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                if (!file.exists()) {
                    if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                        try {
                            PermissionHelper.showToast(HookMain.toast_content, "不存在替换视频\n" + lpparam.packageName + "\n当前路径：" + file.getAbsolutePath());
                        } catch (Exception ee) {
                            LogUtil.log("【CS】[toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                    return;
                }
                Surface rm_surf = (Surface) param.args[0];
                if (rm_surf.equals(HookMain.c2_preview_Surfcae)) {
                    HookMain.c2_preview_Surfcae = null;
                }
                if (rm_surf.equals(HookMain.c2_preview_Surfcae_1)) {
                    HookMain.c2_preview_Surfcae_1 = null;
                }
                if (rm_surf.equals(HookMain.c2_reader_Surfcae_1)) {
                    HookMain.c2_reader_Surfcae_1 = null;
                }
                if (rm_surf.equals(HookMain.c2_reader_Surfcae)) {
                    HookMain.c2_reader_Surfcae = null;
                }

            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject == null) {
                    return;
                }
                if (param.thisObject.equals(HookMain.c2_builder)) {
                    return;
                }
                HookMain.c2_builder = (CaptureRequest.Builder) param.thisObject;
                File file = new File(VideoManager.getCurrentVideoPath());
                HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                if (!file.exists() && HookMain.need_to_show_toast) {
                    if (HookMain.toast_content != null) {
                        try {
                            PermissionHelper.showToast(HookMain.toast_content, "不存在替换视频\n" + lpparam.packageName + "\n当前路径：" + file.getAbsolutePath());
                        } catch (Exception ee) {
                            LogUtil.log("【CS】[toast]" + ee.toString());
                        }
                    }
                    return;
                }

                if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                    return;
                }
                LogUtil.log("【CS】开始build请求");
                HookMain.process_camera2_play();
            }
        });
    }
}
