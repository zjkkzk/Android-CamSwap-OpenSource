# Android CamSwap 代码概览

这是一个基于 Xposed/LSPosed 框架的 Android 虚拟摄像头与麦克风模块项目（Android CamSwap）。项目的主要目标是拦截和替换系统相机与麦克风数据，使第三方应用（如微信、QQ 或系统相机）在调用摄像头/麦克风时，读取到用户预先设置的视频、图片或音频内容。

## 目录

- [核心功能与实现逻辑](#核心功能与实现逻辑)
- [代码结构与文件职责](#代码结构与文件职责)

## 核心功能与实现逻辑

### 1. 摄像头画面替换（Camera Replacement）

实现逻辑：利用 Xposed 拦截第三方应用的相机初始化与预览相关方法（如 Camera1 的 `setPreviewDisplay`、Camera2 的 `createCaptureSession` 等）。当应用请求物理相机的 `Surface` 画面时，模块将其重定向到自身解码链路，从而把本地视频/图片的帧数据写入应用提供的 `Surface`。

### 2. 麦克风音频替换（Audio/Microphone Replacement）

实现逻辑：Hook 安卓底层 `AudioRecord`（录音）与 `MediaRecorder` API。当应用想读取麦克风声音字节数组时，模块拦截读取过程，改为从用户指定的音频文件解码并提供自定义的 PCM 音频数据。

### 3. 视频/图片硬件解码与渲染

实现逻辑：使用 `MediaExtractor` 获取视频流，配合 `MediaCodec` 进行硬解码。为解决设备复杂的旋转与拉伸裁剪（如微信视频通话分辨率异常）问题，引入 OpenGL ES，确保虚拟视频帧可根据目标 `Surface` 尺寸要求正确旋转与渲染。

### 4. 跨进程配置通信（IPC Config Management）

实现逻辑：Hook 代码运行在被 Hook 的各个应用进程中，而设置界面（宿主 App）运行在另一进程。项目通过 `ContentProvider`（跨进程数据共享）配合动态注册的 `BroadcastReceiver`（广播接收器），实现宿主 App 可随时切换视频，并让各被 Hook 应用实时更新配置并无缝加载新素材。

### 5. 状态栏/通知栏控制（Notification Controls）

实现逻辑：启动前台服务，在系统下拉通知栏常驻控制面板，提供快捷控制功能（如切换上一段/下一段视频、手动旋转画面、暂停/播放等）。用户无需切回主 App，即可在被 Hook 应用内下拉通知栏调整画面。

### 6. 现代化且配置丰富的宿主控制端（Main UI）

实现逻辑：基于 Jetpack Compose 的宿主 UI，提供主页状态总览、多媒体文件（视频/图片/音频）管理页面，以及更细粒度的设置页面。

## 代码结构与文件职责

### 1. 核心 Hook 层（包根目录）

| 文件 | 说明 |
| --- | --- |
| `HookMain.java` | Xposed 模块入口类：识别当前运行的应用包名、加载用户配置、分发/注册 Hook 逻辑。 |
| `ICameraHandler.java` | 相机 Hook 处理器抽象接口：规范 Camera1/Camera2 的共同行为。 |
| `Camera1Handler.java` | 旧版 `android.hardware.Camera`（Camera1 API）Hook 实现。 |
| `Camera2Handler.java` | 新版 `android.hardware.camera2`（Camera2 API）Hook 实现：处理更复杂的 Session 与 Target 路由。 |
| `MicrophoneHandler.java` | Hook `AudioRecord`：拦截麦克风录音并注入假音频数据。 |

### 2. 多媒体数据处理与渲染

| 文件 | 说明 |
| --- | --- |
| `VideoToFrames.java` | 视频到帧解析器：使用 `MediaCodec` 硬解码视频流。 |
| `GLVideoRenderer.java` | OpenGL 渲染器：处理旋转（侧边/倒置）、缩放与黑屏等画面适配问题。 |
| `SurfaceRelay.java` | `Surface` 中继组件：桥接被 Hook 应用的承载 `Surface` 与模块解码输出 `Surface`。 |
| `utils/ImageToVideoConverter.java` | 图片转视频流工具：将静态图片模拟为连续帧并输送给摄像头链路。 |
| `BytePool.java` | 字节数组池：复用 `byte[]`，降低连续音视频流处理时的 GC 压力。 |

### 3. 配置、跨进程通信与服务

| 文件 | 说明 |
| --- | --- |
| `ConfigManager.java` | 配置管理核心类：提取、保存并维护各类开关与状态配置。 |
| `ConfigReceiver.java` | 广播接收器：接收来自通知栏或宿主 App 的动态指令（如切换视频、调整方向）。 |
| `VideoProvider.java` | `ContentProvider`：宿主 App 对外暴露的跨进程数据通道，用于共享文件路径与配置。 |
| `NotificationService.java` | 前台服务：渲染并管理通知栏快捷控制器（如上一段/下一段/旋转）。 |

### 4. 宿主端 UI（`ui` 目录与相关逻辑）

| 文件 | 说明 |
| --- | --- |
| `MainActivity.kt` | 宿主 App 的 UI 入口与初始化容器。 |
| `ui/HomeScreen.kt` | 主页界面。 |
| `ui/ManageScreen.kt` | 媒体管理界面：展示已导入素材并提供选择。 |
| `ui/SettingsScreen.kt` | 设置界面：渲染模式、画质缩放模式等深度配置。 |
| `ui/MainViewModel.kt` | 主界面状态管理（MVVM）。 |
| `ui/MediaManagerViewModel.kt` | 媒体管理界面状态管理（MVVM）。 |
| `ui/Navigation.kt` | Compose 导航与页面跳转逻辑。 |

### 5. 工具类（`utils` 目录）

| 文件 | 说明 |
| --- | --- |
| `utils/VideoManager.java` | 视频资源管理：枚举、存储与读取用户添加的视频/图片。 |
| `utils/AudioDataProvider.java` | 音频数据提供：将音频文件提取/解码为所需的 PCM 格式数据。 |
| `utils/ImageUtils.java` | 图片处理辅助工具。 |
| `utils/PermissionHelper.java` | 动态权限申请（存储、前台通知等）。 |
| `utils/LogUtil.java` | 统一日志封装：便于跨进程环境调试。 |
| `utils/LocaleHelper.kt` | 国际化与语言辅助。 |
