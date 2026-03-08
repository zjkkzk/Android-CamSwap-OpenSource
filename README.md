# Android CamSwap (Open Source)
感谢项目 [android_virtual_cam](https://github.com/w2016561536/android_virtual_cam) 为本项目提供的灵感和代码基础。

Android CamSwap 是一个基于 Xposed 框架的虚拟摄像头模块。它能够拦截 Android 系统相机的预览和拍照请求，并将预览画面替换为用户指定的视频。

本项目采用现代化的 Android 开发技术栈（Kotlin, Jetpack Compose）重构，并引入了基于 ContentProvider 的跨进程数据传输机制，兼容高版本 Android (11+) 的文件权限隔离。

具体实现方式查看仓库中的 `code description.md` 文件（仅适用v2.0及前版本）。

## ✨ 主要功能

*   **全 API 支持**：同时支持 Camera1 (Camera) 和 Camera2 (CameraDevice) API。
*   **视频替换预览** 🎥：将相机预览画面无缝替换为指定的 MP4 视频。
*   **音频替换** 🎤：可选择播放自定义 MP3 音频文件，或与替换视频同步。
*   **通知栏实时控制**：
    *   ⏭ 切换到下一个 / 上一个视频。
    *   🔄 快速调整视频旋转方向（+90° / -90°）。
    *   🎲 随机播放模式。
*   **自动旋转处理**：读取视频元数据中的旋转角度，通过 OpenGL ES 正确渲染，无需手动处理。
*   **跨进程配置同步**：
    *   主路径：ContentProvider IPC（无需目标应用存储权限）。
    *   回退路径：直接文件读取 + Application 冷启动预热，彻底解决冷启动时配置未就绪问题。
*   **目标应用过滤**：可指定模块仅在特定应用中生效。
*   **现代化 UI**：基于 Material Design 3 和 Jetpack Compose 构建的管理界面。

## 📱 环境要求

*   **Android 版本**：Android 8.0 (API 26) 及以上（推荐 Android 11+）
*   **Root 权限**：必须
*   **Xposed 框架**：推荐使用 [LSPosed](https://github.com/LSPosed/LSPosed)（Zygisk / Riru 版本均可）

## 🚀 安装与使用

### 1. 安装模块
1. 下载最新版本的 Release APK。
2. 安装到你的 Android 设备。
3. 在 **LSPosed 管理器**中启用该模块。
4. **作用域勾选**：
    *   **强烈建议**：勾选「系统框架 (System Framework)」以获得最佳兼容性。
    *   或者：仅勾选你需要进行替换的**目标应用**（如相机、微信、抖音等）。
5. 重启手机（或重启目标应用的进程）。

### 2. 配置素材
1. 打开 **CamSwap** 应用，授予必要的文件读写权限。
2. 在「管理」页面导入你的 MP4 视频素材。
3. 在「设置」页面选择默认视频、配置音频替换、开启通知栏控制等选项。

### 3. 开始使用
打开任意调用相机的应用，预览画面将被替换为你选择的视频。

> **提示**：若视频画面方向不正确，可在通知栏使用旋转按钮微调，或在设置中手动填写旋转偏移角度。

## 📁 配置文件

模块的配置存储在以下路径（JSON 格式）：
```
/sdcard/DCIM/Camera1/cs_config.json
```

目标视频文件也应放置在该目录下。模块重启后会自动读取。

## 🏗️ 项目架构

Hook 层（Xposed 模块核心）采用模块化设计：

| 模块 | 文件 | 职责 |
|------|------|------|
| **入口** | `HookMain.java` | Xposed 入口 + 共享状态 + 委托分发 |
| **播放器管理** | `MediaPlayerManager.java` | 6 组 MediaPlayer/GLVideoRenderer/SurfaceRelay 的生命周期 |
| **配置监听** | `ConfigWatcher.java` | ContentObserver + FileObserver + BroadcastReceiver |
| **Camera2 拦截** | `Camera2SessionHook.java` | Camera2 session 创建拦截 + 虚拟 Surface 管理 |
| **Camera2 Hook** | `Camera2Handler.java` | Camera2 CaptureRequest.Builder + Surface 替换 |
| **Camera1 Hook** | `Camera1Handler.java` | Camera1 预览 Hook + onPreviewFrame 帧替换 |
| **音频 Hook** | `MicrophoneHandler.java` | AudioRecord 拦截 + MP3 音频替换 |


## 🛠️ 编译指南

### 准备环境
*   JDK 17+
*   Android SDK Platform 34

### 克隆仓库
```bash
git clone https://github.com/zensu357/Android-CamSwap-OpenSource.git
cd Android-CamSwap-OpenSource
```

### 配置签名（可选）
编译 Debug 包无需任何签名配置，直接运行：
```bash
./gradlew assembleDebug
```

如需编译已签名的 Release 包，在项目根目录创建 `local.properties`（勿提交到版本控制）并填写：
```properties
sdk.dir=/path/to/your/android/sdk
storeFile=../camswap_release.jks
storePassword=your_password
keyAlias=your_alias
keyPassword=your_key_password
```

若未配置签名，Release 构建会**自动回退到 Debug 密钥签名**，仍可正常安装。

```bash
# 编译 Release 包
./gradlew assembleRelease
```

编译产物位于 `app/build/outputs/apk/` 目录下。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库。
2. 创建特性分支：`git checkout -b feature/AmazingFeature`
3. 提交更改：`git commit -m 'Add some AmazingFeature'`
4. 推送分支：`git push origin feature/AmazingFeature`
5. 开启 Pull Request。

## ⚠️ 免责声明

本项目仅供**安全研究、软件测试和教育目的**使用。  
请勿将本项目用于任何非法用途（包括但不限于人脸识别绕过、身份欺诈等）。  
使用者需自行承担因使用本项目而产生的一切法律责任。

## ❤️ 支持

如果本项目对你有帮助，请点 ⭐ Star 支持！

![Star History](https://api.star-history.com/svg?repos=zensu357/Android-CamSwap-OpenSource&type=20260219)

## 📄 许可证

本项目基于 [GPL-3.0 license](LICENSE) 开源。
