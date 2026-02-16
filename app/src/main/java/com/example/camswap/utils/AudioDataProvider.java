package com.example.camswap.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * 音频数据提供器 —— 解码本地音频文件为 PCM 数据并提供给 MicrophoneHandler。
 * <p>
 * 功能：
 * <ul>
 *   <li>使用 MediaExtractor + MediaCodec 解码音频文件到 PCM 16-bit</li>
 *   <li>缓存解码后的 PCM 数据在内存中</li>
 *   <li>根据目标 AudioRecord 参数进行重采样和声道转换</li>
 *   <li>支持循环播放</li>
 *   <li>线程安全</li>
 * </ul>
 */
public class AudioDataProvider {

    private static final String TAG = "【CS】[AudioData]";

    // 解码后的 PCM 16-bit 数据（原始格式）
    private static short[] pcmData = null;
    private static int pcmSampleRate = 0;
    private static int pcmChannels = 0;

    // 播放位置（以 sample 为单位，一个 sample = 一个声道的一个采样点）
    private static long playbackPosition = 0;

    // 音频时长（毫秒），解码后计算
    private static long audioDurationMs = 0;

    // 当前加载的文件路径
    private static String currentFilePath = null;

    // 同步锁
    private static final Object lock = new Object();

    // 默认音频文件名
    public static final String DEFAULT_AUDIO_NAME = "Mic.mp3";

    /**
     * 获取音频文件路径
     */
    public static String getAudioFilePath() {
        String audioDir = VideoManager.video_path;
        String selectedAudio = VideoManager.getConfig().getString(
                com.example.camswap.ConfigManager.KEY_SELECTED_AUDIO, null);
        
        if (selectedAudio != null && !selectedAudio.isEmpty()) {
            File selected = new File(audioDir, selectedAudio);
            if (selected.exists()) {
                return selected.getAbsolutePath();
            }
        }

        // 降级查找：Mic.mp3 → 目录中任意音频文件
        File defaultFile = new File(audioDir, DEFAULT_AUDIO_NAME);
        if (defaultFile.exists()) {
            return defaultFile.getAbsolutePath();
        }

        // 扫描目录中任意音频文件
        File dir = new File(audioDir);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles(file -> {
                String name = file.getName().toLowerCase();
                return name.endsWith(".mp3") || name.endsWith(".wav")
                        || name.endsWith(".aac") || name.endsWith(".m4a")
                        || name.endsWith(".ogg") || name.endsWith(".flac");
            });
            if (files != null && files.length > 0) {
                return files[0].getAbsolutePath();
            }
        }

        return null;
    }

    /**
     * 加载并解码音频文件到内存。如果文件已加载且未改变，跳过解码。
     *
     * @return true 如果数据可用
     */
    public static boolean loadAudioFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            LogUtil.log(TAG + " 无音频文件路径");
            return false;
        }

        synchronized (lock) {
            // 已加载同一文件
            if (filePath.equals(currentFilePath) && pcmData != null) {
                return true;
            }

            File file = new File(filePath);
            if (!file.exists()) {
                LogUtil.log(TAG + " 音频文件不存在: " + filePath);
                return false;
            }

            LogUtil.log(TAG + " 开始解码音频: " + filePath);

            MediaExtractor extractor = null;
            MediaCodec codec = null;

            try {
                extractor = new MediaExtractor();
                extractor.setDataSource(filePath);

                // 查找音频轨道
                int audioTrack = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrack = i;
                        break;
                    }
                }

                if (audioTrack < 0) {
                    LogUtil.log(TAG + " 未找到音频轨道");
                    return false;
                }

                extractor.selectTrack(audioTrack);
                MediaFormat format = extractor.getTrackFormat(audioTrack);
                String mime = format.getString(MediaFormat.KEY_MIME);
                pcmSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                pcmChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

                LogUtil.log(TAG + " 音频格式: mime=" + mime
                        + " sampleRate=" + pcmSampleRate
                        + " channels=" + pcmChannels);

                // 创建解码器
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, null, null, 0);
                codec.start();

                // 解码循环
                // 预分配缓冲区，初始 1MB（约 5 秒 44100Hz 16-bit 立体声）
                short[] tempBuffer = new short[512 * 1024];
                int totalSamples = 0;
                boolean inputDone = false;
                boolean outputDone = false;
                long timeoutUs = 10000; // 10ms

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                while (!outputDone) {
                    // 喂入输入数据
                    if (!inputDone) {
                        int inputIndex = codec.dequeueInputBuffer(timeoutUs);
                        if (inputIndex >= 0) {
                            ByteBuffer inputBuffer;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                inputBuffer = codec.getInputBuffer(inputIndex);
                            } else {
                                inputBuffer = codec.getInputBuffers()[inputIndex];
                            }
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long presentationTimeUs = extractor.getSampleTime();
                                codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                        presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }

                    // 读取输出数据
                    int outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs);
                    if (outputIndex >= 0) {
                        ByteBuffer outputBuffer;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            outputBuffer = codec.getOutputBuffer(outputIndex);
                        } else {
                            outputBuffer = codec.getOutputBuffers()[outputIndex];
                        }

                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                            // PCM 16-bit: 每个 sample 2 字节
                            int sampleCount = bufferInfo.size / 2;

                            // 扩容
                            if (totalSamples + sampleCount > tempBuffer.length) {
                                int newSize = Math.max(tempBuffer.length * 2,
                                        totalSamples + sampleCount);
                                tempBuffer = Arrays.copyOf(tempBuffer, newSize);
                            }

                            // 读取 short 数据
                            ShortBuffer shortBuffer = outputBuffer.order(ByteOrder.nativeOrder())
                                    .asShortBuffer();
                            shortBuffer.get(tempBuffer, totalSamples, sampleCount);
                            totalSamples += sampleCount;
                        }

                        codec.releaseOutputBuffer(outputIndex, false);

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    }
                }

                // 裁剪到实际大小
                pcmData = Arrays.copyOf(tempBuffer, totalSamples);
                playbackPosition = 0;
                currentFilePath = filePath;

                // 计算音频时长
                int frameCount = totalSamples / pcmChannels;
                audioDurationMs = (long) frameCount * 1000 / pcmSampleRate;

                LogUtil.log(TAG + " 解码完成: " + totalSamples + " samples ("
                        + (audioDurationMs / 1000) + "s, " + audioDurationMs + "ms)");

                return true;

            } catch (Exception e) {
                LogUtil.log(TAG + " 解码失败: " + e);
                pcmData = null;
                currentFilePath = null;
                return false;
            } finally {
                try {
                    if (codec != null) {
                        codec.stop();
                        codec.release();
                    }
                } catch (Exception ignored) {}
                try {
                    if (extractor != null) {
                        extractor.release();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 检查音频数据是否已就绪
     */
    public static boolean isReady() {
        synchronized (lock) {
            return pcmData != null && pcmData.length > 0;
        }
    }

    /**
     * 获取当前已加载的音频文件路径
     */
    public static String getCurrentFilePath() {
        synchronized (lock) {
            return currentFilePath;
        }
    }

    /**
     * 重置播放位置
     */
    public static void resetPosition() {
        synchronized (lock) {
            playbackPosition = 0;
        }
    }

    /**
     * 释放缓存数据
     */
    public static void release() {
        synchronized (lock) {
            pcmData = null;
            currentFilePath = null;
            playbackPosition = 0;
            audioDurationMs = 0;
        }
    }

    /**
     * 获取音频时长（毫秒）
     */
    public static long getDurationMs() {
        synchronized (lock) {
            return audioDurationMs;
        }
    }

    /**
     * 获取源音频采样率
     */
    public static int getSampleRate() {
        return pcmSampleRate;
    }

    /**
     * 获取源音频声道数
     */
    public static int getChannelCount() {
        return pcmChannels;
    }

    // ================================================================
    // 数据填充方法 — 对应 AudioRecord.read() 的各种重载
    // ================================================================

    /**
     * 填充 byte[] 缓冲区（对应 AudioRecord.read(byte[], int, int)）
     * PCM 16-bit 格式：每个 sample 占 2 字节，小端序
     *
     * @param buffer           目标缓冲区
     * @param offset           写入偏移
     * @param size             要填充的字节数
     * @param targetSampleRate 目标采样率
     * @param targetChannels   目标声道数
     * @return 实际填充的字节数
     */
    public static int fillBytes(byte[] buffer, int offset, int size,
                                int targetSampleRate, int targetChannels) {
        synchronized (lock) {
            if (pcmData == null || pcmData.length == 0) return 0;

            // 字节数 → sample 数（16-bit = 2 bytes per sample）
            int samplesNeeded = size / 2;
            short[] converted = getConvertedSamples(samplesNeeded, targetSampleRate, targetChannels);

            int bytesToFill = Math.min(size, converted.length * 2);
            // short[] → byte[] (小端序)
            for (int i = 0; i < converted.length && (i * 2 + 1) < size; i++) {
                buffer[offset + i * 2] = (byte) (converted[i] & 0xFF);
                buffer[offset + i * 2 + 1] = (byte) ((converted[i] >> 8) & 0xFF);
            }
            return bytesToFill;
        }
    }

    /**
     * 填充 short[] 缓冲区（对应 AudioRecord.read(short[], int, int)）
     *
     * @param buffer           目标缓冲区
     * @param offset           写入偏移
     * @param size             要填充的 short 数
     * @param targetSampleRate 目标采样率
     * @param targetChannels   目标声道数
     * @return 实际填充的 short 数
     */
    public static int fillShorts(short[] buffer, int offset, int size,
                                 int targetSampleRate, int targetChannels) {
        synchronized (lock) {
            if (pcmData == null || pcmData.length == 0) return 0;

            short[] converted = getConvertedSamples(size, targetSampleRate, targetChannels);
            int toFill = Math.min(size, converted.length);
            System.arraycopy(converted, 0, buffer, offset, toFill);
            return toFill;
        }
    }

    /**
     * 填充 float[] 缓冲区（对应 AudioRecord.read(float[], int, int, int)）
     *
     * @param buffer           目标缓冲区
     * @param offset           写入偏移
     * @param size             要填充的 float 数
     * @param targetSampleRate 目标采样率
     * @param targetChannels   目标声道数
     * @return 实际填充的 float 数
     */
    public static int fillFloats(float[] buffer, int offset, int size,
                                 int targetSampleRate, int targetChannels) {
        synchronized (lock) {
            if (pcmData == null || pcmData.length == 0) return 0;

            short[] converted = getConvertedSamples(size, targetSampleRate, targetChannels);
            int toFill = Math.min(size, converted.length);
            for (int i = 0; i < toFill; i++) {
                // short (-32768~32767) → float (-1.0~1.0)
                buffer[offset + i] = converted[i] / 32768.0f;
            }
            return toFill;
        }
    }

    /**
     * 填充 ByteBuffer 缓冲区（对应 AudioRecord.read(ByteBuffer, int) 和 read(ByteBuffer, int, int)）
     *
     * @param buffer           目标 ByteBuffer
     * @param size             要填充的字节数
     * @param targetSampleRate 目标采样率
     * @param targetChannels   目标声道数
     * @return 实际填充的字节数
     */
    public static int fillByteBuffer(ByteBuffer buffer, int size,
                                     int targetSampleRate, int targetChannels) {
        synchronized (lock) {
            if (pcmData == null || pcmData.length == 0) return 0;

            int samplesNeeded = size / 2;
            short[] converted = getConvertedSamples(samplesNeeded, targetSampleRate, targetChannels);

            int bytesToFill = Math.min(size, converted.length * 2);
            ByteBuffer temp = ByteBuffer.allocate(bytesToFill).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < converted.length && i * 2 < bytesToFill; i++) {
                temp.putShort(converted[i]);
            }
            temp.flip();
            buffer.put(temp);
            return bytesToFill;
        }
    }

    // ================================================================
    // 按时间位置填充方法 — 方案 C 视频同步专用
    // ================================================================

    /**
     * 按绝对时间位置填充 byte[]（方案 C）
     */
    public static int fillBytesAtPosition(byte[] buffer, int offset, int size,
                                          int targetSampleRate, int targetChannels, long positionMs) {
        synchronized (lock) {
            if (pcmData == null || pcmData.length == 0) return 0;
            int samplesNeeded = size / 2;
            short[] converted = getConvertedSamplesAtPosition(samplesNeeded, targetSampleRate, targetChannels, positionMs);
            int bytesToFill = Math.min(size, converted.length * 2);
            for (int i = 0; i < converted.length && (i * 2 + 1) < size; i++) {
                buffer[offset + i * 2] = (byte) (converted[i] & 0xFF);
                buffer[offset + i * 2 + 1] = (byte) ((converted[i] >> 8) & 0xFF);
            }
            return bytesToFill;
        }
    }

    /**
     * 按绝对时间位置填充 short[]（方案 C）
     */
    public static int fillShortsAtPosition(short[] buffer, int offset, int size,
                                           int targetSampleRate, int targetChannels, long positionMs) {
        synchronized (lock) {
            if (pcmData == null || pcmData.length == 0) return 0;
            short[] converted = getConvertedSamplesAtPosition(size, targetSampleRate, targetChannels, positionMs);
            int toFill = Math.min(size, converted.length);
            System.arraycopy(converted, 0, buffer, offset, toFill);
            return toFill;
        }
    }

    /**
     * 按绝对时间位置填充 float[]（方案 C）
     */
    public static int fillFloatsAtPosition(float[] buffer, int offset, int size,
                                           int targetSampleRate, int targetChannels, long positionMs) {
        synchronized (lock) {
            if (pcmData == null || pcmData.length == 0) return 0;
            short[] converted = getConvertedSamplesAtPosition(size, targetSampleRate, targetChannels, positionMs);
            int toFill = Math.min(size, converted.length);
            for (int i = 0; i < toFill; i++) {
                buffer[offset + i] = converted[i] / 32768.0f;
            }
            return toFill;
        }
    }

    /**
     * 按绝对时间位置填充 ByteBuffer（方案 C）
     */
    public static int fillByteBufferAtPosition(ByteBuffer buffer, int size,
                                               int targetSampleRate, int targetChannels, long positionMs) {
        synchronized (lock) {
            if (pcmData == null || pcmData.length == 0) return 0;
            int samplesNeeded = size / 2;
            short[] converted = getConvertedSamplesAtPosition(samplesNeeded, targetSampleRate, targetChannels, positionMs);
            int bytesToFill = Math.min(size, converted.length * 2);
            ByteBuffer temp = ByteBuffer.allocate(bytesToFill).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < converted.length && i * 2 < bytesToFill; i++) {
                temp.putShort(converted[i]);
            }
            temp.flip();
            buffer.put(temp);
            return bytesToFill;
        }
    }

    // ================================================================
    // 内部方法
    // ================================================================

    /**
     * 获取经过重采样和声道转换后的 PCM 数据。
     * 调用者需持有 lock。
     *
     * @param samplesNeeded    需要的 sample 数量（目标格式的 sample，包含声道）
     * @param targetSampleRate 目标采样率
     * @param targetChannels   目标声道数
     * @return 转换后的 short 数组
     */
    private static short[] getConvertedSamples(int samplesNeeded,
                                               int targetSampleRate, int targetChannels) {
        if (pcmData == null || pcmData.length == 0) {
            return new short[0];
        }

        // 源数据的帧数（一帧 = 所有声道的一个采样点）
        int srcFrameCount = pcmData.length / pcmChannels;

        // 目标的帧数
        int targetFrameCount = samplesNeeded / targetChannels;
        if (targetFrameCount <= 0) targetFrameCount = 1;

        short[] result = new short[targetFrameCount * targetChannels];

        // 计算重采样比率
        double ratio = (double) pcmSampleRate / targetSampleRate;

        for (int frame = 0; frame < targetFrameCount; frame++) {
            // 计算在源数据中的位置（带循环）
            double srcFramePos = (playbackPosition / targetChannels + frame) * ratio;
            long srcFrameIndex = (long) srcFramePos % srcFrameCount;
            double frac = srcFramePos - (long) srcFramePos;

            // 下一帧（用于线性插值）
            long nextFrameIndex = (srcFrameIndex + 1) % srcFrameCount;

            for (int ch = 0; ch < targetChannels; ch++) {
                // 源声道映射
                int srcCh = ch % pcmChannels;

                int idx1 = (int) (srcFrameIndex * pcmChannels + srcCh);
                int idx2 = (int) (nextFrameIndex * pcmChannels + srcCh);

                // 边界保护
                if (idx1 >= pcmData.length) idx1 = pcmData.length - 1;
                if (idx2 >= pcmData.length) idx2 = pcmData.length - 1;
                if (idx1 < 0) idx1 = 0;
                if (idx2 < 0) idx2 = 0;

                // 线性插值
                short s1 = pcmData[idx1];
                short s2 = pcmData[idx2];
                result[frame * targetChannels + ch] = (short) (s1 + (s2 - s1) * frac);
            }
        }

        // 更新播放位置（以目标 sample 为单位）
        playbackPosition += samplesNeeded;
        // 循环：当播放位置超过一轮时重置
        long totalTargetSamples = (long) (srcFrameCount / ratio) * targetChannels;
        if (totalTargetSamples > 0) {
            playbackPosition = playbackPosition % totalTargetSamples;
        }

        return result;
    }

    /**
     * 根据绝对时间位置获取 PCM 数据（方案 C 专用，不更新 playbackPosition）。
     * 调用者需持有 lock。
     *
     * @param samplesNeeded    需要的 sample 数量
     * @param targetSampleRate 目标采样率
     * @param targetChannels   目标声道数
     * @param positionMs       绝对时间位置（毫秒）
     * @return 转换后的 short 数组
     */
    private static short[] getConvertedSamplesAtPosition(int samplesNeeded,
                                                         int targetSampleRate, int targetChannels,
                                                         long positionMs) {
        if (pcmData == null || pcmData.length == 0) {
            return new short[0];
        }

        int srcFrameCount = pcmData.length / pcmChannels;
        int targetFrameCount = samplesNeeded / targetChannels;
        if (targetFrameCount <= 0) targetFrameCount = 1;

        short[] result = new short[targetFrameCount * targetChannels];
        double ratio = (double) pcmSampleRate / targetSampleRate;

        // 将毫秒位置转换为源帧位置
        long startSrcFrame = (long) (positionMs / 1000.0 * pcmSampleRate);
        startSrcFrame = startSrcFrame % srcFrameCount; // 循环

        for (int frame = 0; frame < targetFrameCount; frame++) {
            double srcFramePos = (startSrcFrame + frame * ratio);
            long srcFrameIndex = (long) srcFramePos % srcFrameCount;
            double frac = srcFramePos - (long) srcFramePos;
            long nextFrameIndex = (srcFrameIndex + 1) % srcFrameCount;

            for (int ch = 0; ch < targetChannels; ch++) {
                int srcCh = ch % pcmChannels;
                int idx1 = (int) (srcFrameIndex * pcmChannels + srcCh);
                int idx2 = (int) (nextFrameIndex * pcmChannels + srcCh);
                if (idx1 >= pcmData.length) idx1 = pcmData.length - 1;
                if (idx2 >= pcmData.length) idx2 = pcmData.length - 1;
                if (idx1 < 0) idx1 = 0;
                if (idx2 < 0) idx2 = 0;

                short s1 = pcmData[idx1];
                short s2 = pcmData[idx2];
                result[frame * targetChannels + ch] = (short) (s1 + (s2 - s1) * frac);
            }
        }

        return result;
    }
}
