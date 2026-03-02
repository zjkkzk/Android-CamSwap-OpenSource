package com.example.camswap;

import android.media.AudioFormat;
import android.media.MediaPlayer;
import android.os.Build;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.example.camswap.utils.AudioDataProvider;
import com.example.camswap.utils.LogUtil;
import com.example.camswap.utils.VideoManager;

/**
 * 麦克风 Hook 处理器 —— 支持三种模式
 * <ul>
 * <li><b>静音模式 (mute)</b>：方案 A，将音频数据替换为全零</li>
 * <li><b>替换模式 (replace)</b>：方案 B，注入本地音频文件的 PCM 数据</li>
 * <li><b>视频同步 (video_sync)</b>：方案 C，从当前视频提取音轨，与视频帧同步播放</li>
 * </ul>
 * <p>
 * 所有 Hook 在执行替换前都会实时检查 {@link ConfigManager#KEY_ENABLE_MIC_HOOK} 配置值，
 * 当配置为 {@code false} 时不做任何操作，保证运行时可热切换。
 */
public class MicrophoneHandler implements ICameraHandler {

    private static final String TAG = "【CS】[Mic]";

    // 方案 B 时长校验：是否已提醒过用户
    private static volatile boolean durationWarningShown = false;

    // 视频同步模式：记住上次已知的播放位置，避免播放器暂时不可用时回退到 0
    private static volatile long lastKnownPlaybackPositionMs = 0;

    // 异步加载标记：使用 AtomicBoolean 防止竞态条件导致重复提交加载任务
    private static final AtomicBoolean asyncLoadingInProgress = new AtomicBoolean(false);

    /**
     * 存储每个 AudioRecord 实例的构造参数
     * 使用 ConcurrentHashMap 防止 GC 过早回收导致参数丢失，
     * 在 AudioRecord.release() 时手动清理
     */
    private static final Map<Object, AudioRecordParams> recordParamsMap = new ConcurrentHashMap<>();

    /**
     * AudioRecord 构造参数
     */
    private static class AudioRecordParams {
        final int audioSource;
        final int sampleRate;
        final int channelConfig;
        final int audioFormat;
        final int bufferSize;
        final int channelCount;

        AudioRecordParams(int audioSource, int sampleRate, int channelConfig,
                int audioFormat, int bufferSize) {
            this.audioSource = audioSource;
            this.sampleRate = sampleRate;
            this.channelConfig = channelConfig;
            this.audioFormat = audioFormat;
            this.bufferSize = bufferSize;
            this.channelCount = getChannelCount(channelConfig);
        }

        private static int getChannelCount(int channelConfig) {
            switch (channelConfig) {
                case AudioFormat.CHANNEL_IN_MONO:
                    return 1;
                case AudioFormat.CHANNEL_IN_STEREO:
                    return 2;
                default:
                    return Integer.bitCount(channelConfig);
            }
        }
    }

    // ================================================================
    // 配置读取
    // ================================================================

    private static boolean isMicHookEnabled() {
        try {
            return VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getMicHookMode() {
        try {
            return VideoManager.getConfig().getString(
                    ConfigManager.KEY_MIC_HOOK_MODE, ConfigManager.MIC_MODE_MUTE);
        } catch (Exception e) {
            return ConfigManager.MIC_MODE_MUTE;
        }
    }

    // ================================================================
    // 模式判断 + 数据加载
    // ================================================================

    /**
     * 检查是否为替换模式，并确保音频数据已加载。
     * 如果数据尚未就绪，触发异步加载并返回 false（本次 read 用静音填充，下次再替换）。
     */
    private static boolean isReplaceMode() {
        if (!ConfigManager.MIC_MODE_REPLACE.equals(getMicHookMode())) {
            return false;
        }
        String audioPath = AudioDataProvider.getAudioFilePath();
        if (audioPath == null)
            return false;

        // 检查是否需要（重新）加载：未就绪 或 文件已切换
        String loadedPath = AudioDataProvider.getCurrentFilePath();
        if (!AudioDataProvider.isReady() || !audioPath.equals(loadedPath)) {
            // 异步加载，不阻塞音频线程
            final String pathToLoad = audioPath;
            preloadAudioFileAsync(pathToLoad);
            return false; // 数据还没准备好，本次用静音
        }
        return true;
    }

    /**
     * 检查是否为视频同步模式，并确保视频音轨数据已加载。
     * 如果数据尚未就绪，触发异步加载并返回 false。
     */
    private static boolean isVideoSyncMode() {
        if (!ConfigManager.MIC_MODE_VIDEO_SYNC.equals(getMicHookMode())) {
            return false;
        }
        String videoPath = VideoManager.getCurrentVideoPath();
        if (videoPath == null)
            return false;

        String loadedPath = AudioDataProvider.getCurrentFilePath();
        if (!AudioDataProvider.isReady() || !videoPath.equals(loadedPath)) {
            // 异步加载，不阻塞音频线程
            final String pathToLoad = videoPath;
            preloadAudioFileAsync(pathToLoad);
            return false;
        }
        return true;
    }

    /**
     * 异步预加载音频数据（根据当前模式决定加载什么文件）
     */
    private static void preloadAudioAsync() {
        if (!isMicHookEnabled()) return;
        String mode = getMicHookMode();
        String pathToLoad = null;
        if (ConfigManager.MIC_MODE_REPLACE.equals(mode)) {
            pathToLoad = AudioDataProvider.getAudioFilePath();
        } else if (ConfigManager.MIC_MODE_VIDEO_SYNC.equals(mode)) {
            pathToLoad = VideoManager.getCurrentVideoPath();
        }
        if (pathToLoad != null) {
            preloadAudioFileAsync(pathToLoad);
        }
    }

    /**
     * 在后台线程中加载指定音频文件，避免阻塞音频回调线程。
     * 使用 asyncLoadingInProgress 标记防止重复提交。
     */
    private static void preloadAudioFileAsync(final String filePath) {
        if (filePath == null) return;
        // 已经加载了同一文件，无需重复
        if (filePath.equals(AudioDataProvider.getCurrentFilePath()) && AudioDataProvider.isReady()) {
            return;
        }
        if (!asyncLoadingInProgress.compareAndSet(false, true)) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LogUtil.log(TAG + " 异步加载音频: " + filePath);
                    AudioDataProvider.loadAudioFile(filePath);
                    durationWarningShown = false;
                    checkDurationMismatch();
                    LogUtil.log(TAG + " 异步加载完成: " + filePath
                            + " ready=" + AudioDataProvider.isReady());
                } catch (Exception e) {
                    LogUtil.log(TAG + " 异步加载失败: " + e);
                } finally {
                    asyncLoadingInProgress.set(false);
                }
            }
        }, "CS-AudioPreload").start();
    }

    /**
     * 获取当前视频 MediaPlayer 的播放位置（毫秒）
     * 按优先级尝试所有可能的 MediaPlayer 实例。
     * 当所有播放器都不在播放时，返回上次已知位置而非 0，防止同步失效。
     */
    private static long getVideoPlaybackPositionMs() {
        MediaPlayer[] players = {
                HookMain.playerManager.c2_player, HookMain.playerManager.c2_player_1,
                HookMain.playerManager.mMediaPlayer, HookMain.playerManager.mplayer1
        };
        for (MediaPlayer mp : players) {
            try {
                if (mp != null && mp.isPlaying()) {
                    long pos = mp.getCurrentPosition();
                    lastKnownPlaybackPositionMs = pos;
                    return pos;
                }
            } catch (Exception ignored) {
            }
        }
        // 返回上次已知位置，避免在播放器暂时不可用时音频跳回开头
        return lastKnownPlaybackPositionMs;
    }

    /**
     * 获取指定 AudioRecord 实例的参数
     */
    private static AudioRecordParams getParams(Object audioRecord) {
        AudioRecordParams params = recordParamsMap.get(audioRecord);
        if (params != null)
            return params;
        // 回退：尝试从 AudioRecord 实例动态获取参数
        try {
            int sampleRate = (int) XposedHelpers.callMethod(audioRecord, "getSampleRate");
            int channelConfig = (int) XposedHelpers.callMethod(audioRecord, "getChannelConfiguration");
            int audioFormat = (int) XposedHelpers.callMethod(audioRecord, "getAudioFormat");
            params = new AudioRecordParams(0, sampleRate, channelConfig, audioFormat, 4096);
            recordParamsMap.put(audioRecord, params);
            LogUtil.log(TAG + " 动态获取 AudioRecord 参数: sampleRate=" + sampleRate
                    + " channelConfig=" + channelConfig + " audioFormat=" + audioFormat);
            return params;
        } catch (Exception e) {
            LogUtil.log(TAG + " 动态获取参数失败，使用默认值: " + e);
        }
        return new AudioRecordParams(0, 44100,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096);
    }

    /**
     * 方案 B 时长校验：对比音频文件与视频文件时长
     */
    private static void checkDurationMismatch() {
        if (durationWarningShown)
            return;

        try {
            long audioDuration = AudioDataProvider.getDurationMs();
            if (audioDuration <= 0)
                return;

            // 尝试获取视频时长
            long videoDuration = -1;
            MediaPlayer[] players = {
                    HookMain.playerManager.c2_player, HookMain.playerManager.c2_player_1,
                    HookMain.playerManager.mMediaPlayer, HookMain.playerManager.mplayer1
            };
            for (MediaPlayer mp : players) {
                try {
                    if (mp != null) {
                        videoDuration = mp.getDuration();
                        if (videoDuration > 0)
                            break;
                    }
                } catch (Exception ignored) {
                }
            }

            if (videoDuration <= 0)
                return;

            // 超过 2 秒差异时警告
            long diff = Math.abs(audioDuration - videoDuration);
            if (diff > 2000) {
                String msg = "【CS】⚠ 音频文件时长(" + (audioDuration / 1000) + "s)与视频时长("
                        + (videoDuration / 1000) + "s)不一致，可能导致音画不同步";
                LogUtil.log(msg);
                VideoManager.showToast("音频与视频时长不一致\n音频: " + (audioDuration / 1000)
                        + "s  视频: " + (videoDuration / 1000) + "s");
                durationWarningShown = true;
            }
        } catch (Exception e) {
            LogUtil.log(TAG + " 时长校验异常: " + e);
        }
    }

    // ================================================================
    // 供 NativeAudioHook 调用的 package-visible 静态方法
    // ================================================================

    static boolean isMicHookEnabledStatic() {
        return isMicHookEnabled();
    }

    static String getMicHookModeStatic() {
        return getMicHookMode();
    }

    static long getVideoPlaybackPositionMsStatic() {
        return getVideoPlaybackPositionMs();
    }

    static void preloadAudioAsyncStatic() {
        preloadAudioAsync();
    }

    // ================================================================
    // Hook 初始化
    // ================================================================

    @Override
    public void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        LogUtil.log(TAG + " 初始化麦克风 Hook");

        // ============================================================
        // 1. Hook AudioRecord 构造函数 — 捕获参数 + 预加载音频
        // ============================================================
        try {
            XposedHelpers.findAndHookConstructor(
                    "android.media.AudioRecord", lpparam.classLoader,
                    int.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int audioSource = (int) param.args[0];
                            int sampleRate = (int) param.args[1];
                            int channelConfig = (int) param.args[2];
                            int audioFormat = (int) param.args[3];
                            int bufferSize = (int) param.args[4];

                            LogUtil.log(TAG + " AudioRecord 创建: audioSource=" + audioSource
                                    + " sampleRate=" + sampleRate
                                    + " channelConfig=" + channelConfig
                                    + " audioFormat=" + audioFormat
                                    + " bufferSize=" + bufferSize);

                            AudioRecordParams params = new AudioRecordParams(
                                    audioSource, sampleRate, channelConfig, audioFormat, bufferSize);
                            recordParamsMap.put(param.thisObject, params);

                            // 异步预加载音频数据（不阻塞构造线程）
                            preloadAudioAsync();
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord 构造函数失败: " + t);
        }

        // ============================================================
        // 1.5 Hook AudioRecord.Builder.build() — 捕获 Builder 模式创建的 AudioRecord
        // ============================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(
                        "android.media.AudioRecord$Builder", lpparam.classLoader,
                        "build",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object audioRecord = param.getResult();
                                if (audioRecord == null)
                                    return;

                                try {
                                    int sampleRate = (int) XposedHelpers.callMethod(audioRecord, "getSampleRate");
                                    int channelConfig = (int) XposedHelpers.callMethod(audioRecord,
                                            "getChannelConfiguration");
                                    int audioFormat = (int) XposedHelpers.callMethod(audioRecord, "getAudioFormat");
                                    int bufferSize = (int) XposedHelpers.callMethod(audioRecord,
                                            "getBufferSizeInFrames");
                                    int audioSource = 0;

                                    LogUtil.log(TAG + " AudioRecord.Builder.build(): sampleRate=" + sampleRate
                                            + " channelConfig=" + channelConfig
                                            + " audioFormat=" + audioFormat
                                            + " bufferSize=" + bufferSize);

                                    AudioRecordParams params = new AudioRecordParams(
                                            audioSource, sampleRate, channelConfig, audioFormat, bufferSize);
                                    recordParamsMap.put(audioRecord, params);

                                    // 异步预加载音频数据
                                    preloadAudioAsync();
                                } catch (Exception e) {
                                    LogUtil.log(TAG + " 获取 Builder 创建的 AudioRecord 参数失败: " + e);
                                }
                            }
                        });
            } catch (Throwable t) {
                LogUtil.log(TAG + " Hook AudioRecord.Builder.build() 失败: " + t);
            }
        }

        // ============================================================
        // 1.6 Hook AudioRecord.release() — 清理参数映射
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioRecord", lpparam.classLoader,
                    "release",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            recordParamsMap.remove(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord.release() 失败: " + t);
        }

        // ============================================================
        // 1.7 Hook AudioRecord.startRecording() — 确保音频数据在录制前已加载
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioRecord", lpparam.classLoader,
                    "startRecording",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            LogUtil.log(TAG + " AudioRecord.startRecording() 被调用, micHook="
                                    + isMicHookEnabled() + " mode=" + getMicHookMode());
                            preloadAudioAsync();
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord.startRecording() 失败: " + t);
        }

        // ============================================================
        // 2. Hook AudioRecord.read(byte[], int, int)
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioRecord", lpparam.classLoader,
                    "read", byte[].class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isMicHookEnabled())
                                return;
                            int result = (int) param.getResult();
                            if (result <= 0)
                                return;

                            byte[] buffer = (byte[]) param.args[0];
                            int offset = (int) param.args[1];
                            AudioRecordParams p = getParams(param.thisObject);

                            logReadCall(result, "byte[]");

                            if (isVideoSyncMode()) {
                                long posMs = getVideoPlaybackPositionMs();
                                AudioDataProvider.fillBytesAtPosition(buffer, offset, result,
                                        p.sampleRate, p.channelCount, posMs);
                            } else if (isReplaceMode()) {
                                AudioDataProvider.fillBytes(buffer, offset, result,
                                        p.sampleRate, p.channelCount);
                            } else {
                                Arrays.fill(buffer, offset, offset + result, (byte) 0);
                            }
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord.read(byte[]) 失败: " + t);
        }

        // ============================================================
        // 3. Hook AudioRecord.read(short[], int, int)
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioRecord", lpparam.classLoader,
                    "read", short[].class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isMicHookEnabled())
                                return;
                            int result = (int) param.getResult();
                            if (result <= 0)
                                return;

                            short[] buffer = (short[]) param.args[0];
                            int offset = (int) param.args[1];
                            AudioRecordParams p = getParams(param.thisObject);

                            logReadCall(result, "short[]");

                            if (isVideoSyncMode()) {
                                long posMs = getVideoPlaybackPositionMs();
                                AudioDataProvider.fillShortsAtPosition(buffer, offset, result,
                                        p.sampleRate, p.channelCount, posMs);
                            } else if (isReplaceMode()) {
                                AudioDataProvider.fillShorts(buffer, offset, result,
                                        p.sampleRate, p.channelCount);
                            } else {
                                Arrays.fill(buffer, offset, offset + result, (short) 0);
                            }
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord.read(short[]) 失败: " + t);
        }

        // ============================================================
        // 4. Hook AudioRecord.read(ByteBuffer, int)
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioRecord", lpparam.classLoader,
                    "read", ByteBuffer.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isMicHookEnabled())
                                return;
                            int result = (int) param.getResult();
                            if (result <= 0)
                                return;

                            ByteBuffer buffer = (ByteBuffer) param.args[0];
                            int pos = buffer.position();
                            AudioRecordParams p = getParams(param.thisObject);

                            logReadCall(result, "ByteBuffer");

                            if (isVideoSyncMode()) {
                                long posMs = getVideoPlaybackPositionMs();
                                buffer.position(pos - result);
                                AudioDataProvider.fillByteBufferAtPosition(buffer, result,
                                        p.sampleRate, p.channelCount, posMs);
                                buffer.position(pos);
                            } else if (isReplaceMode()) {
                                buffer.position(pos - result);
                                AudioDataProvider.fillByteBuffer(buffer, result,
                                        p.sampleRate, p.channelCount);
                                buffer.position(pos);
                            } else {
                                byte[] zeros = new byte[result];
                                buffer.position(pos - result);
                                buffer.put(zeros);
                                buffer.position(pos);
                            }
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord.read(ByteBuffer, int) 失败: " + t);
        }

        // ============================================================
        // 5. Hook AudioRecord.read(float[], int, int, int) — API 23+
        // ============================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(
                        "android.media.AudioRecord", lpparam.classLoader,
                        "read", float[].class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!isMicHookEnabled())
                                    return;
                                int result = (int) param.getResult();
                                if (result <= 0)
                                    return;

                                float[] buffer = (float[]) param.args[0];
                                int offset = (int) param.args[1];
                                AudioRecordParams p = getParams(param.thisObject);

                                logReadCall(result, "float[]");

                                if (isVideoSyncMode()) {
                                    long posMs = getVideoPlaybackPositionMs();
                                    AudioDataProvider.fillFloatsAtPosition(buffer, offset, result,
                                            p.sampleRate, p.channelCount, posMs);
                                } else if (isReplaceMode()) {
                                    AudioDataProvider.fillFloats(buffer, offset, result,
                                            p.sampleRate, p.channelCount);
                                } else {
                                    Arrays.fill(buffer, offset, offset + result, 0.0f);
                                }
                            }
                        });
            } catch (Throwable t) {
                LogUtil.log(TAG + " Hook AudioRecord.read(float[]) 失败: " + t);
            }
        }

        // ============================================================
        // 6. Hook AudioRecord.read(ByteBuffer, int, int) — API 23+
        // ============================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(
                        "android.media.AudioRecord", lpparam.classLoader,
                        "read", ByteBuffer.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!isMicHookEnabled())
                                    return;
                                int result = (int) param.getResult();
                                if (result <= 0)
                                    return;

                                ByteBuffer buffer = (ByteBuffer) param.args[0];
                                int pos = buffer.position();
                                AudioRecordParams p = getParams(param.thisObject);

                                logReadCall(result, "ByteBuffer(int,int)");

                                if (isVideoSyncMode()) {
                                    long posMs = getVideoPlaybackPositionMs();
                                    buffer.position(pos - result);
                                    AudioDataProvider.fillByteBufferAtPosition(buffer, result,
                                            p.sampleRate, p.channelCount, posMs);
                                    buffer.position(pos);
                                } else if (isReplaceMode()) {
                                    buffer.position(pos - result);
                                    AudioDataProvider.fillByteBuffer(buffer, result,
                                            p.sampleRate, p.channelCount);
                                    buffer.position(pos);
                                } else {
                                    byte[] zeros = new byte[result];
                                    buffer.position(pos - result);
                                    buffer.put(zeros);
                                    buffer.position(pos);
                                }
                            }
                        });
            } catch (Throwable t) {
                LogUtil.log(TAG + " Hook AudioRecord.read(ByteBuffer, int, int) 失败: " + t);
            }
        }

        // ============================================================
        // 7. Hook MediaRecorder.setAudioSource(int) — 日志
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.MediaRecorder", lpparam.classLoader,
                    "setAudioSource", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            LogUtil.log(TAG + " MediaRecorder.setAudioSource: " + param.args[0]
                                    + " (micHook=" + isMicHookEnabled()
                                    + " mode=" + getMicHookMode() + ")");
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook MediaRecorder.setAudioSource 失败: " + t);
        }

        LogUtil.log(TAG + " 麦克风 Hook 初始化完成");
    }

    private static volatile int readHookCount = 0;

    private static void logReadCall(int result, String method) {
        if (readHookCount < 10) {
            readHookCount++;
            LogUtil.log(TAG + " AudioRecord.read(" + method + ") 被调用 #" + readHookCount
                    + " result=" + result + " micHookEnabled=" + isMicHookEnabled()
                    + " mode=" + getMicHookMode());
            if (readHookCount == 10) {
                LogUtil.log(TAG + " AudioRecord.read 调用日志已达到 10 次上限，后续调用不再打印。");
            }
        }
    }
}
