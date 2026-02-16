# Android CamSwap（VCAM Revise）

基于 Xposed 的虚拟摄像头模块，可将 Android 相机预览与拍照结果替换为自定义视频与图片。它面向使用 Camera1 / Camera2 API 的应用，无需改动目标应用即可模拟相机输入，适用于测试、演示和研究场景。

本项目主要包含：
- 通过 Xposed 在运行时 Hook 相机 API
- 使用解码后的视频帧替换预览帧
- 使用本地位图文件替换拍照结果
- 提供可视化配置与文件配置两种方式

## 目录

- [项目名称与描述](#项目名称与描述)
- [先决条件与环境要求](#先决条件与环境要求)
- [安装说明](#安装说明)
- [配置说明](#配置说明)
- [使用示例](#使用示例)
- [API 文档](#api-文档)
- [测试说明](#测试说明)
- [部署指南](#部署指南)
- [贡献指南](#贡献指南)
- [许可证信息](#许可证信息)
- [支持与联系方式](#支持与联系方式)
- [更新日志](#更新日志)
- [性能基准](#性能基准)
- [安全注意事项](#安全注意事项)
- [故障排查](#故障排查)

## 项目名称与描述

Android CamSwap 是一个 Xposed 模块，拦截 Android 相机调用并将真实相机输出替换为用户提供的媒体文件，主要解决：
- 无真实相机素材时的功能演示
- 相机流程的可重复测试
- QA 或研究场景下的固定输入模拟

核心功能包括 Camera1/Camera2 的拦截、视频解码为预览帧、以及静态图片替换拍照结果。

## 先决条件与环境要求

**系统要求**
- Android 5.0+ 设备（API 21+）
- Xposed 框架（LSPosed、EdXposed 或兼容实现）
- Windows / macOS / Linux 开发环境

**软件要求**
- Android Studio（建议使用最新稳定版）
- Android SDK Platform 36（compileSdk/targetSdk = 36）
- Android Build Tools 36.0.0（推荐）
- Gradle 8.5（wrapper 已配置）
- Android Gradle Plugin 8.3.2
- JDK 17+（项目使用 Java 17 编译）
- `gradle.properties` 中设置了 `org.gradle.java.home` 指向 JDK 20

**依赖**
- Xposed API 82（compileOnly）

**硬件要求**
- 支持 Xposed 的 Android 设备或模拟器

## 安装说明

### 开发环境安装

```bash
git clone https://github.com/zensu357/android_CamSwap.git
cd android_VCAM-Revise
```

创建 `local.properties` 并配置 SDK 路径：

```properties
sdk.dir=C:\\Android\\Sdk
```

构建 Debug APK：

```bash
./gradlew assembleDebug
```

### 用户安装

1. 在设备上安装 APK
2. 在 Xposed 框架中启用该模块
3. 重启设备或目标应用

## 配置说明

本项目使用本地存储目录与 JSON 配置文件，默认目录：

```
[内部存储]/DCIM/Camera1/
```

**必需媒体文件**
- `Cam.mp4`：预览替换视频
- `*.bmp`：用于拍照替换的位图文件

**配置文件**
- `vcam_config.json` 位于 `DCIM/Camera1/`
- 支持的键：
  - `disable_module`：是否禁用模块
  - `force_show_warning`：是否强制显示提醒
  - `play_video_sound`：是否播放视频声音
  - `force_private_dir`：是否强制使用私有目录
  - `disable_toast`：是否关闭 Toast 提示
  - `enable_random_play`：是否随机播放视频
  - `target_packages`：目标应用包名列表

示例：

```json
{
  "disable_module": false,
  "force_show_warning": false,
  "play_video_sound": true,
  "force_private_dir": false,
  "disable_toast": false,
  "enable_random_play": true,
  "target_packages": ["com.example.target"]
}
```

**私有目录模式**

当目标应用缺少存储权限时，模块会使用：

```
/Android/data/<package name>/files/Camera1/
```

**CI/CD Secrets**

GitHub Actions 发布签名 APK 需要配置：
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## 使用示例

### 使用自定义视频替换预览

```bash
# Windows PowerShell
Copy-Item .\demo\virtual.mp4 -Destination "C:\Users\<you>\DCIM\Camera1\Cam.mp4"
```

### 使用自定义图片替换拍照结果

```bash
Copy-Item .\demo\image_1.bmp -Destination "C:\Users\<you>\DCIM\Camera1\image_1.bmp"
```

### 推荐目录结构

```
DCIM/
  Camera1/
    Cam.mp4
    image_1.bmp
    image_2.bmp
    vcam_config.json
```

### 截图与动图

当前暂无截图或 GIF，可在后续补充并在此处引用。

## API 文档

本项目不提供网络 API、REST 接口或 CLI。对外集成方式为：
- Xposed 入口类：`com.example.camswap.HookMain`
- 配置文件：`DCIM/Camera1/vcam_config.json`
- 媒体输入：`Cam.mp4` 与 `*.bmp`

## 测试说明

默认提供 Gradle 测试任务：

```bash
./gradlew test
./gradlew connectedAndroidTest
```

若无设备，可使用已安装 Xposed 的模拟器。

## 部署指南

### 开发环境
- 构建：`./gradlew assembleDebug`
- 安装 Debug APK 并在 Xposed 中启用

### 预发布环境
- 构建 Release：`./gradlew assembleRelease`
- 本地配置签名或使用 CI 的 Secrets 生成签名 APK

### 生产环境
- 打 Tag 触发 GitHub Actions
- 工作流文件：`.github/workflows/android-build.yml`
- 需要的 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`

## 贡献指南

**代码风格**
- Java 17 兼容
- 使用 Android Studio 默认格式化
- 命名清晰、减少副作用

**分支策略**
- `main` 为稳定分支
- 功能开发请使用 `feature/<short-name>`

**PR 流程**
- 描述问题与解决方案
- 如涉及 UI 或行为变化请附截图或日志
- 确保本地测试通过

**Issue 反馈**
- 提供设备型号、Android 版本、Xposed 框架与版本
- 说明目标应用包名与复现步骤

## 许可证信息

MIT License

Copyright (c) 2021 w2016561536

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 支持与联系方式

- GitHub Issues：https://github.com/zensu357/android_CamSwap/issues
- 安全问题建议使用 GitHub Security Advisories（如仓库已启用）

## 更新日志

### 未发布（仓库快照）
- 日期：2026-02-10
- 变更：README 文档整理与扩展
- 破坏性变更：无

## 性能基准

暂无官方基准数据。建议关注：
- Camera1/Camera2 的预览替换帧率
- 解码到预览回调的端到端延迟
- 持续预览播放时的 CPU 占用

## 安全注意事项

- 请勿用于任何违法用途
- 避免在公共存储路径中存放敏感媒体
- 通过 `vcam_config.json` 限制目标应用范围
- 如发现安全问题，请通过 GitHub Security Advisories 私下报告

## 故障排查

**FAQ**

**Q：预览黑屏或相机启动失败。**  
A：确认 `virtual.mp4` 位于 `DCIM/Camera1/`，并确保没有重复的 `Camera1` 目录层级。

**Q：画面花屏或变形。**  
A：视频分辨率需与 Toast 提示的预览分辨率一致。

**Q：前置摄像头方向不正确。**  
A：建议对源视频进行水平翻转并旋转后再替换。

**Q：拍照替换无效。**  
A：确认 `DCIM/Camera1/` 中至少存在一个 `.bmp` 文件。