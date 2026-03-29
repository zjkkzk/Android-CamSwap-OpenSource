# Android CamSwap 网络流虚拟摄像头实现方案

适用仓库：`io.github.zensu357.camswap`

基于当前仓库的真实代码结构，分析如何在本项目中实现"网络流作为虚拟摄像头输入"，哪些模块能直接复用、哪些地方必须重构，以及第一版建议做到什么边界。

---

## 1. 结论

当前仓库已具备完整的虚拟摄像头主链路：

1. 通过 `libxposed API 101` 注入目标 App 进程。
2. Hook `Camera1` / `Camera2` 的预览与采集输出。
3. 记录目标 App 的真实 `Surface` / `ImageReader Surface`。
4. 用播放器把替换画面渲染到这些 Surface。
5. 通过 `GLVideoRenderer` / `SurfaceRelay` 解决旋转和部分 Surface 兼容问题。
6. WhatsApp 等特殊 App 通过 `YuvCallbackPump` + `FakeYuvBridge` 生成 YUV 伪帧。

所以，这个项目要新增的不是"虚拟摄像头能力"，而是**"网络流媒体源能力"**。

最合理的落地方向：

- 先抽象媒体源模型。
- 再抽象播放器后端。
- 本地文件继续走当前 `MediaPlayer` 路线。
- 网络流走 **ExoPlayer (Media3)** 后端。
- 继续复用现有 `Camera1Handler` / `Camera2Handler` / `Camera2SessionHook` 的 Hook 体系。

---

## 2. 播放器方案选型：ExoPlayer (Media3)

### 2.1 为什么选 ExoPlayer 而不是 IjkPlayer / ffmpeg-kit / libVLC

| 维度 | ExoPlayer (Media3) | IjkPlayer | ffmpeg-kit | libVLC |
|------|-------------------|-----------|------------|--------|
| **RTSP** | 原生支持 | 支持 | 支持 | 支持 |
| **HLS** | 原生支持 | 支持 | 支持 | 支持 |
| **DASH** | 原生支持 | 部分 | 支持 | 支持 |
| **RTMP** | 扩展支持 | 支持 | 支持 | 支持 |
| **RTP** | RTSP 内支持 | 支持 | 支持 | 支持 |
| **HTTP/HTTPS** | 原生支持 | 支持 | 支持 | 支持 |
| **维护状态** | Google 活跃维护 | 2020 停更 | 活跃 | 活跃 |
| **Native so** | 无（纯 Java） | ~15MB/ABI | ~20MB/ABI | ~30MB/ABI |
| **ABI 冲突风险** | 无 | 高 | 高 | 高 |
| **minSdk** | 21 | 16 | 24 | 17 |

**决定性优势**：

1. **纯 Java 实现** — 不引入任何额外 native so。这在 Xposed 注入场景下至关重要：Hook 代码运行在目标 App 进程中，如果引入 native 库可能与目标进程已有 native 库冲突（so 加载路径、符号表冲突等）。ExoPlayer 完全避免了这个问题。
2. **体积极小** — 当前项目 ABI split 为 `arm64-v8a`、`armeabi-v7a`、`x86_64`，ExoPlayer 不会给每个 ABI 增加 15-30MB 体积。
3. **协议覆盖完整** — RTSP/HLS/DASH 原生支持，RTMP 通过官方扩展 `media3-exoplayer-rtmp` 支持。
4. **Google 维护** — API 稳定、持续更新、文档完善，不存在 IjkPlayer 停更后无人修 bug 的风险。

### 2.2 关于 RTP

独立裸 RTP（不经过 RTSP 协商）在实际使用中极其罕见。ExoPlayer 的 RTSP 实现内部使用 RTP 传输，覆盖了绝大多数真实场景。如果未来确实需要裸 RTP 接入，可以通过自定义 `MediaSource` 扩展，但第一版不需要。

### 2.3 Gradle 依赖

```groovy
// ExoPlayer (Media3) - 网络流播放
implementation "androidx.media3:media3-exoplayer:1.6.0"
implementation "androidx.media3:media3-exoplayer-rtsp:1.6.0"
implementation "androidx.media3:media3-exoplayer-hls:1.6.0"
implementation "androidx.media3:media3-exoplayer-dash:1.6.0"
implementation "androidx.media3:media3-exoplayer-rtmp:1.6.0"
```

> 注意：当前项目 `settings.gradle` 使用阿里云 Maven 镜像。Media3 包在 Google Maven 仓库（`maven.aliyun.com/repository/google` 已包含镜像），无需额外配置。

### 2.4 ProGuard / R8

当前 release 构建开启了 `minifyEnabled true` 和 `shrinkResources true`。ExoPlayer 的 AAR 自带 ProGuard 规则（`consumer-rules.pro`），通常不需要额外配置。但如果遇到反射相关的类被移除，可能需要添加：

```proguard
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
```

---

## 3. 当前项目真实情况

### 3.1 项目类型

这个仓库不是普通 Android 播放器 App，而是：

- 一个宿主 App（Jetpack Compose UI，compileSdk 36，minSdk 26）
- 一个基于 `libxposed API 101` 的 Xposed 模块

实际入口：

- `Api101ModuleMain.java` — Xposed 模块入口
- `HookMain.java` — Hook 逻辑主类

网络流能力必须接到当前 Hook 架构里，而不是再设计一套新的替换方案。

### 3.2 Camera2 已经具备很强的可复用基础

核心文件：

- `Camera2Handler.java` — Hook 入口（openCamera / addTarget / removeTarget / build）
- `Camera2SessionHook.java` — 会话改写、reader 跟踪、WhatsApp YUV 泵

当前已覆盖：

- `CaptureRequest.Builder.addTarget/removeTarget/build`
- 多个 `createCaptureSession` 变体（含 `SessionConfiguration` / `OutputConfiguration`）
- `ImageReader` 的 JPEG/YUV Surface 跟踪
- 延迟播放与 session callback hook
- WhatsApp 专用链路：`YuvCallbackPump` → `preRefreshYuvCache` → `acquireFakeWhatsAppYuvImage` → `FakeYuvBridge`
- 拍照替换（JPEG ImageReader acquire hook）

网络流接入时，不要退回到简化方案，应复用现有会话改写体系。

### 3.3 Camera1 也不能忽略

核心文件：`Camera1Handler.java`

当前 Camera1 已处理：`setPreviewTexture` / `setPreviewDisplay` / `startPreview` / `setPreviewCallback*` / `takePicture`

### 3.4 当前播放体系

核心文件：

- `MediaPlayerManager.java` — 管理多组 MediaPlayer + GLVideoRenderer + SurfaceRelay
- `GLVideoRenderer.java` — OpenGL ES 渲染器，支持旋转（vertex shader）、GL 截帧（`captureFrameWithRotation`）
- `SurfaceRelay.java` — GL 失败时的兜底 Surface 转发

现状：

- 主要使用 `android.media.MediaPlayer`
- 输入源是本地文件路径或 `ContentProvider` 返回的 `ParcelFileDescriptor`
- 渲染经过 GL 层旋转处理
- `MediaPlayerManager` 维护 6 个播放槽位（Camera1 holder/texture，Camera2 preview×2 / reader×2）
- 每个槽位有对应的 `GLVideoRenderer` 和 `SurfaceRelay`
- 通过 `mediaLock` 同步保证线程安全

这套设计对本地文件非常合适，但 `MediaPlayer` 不支持 RTSP/RTMP 等流协议。

### 3.5 当前配置热更新体系可以直接复用

核心文件：

- `ConfigManager.java` — JSON 配置持久化，Provider 优先 + 文件兜底
- `ConfigWatcher.java` — ContentObserver / FileObserver / BroadcastReceiver 三路监听
- `VideoProvider.java` — ContentProvider，跨进程提供配置和视频文件
- `ConfigReceiver.java` — 广播接收器

当前已支持：

- Provider 优先读取配置，`DCIM/Camera1/cs_config.json` 文件兜底
- 配置变化检测（`handleConfigUpdate` 区分媒体源变化 vs 旋转变化）
- `onMediaSourceChanged()` / `onRotationChanged()` 回调
- 广播触发切换视频 / 旋转 / 更新配置

网络流配置完全应复用这套机制。

### 3.6 本地文件语义深入多个模块

现在很多逻辑默认"当前媒体源一定是本地文件"：

- `VideoManager` — `getCurrentVideoPath()` / `getVideoPFD()` 均返回本地文件
- `HookGuards.shouldBypass()` — 依赖 `File.exists()` 判断是否有可用视频
- `VideoProvider.openFile()` — 返回本地视频 FD
- `Camera2SessionHook.captureFrameFromVideoFile()` — 用 `MediaMetadataRetriever` 做拍照兜底
- `AudioDataProvider` 的 `video_sync` 模式 — 依赖本地视频 FD 提取音轨

所以网络流接入的第一步是**先把媒体源从"路径字符串"升级为"结构化对象"**。

### 3.7 WhatsApp YUV 泵链路需要适配

当前 WhatsApp 视频通话的帧生成链路：

```
YuvCallbackPump.notifyRunnable (泵线程)
  → preRefreshYuvCache()          // 重计算：GL 截帧 + RGB→YUV
  → dispatchWhatsAppYuvCallback() // 轻量：投递到 WhatsApp handler
    → acquireFakeWhatsAppYuvImage()  // 只读缓存 + FakeYuvBridge 生成 Image
```

这条链路依赖 `GLVideoRenderer` 有活跃的视频画面。网络流模式下：

- ExoPlayer 输出到 SurfaceTexture → GLVideoRenderer 渲染 — 这部分与本地模式相同
- `captureFrameForYuv()` 通过 `captureFrameWithRotation()` 截取 GL 帧 — 无需改动
- 只要 ExoPlayer 正常输出画面，整条 YUV 链路无需修改

**风险点**：网络流断流时 ExoPlayer 停止输出新帧，`GLVideoRenderer` 的 SurfaceTexture 不会更新，GL 截帧会反复返回最后一帧。这在重连期间是可接受的行为（保持画面冻结），但需要在日志中标记。

---

## 4. 不该怎么做

### 4.1 不要把 URL 直接混入 `current_video_path`

如果直接让 `VideoManager.getCurrentVideoPath()` 有时返回本地路径、有时返回 URL，会立刻破坏：

- `HookGuards.shouldBypass()` — `new File(url).exists()` 永远返回 false
- `captureFrameFromVideoFile()` — `MediaMetadataRetriever.setDataSource(url)` 可能不工作
- `AudioDataProvider` — 无法从 URL 提取音轨 FD
- `VideoProvider.openFile()` — 无法为 URL 创建 FD

必须新增统一的媒体源描述类型。

### 4.2 不要一上来同时追求全量支持

第一版聚焦：**预览替换 + 配置热更新 + 自动重连 + 本地模式零回归**。

暂不追求：网络流下的拍照替换优化、`video_sync` 音频、WhatsApp YUV 帧质量优化。

### 4.3 "Manifest 加 INTERNET"不是全部答案

播放器跑在被 Hook 的**目标 App 进程**里，因此：

- 给宿主 APK 加 `INTERNET` 只保证宿主 UI 做流地址校验或网络探测时可联网
- 目标 App（如微信、Snapchat）本身通常都有 `INTERNET` 权限，所以实际上**大多数目标进程可以正常拉流**
- 不能 100% 保证所有目标 App 都能拉流（极少数 App 可能有网络策略限制）

第一版建议：

1. 宿主 App 补 `INTERNET` 权限
2. ExoPlayer 错误回调中记录详细日志（区分网络不可达 vs 协议不支持 vs 服务端拒绝）
3. 暂不做宿主代理转流

---

## 5. 推荐的项目内实现架构

### 5.1 新增 `MediaSourceDescriptor`

建议新增文件：`app/src/main/java/io/github/zensu357/camswap/MediaSourceDescriptor.java`

```java
public final class MediaSourceDescriptor {
    public enum Type {
        LOCAL_FILE,
        STREAM_URL
    }

    public final Type type;
    /** 本地模式：视频文件路径；流模式：null */
    public final String localPath;
    /** 流模式：流地址（rtsp/rtmp/http/https）；本地模式：null */
    public final String streamUrl;
    /** 本地模式：是否使用 Provider PFD */
    public final boolean useProviderPfd;

    // ---- 流模式参数 ----
    /** 流断开后是否自动重连 */
    public final boolean autoReconnect;
    /** 流不可用时是否回退到本地视频 */
    public final boolean enableLocalFallback;
    /** RTSP transport hint: auto / tcp / udp */
    public final String transportHint;
    /** 连接超时（毫秒） */
    public final long timeoutMs;

    private MediaSourceDescriptor(Builder builder) {
        this.type = builder.type;
        this.localPath = builder.localPath;
        this.streamUrl = builder.streamUrl;
        this.useProviderPfd = builder.useProviderPfd;
        this.autoReconnect = builder.autoReconnect;
        this.enableLocalFallback = builder.enableLocalFallback;
        this.transportHint = builder.transportHint;
        this.timeoutMs = builder.timeoutMs;
    }

    public boolean isStream() {
        return type == Type.STREAM_URL;
    }

    public boolean isValid() {
        if (type == Type.LOCAL_FILE) {
            return localPath != null && !localPath.isEmpty();
        } else {
            return streamUrl != null && !streamUrl.isEmpty();
        }
    }

    public static Builder localFile(String path) {
        return new Builder(Type.LOCAL_FILE).localPath(path);
    }

    public static Builder stream(String url) {
        return new Builder(Type.STREAM_URL).streamUrl(url);
    }

    public static class Builder {
        Type type;
        String localPath;
        String streamUrl;
        boolean useProviderPfd;
        boolean autoReconnect = true;
        boolean enableLocalFallback = true;
        String transportHint = "auto";
        long timeoutMs = 8000L;

        Builder(Type type) { this.type = type; }
        public Builder localPath(String v) { this.localPath = v; return this; }
        public Builder streamUrl(String v) { this.streamUrl = v; return this; }
        public Builder useProviderPfd(boolean v) { this.useProviderPfd = v; return this; }
        public Builder autoReconnect(boolean v) { this.autoReconnect = v; return this; }
        public Builder enableLocalFallback(boolean v) { this.enableLocalFallback = v; return this; }
        public Builder transportHint(String v) { this.transportHint = v; return this; }
        public Builder timeoutMs(long v) { this.timeoutMs = v; return this; }
        public MediaSourceDescriptor build() { return new MediaSourceDescriptor(this); }
    }
}
```

### 5.2 `ConfigManager` 新增流媒体配置项

新增常量键：

```java
public static final String KEY_MEDIA_SOURCE_TYPE = "media_source_type";       // "local" | "stream"
public static final String KEY_STREAM_URL = "stream_url";                     // rtsp://... 等
public static final String KEY_STREAM_AUTO_RECONNECT = "stream_auto_reconnect";
public static final String KEY_STREAM_LOCAL_FALLBACK = "stream_enable_local_fallback";
public static final String KEY_STREAM_TRANSPORT_HINT = "stream_transport_hint"; // "auto" | "tcp" | "udp"
public static final String KEY_STREAM_TIMEOUT_MS = "stream_timeout_ms";
```

推荐默认值：

```json
{
  "media_source_type": "local",
  "stream_url": "",
  "stream_auto_reconnect": true,
  "stream_enable_local_fallback": true,
  "stream_transport_hint": "auto",
  "stream_timeout_ms": 8000
}
```

这些字段必须贯通到：

- `ConfigManager.reload()` / `reloadFromProvider()` / `reloadFromFile()`
- `ConfigWatcher.handleConfigUpdate()` — 流配置变化视为媒体源变化触发 `onMediaSourceChanged()`
- `MainViewModel.kt`
- `SettingsScreen.kt`

### 5.3 `VideoManager` 升级为媒体源解析器

在 `VideoManager.java` 中新增：

```java
/** 获取当前媒体源描述，统一本地文件和网络流 */
public static MediaSourceDescriptor getCurrentMediaSource() { ... }

/** 当前是否处于流模式 */
public static boolean isStreamMode() { ... }

/** 是否有可用的媒体源（本地模式检查文件，流模式检查 URL） */
public static boolean hasUsableMediaSource() { ... }
```

策略：

- **local 模式**：继续沿用当前逻辑（`selected_video → Cam.mp4 → 任意视频`），私有目录 / Provider FD 保持不变
- **stream 模式**：如果 `stream_url` 非空，返回 `STREAM_URL` 描述；如果 URL 为空且 `enableLocalFallback=true`，回退到本地视频

现有的 `getCurrentVideoPath()` / `getVideoPFD()` 等方法保持不变，本地模式下的调用路径零修改。

### 5.4 `HookGuards` 改为基于媒体源判断

当前 `shouldBypass()` 只用 `File.exists()` 判断。需改为：

```java
public static boolean shouldBypass(String packageName, MediaSourceDescriptor source) {
    if (source == null || !source.isValid()) {
        logMissingMediaSource(packageName);
        return true;
    }
    // 本地模式：检查文件存在性
    if (!source.isStream()) {
        return !new File(source.localPath).exists();
    }
    // 流模式：URL 非空即放行（连接状态由播放器处理）
    return false;
}
```

需要同步修改所有调用点（`Camera2Handler`、`Camera1Handler`、`Camera2SessionHook`）。为了最小化改动，可保留旧的 `shouldBypass(packageName, File)` 签名作为内部实现，新增基于 `MediaSourceDescriptor` 的重载。

### 5.5 新增播放器后端接口

新增文件：`SurfacePlayerBackend.java`

```java
public interface SurfacePlayerBackend {
    /** 设置输出 Surface（GL SurfaceTexture 或直接 Surface） */
    void setOutputSurface(Surface surface);

    /** 打开媒体源 */
    void open(MediaSourceDescriptor source);

    /** 重新开始播放（从头或重连） */
    void restart();

    /** 停止播放 */
    void stop();

    /** 释放资源 */
    void release();

    /** 是否正在播放 */
    boolean isPlaying();

    /** 当前播放位置（毫秒）。流模式下可能返回 0 或实时时间戳 */
    long getCurrentPositionMs();

    /** 总时长（毫秒）。直播流返回 TIME_UNSET */
    long getDurationMs();

    /** 是否循环播放（本地文件模式需要，流模式忽略） */
    void setLooping(boolean looping);

    /** 设置音量 */
    void setVolume(float volume);

    /** 设置状态回调 */
    void setListener(Listener listener);

    interface Listener {
        /** 播放器已就绪，可以开始播放 */
        void onReady();
        /** 播放出错 */
        void onError(String message, Throwable cause);
        /** 流断开（仅流模式） */
        void onDisconnected();
        /** 重连成功（仅流模式） */
        void onReconnected();
        /** 播放结束（仅本地文件模式） */
        void onCompletion();
    }
}
```

#### `AndroidMediaPlayerBackend`

- 封装现有 `android.media.MediaPlayer` 逻辑
- 继续承载本地文件 / Provider FD 播放
- 从 `MediaPlayerManager.setupMediaPlayer()` 中提取核心逻辑

#### `ExoPlayerBackend`

- 专门处理网络流
- 承接 `http/https/rtsp/rtmp/hls/dash`
- 关键实现细节：

```java
public class ExoPlayerBackend implements SurfacePlayerBackend {
    private ExoPlayer player;
    private MediaSourceDescriptor currentSource;

    @Override
    public void open(MediaSourceDescriptor source) {
        this.currentSource = source;
        player = new ExoPlayer.Builder(context).build();
        player.setVideoSurface(outputSurface);

        MediaSource mediaSource = buildMediaSource(source);
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
    }

    private MediaSource buildMediaSource(MediaSourceDescriptor source) {
        Uri uri = Uri.parse(source.streamUrl);
        String scheme = uri.getScheme();

        if ("rtsp".equalsIgnoreCase(scheme)) {
            // RTSP：支持 TCP/UDP transport hint
            RtspMediaSource.Factory factory = new RtspMediaSource.Factory();
            if ("tcp".equals(source.transportHint)) {
                factory.setForceUseRtpTcp(true);
            }
            factory.setTimeoutMs(source.timeoutMs);
            return factory.createMediaSource(MediaItem.fromUri(uri));
        }
        if ("rtmp".equalsIgnoreCase(scheme)) {
            // RTMP：需要 media3-exoplayer-rtmp 扩展
            return new RtmpMediaSource.Factory()
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        // HTTP/HTTPS — 自动检测 HLS (.m3u8) / DASH (.mpd) / 普通流
        String path = uri.getPath();
        if (path != null && path.endsWith(".m3u8")) {
            return new HlsMediaSource.Factory(new DefaultHttpDataSource.Factory())
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        if (path != null && path.endsWith(".mpd")) {
            return new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        // 兜底：ProgressiveMediaSource（普通 HTTP 视频文件）
        return new ProgressiveMediaSource.Factory(new DefaultHttpDataSource.Factory())
                .createMediaSource(MediaItem.fromUri(uri));
    }

    // 自动重连逻辑
    private void setupReconnect() {
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                if (currentSource.autoReconnect) {
                    // 延迟重连，避免频繁重试
                    handler.postDelayed(() -> {
                        if (player != null) {
                            player.prepare();
                            player.play();
                        }
                    }, 3000L);
                }
            }
        });
    }
}
```

### 5.6 `MediaPlayerManager` 从"播放器集合"升级为"播放会话管理器"

当前它直接持有多组 `MediaPlayer`。逐步改成：

- 每个播放槽位持有一个 `SurfacePlayerBackend`
- 根据 `MediaSourceDescriptor` 决定实例化本地后端还是流后端
- `setupMediaPlayer()` 改为调用后端工厂

槽位结构保持现状：

| 槽位 | Camera1 holder | Camera1 texture | Camera2 preview ×2 | Camera2 reader ×2 |
|------|---------------|----------------|--------------------|--------------------|
| 后端 | `SurfacePlayerBackend` | `SurfacePlayerBackend` | `SurfacePlayerBackend` | `SurfacePlayerBackend` |
| 渲染 | `GLVideoRenderer` | `GLVideoRenderer` | `GLVideoRenderer` | `GLVideoRenderer` |
| 兜底 | `SurfaceRelay` | — | `SurfaceRelay` | `SurfaceRelay` |

关键：流模式下所有槽位共享同一个 `ExoPlayerBackend` 实例是不行的（ExoPlayer 只能输出到一个 Surface）。但可以让 ExoPlayer 输出到主预览 Surface，其他槽位通过 `SurfaceTexture` 共享纹理。这与当前本地模式下多个 `MediaPlayer` 各自独立播放的方式不同，需要仔细设计。

**推荐方案**：流模式下只创建一个 `ExoPlayerBackend`，输出到 `c2_renderer`（主预览渲染器）。`c2_reader_renderer` 等从同一个 `SurfaceTexture` 获取纹理，或者通过 `GLVideoRenderer` 的 GL 截帧获取画面内容。

### 5.7 Hook 层尽量少动，只改播放接入点

#### Camera2

保留现有 `Camera2Handler` hook 入口和 `Camera2SessionHook` 会话改写体系。

主要改动：

- `startPlayback()` — 改为使用播放器后端工厂
- `captureFrameFromVideoFile()` — 流模式下跳过 `MediaMetadataRetriever`，仅依赖 GL 截帧
- `HookGuards` 调用点 — 适配新的 `MediaSourceDescriptor` 参数

WhatsApp YUV 链路无需改动（只要 GLVideoRenderer 有画面就能截帧）。

#### Camera1

保留现有 Hook 路径，重点改：

- `prepareHolderPreviewPlayer()`
- `prepareTexturePreviewPlayer()`

它们现在直接创建 `MediaPlayer`，后续应统一委托给后端工厂。

### 5.8 UI 放在 `SettingsScreen`

网络流 URL 是播放源配置，放在 `SettingsScreen.kt`：

- 源类型切换：本地文件 / 网络流（SegmentedButton 或 RadioButton）
- 流地址输入框（`OutlinedTextField`，仅流模式可见）
- 自动重连开关
- 本地兜底开关
- Transport Hint 选择（auto / tcp / udp，仅 RTSP 时有意义）
- 超时设置（可选，高级选项）

`ManageScreen.kt` 继续只负责本地媒体导入和选择。

同步更新：`MainViewModel.kt` / `strings.xml`

---

## 6. 需要提前说明的风险

### 6.1 ExoPlayer 包体积

虽然 ExoPlayer 无 native so，但 Java/Kotlin 代码本身也有体积：

- 核心 `media3-exoplayer`：约 1.5MB（R8 优化后）
- 各协议扩展：各约 100-300KB
- 总增量约 2-3MB（R8 后）

当前项目已开启 `minifyEnabled true`，ExoPlayer 未使用的类会被 R8 移除，实际增量更小。

### 6.2 网络流下的拍照替换

当前 `Camera2SessionHook` 拍照兜底依赖 `MediaMetadataRetriever(filePath)`。

流模式下改为：

1. 优先从 `GLVideoRenderer.captureFrameWithRotation()` 截取当前帧（与 YUV 截帧共用逻辑）
2. 失败时返回最近一帧缓存
3. 再失败时返回黑帧

实际上当前代码已经优先走 GL 截帧路径（`captureFrameInternal`），`MediaMetadataRetriever` 只是兜底。流模式下 GL 截帧正常工作的概率很高。

### 6.3 `video_sync` 麦克风模式第一版降级

当前 `MIC_MODE_VIDEO_SYNC` 依赖从本地视频提取音轨 FD。

第一版策略：

- 本地模式：保留 `mute / replace / video_sync`
- 流模式：仅保证 `mute / replace`
- `video_sync` 在流模式下自动降级为 `mute`，并在日志中提示

### 6.4 `ConfigWatcher` 流配置变化触发重启

除现有本地媒体字段外，以下字段变化也必须触发 `onMediaSourceChanged()`：

- `media_source_type`
- `stream_url`

以下字段变化触发播放器重启但不需要重新 hook：

- `stream_auto_reconnect`
- `stream_transport_hint`
- `stream_timeout_ms`

`stream_enable_local_fallback` 变化只需要更新内存中的 `MediaSourceDescriptor`，不需要重启。

### 6.5 ExoPlayer 线程模型

ExoPlayer 要求在创建它的线程上调用大多数方法（默认是主线程）。但在 Xposed 注入环境中，Hook 代码可能运行在各种线程上。

解决方案：

- ExoPlayer 实例在 `MediaPlayerManager` 的 GL 线程或专用 `HandlerThread` 上创建
- 所有对 ExoPlayer 的调用通过 `Handler.post()` 转发到创建线程
- 这与当前 `MediaPlayer` 的使用方式一致（`setupMediaPlayer` 已经在特定线程上操作）

### 6.6 `VideoProvider.openFile()` 流模式处理

当前 `VideoProvider.openFile()` 返回本地视频的 `ParcelFileDescriptor`。流模式下被 Hook 进程可能仍会通过 Provider 查询视频文件（用于配置同步）。

解决方案：流模式下 Provider 仍然返回本地视频 FD（用于 fallback 和配置传递），但播放器不使用它。`MediaSourceDescriptor.isStream()` 决定播放器走哪条路径。

---

## 7. 分阶段实现计划

### 阶段 1：配置 + 媒体源抽象 + 播放器后端接口

新增文件：

- `MediaSourceDescriptor.java`
- `SurfacePlayerBackend.java`
- `AndroidMediaPlayerBackend.java`

修改文件：

- `ConfigManager.java` — 新增流媒体配置键
- `VideoManager.java` — 新增 `getCurrentMediaSource()` / `isStreamMode()`
- `HookGuards.java` — 适配 `MediaSourceDescriptor`
- `ConfigWatcher.java` — 流配置变化触发重启
- `MediaPlayerManager.java` — 用 `AndroidMediaPlayerBackend` 封装现有 `MediaPlayer` 逻辑
- `MainViewModel.kt` — 新增流配置状态
- `SettingsScreen.kt` — 新增流模式 UI
- `strings.xml` — 新增字符串
- `AndroidManifest.xml` — 添加 `INTERNET` 权限

目标：

- UI 能切换本地/流模式并保存 URL
- 配置能同步到 Hook 进程
- **本地模式完全不回归**（`AndroidMediaPlayerBackend` 封装现有逻辑，行为不变）

### 阶段 2：接入 ExoPlayer 流后端

新增文件：

- `ExoPlayerBackend.java`

修改文件：

- `app/build.gradle` — 添加 Media3 依赖
- `MediaPlayerManager.java` — 根据 `MediaSourceDescriptor` 选择后端

目标：

- 流模式下 Camera2 预览能显示网络流画面
- 支持 RTSP / RTMP / HLS / DASH / HTTP
- 支持自动重连和超时
- Camera1 预览也能显示网络流

### 阶段 3：完善兼容性和边界

修改文件：

- `Camera2SessionHook.java` — 流模式下拍照兜底路径
- `Camera1Handler.java` — 流模式下拍照兜底路径
- `MicrophoneHandler.java` / `AudioDataProvider.java` — `video_sync` 降级

目标：

- 流模式下拍照不崩（优先 GL 截帧，跳过 `MediaMetadataRetriever`）
- `video_sync` 在流模式下自动降级
- 前后台切换和 Surface 重建可恢复

---

## 8. 第一版文件清单

### 必改文件

| 文件 | 改动范围 |
|------|---------|
| `ConfigManager.java` | 新增 6 个流配置键和默认值 |
| `VideoManager.java` | 新增 `getCurrentMediaSource()` / `isStreamMode()` / `hasUsableMediaSource()` |
| `HookGuards.java` | `shouldBypass()` 适配 `MediaSourceDescriptor` |
| `ConfigWatcher.java` | `handleConfigUpdate()` 增加流配置变化检测 |
| `MediaPlayerManager.java` | 提取 `AndroidMediaPlayerBackend`，根据媒体源选择后端 |
| `Camera2SessionHook.java` | `captureFrameFromVideoFile()` 流模式跳过；`HookGuards` 调用点适配 |
| `Camera2Handler.java` | `HookGuards` 调用点适配 |
| `Camera1Handler.java` | `HookGuards` 调用点适配 + 播放器后端工厂 |
| `MainViewModel.kt` | 新增流配置状态和操作 |
| `SettingsScreen.kt` | 新增流模式 UI 控件 |
| `strings.xml` | 新增流模式相关字符串 |
| `AndroidManifest.xml` | 添加 `INTERNET` 权限 |
| `build.gradle` | 添加 Media3 依赖 |
| `VideoProvider.java` | 流模式下的 query/openFile 处理 |

### 新增文件

| 文件 | 说明 |
|------|------|
| `MediaSourceDescriptor.java` | 统一媒体源描述（类型 + 参数） |
| `SurfacePlayerBackend.java` | 播放器后端接口 |
| `AndroidMediaPlayerBackend.java` | 封装现有 `MediaPlayer` 逻辑 |
| `ExoPlayerBackend.java` | ExoPlayer 网络流后端 |

---

## 9. 验收建议

第一版至少验证：

- [ ] 本地模式行为完全不回归（Snapchat、WhatsApp 等已适配 App 正常工作）
- [ ] 能在 SettingsScreen 切换到流模式并保存 URL
- [ ] Camera2 场景能显示 RTSP 网络流
- [ ] Camera2 场景能显示 HTTP/HTTPS 网络流
- [ ] Camera2 场景能显示 RTMP 网络流（如果有测试源）
- [ ] Camera1 场景能显示网络流
- [ ] 切前后台后可恢复
- [ ] 旋转后不黑屏
- [ ] 断流后自动重连
- [ ] WhatsApp 视频通话在流模式下本机和对方画面正常
- [ ] `mute / replace` 麦克风模式在流模式下不崩溃
- [ ] 流模式下拍照不崩（即使画质降级）

---

## 10. 第一版发布说明建议明确的限制

- 网络流模式优先保证预览替换，已验证 RTSP / HTTP(S) / RTMP / HLS
- `video_sync` 麦克风模式暂不支持网络流，流模式下自动降级为静音
- 流模式下拍照替换依赖 GL 抓帧，极端设备上可能退化为占位图
- 少数目标 App 可能因进程网络策略限制无法直接拉流
- 独立裸 RTP（不经过 RTSP）暂不支持
