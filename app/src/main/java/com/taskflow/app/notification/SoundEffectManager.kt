package com.taskflow.app.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.util.Log
import com.taskflow.app.data.preferences.SoundType
import com.taskflow.app.data.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

private const val TAG = "SoundEffectManager"

/**
 * 交互音效管理器 (SoundEffectManager).
 *
 * 设计要点:
 * 1. 使用 Android [SoundPool] 加载轻量音效资源, 支持低延迟短促播放。
 * 2. 由于不打包二进制音频素材, 音效通过 16-bit PCM 合成生成, 写入
 *    [SoundPool] 的 AudioAttributes (USAGE_ASSISTANCE_SONIFICATION)。
 * 3. 通过 [UserPreferences] 持久化: 总开关 / 类型 / 音量 (0~100)。
 * 4. 通过 [StateFlow] 暴露实时配置, 供 SettingsScreen 双向绑定。
 * 5. 关键交互触发点 (点击编辑图标、进入专注页等) 调用 [playClick] 即可,
 *    管理器内部按当前配置决定是否实际发声。
 *
 * 四种合成音色:
 * - WOOD_FISH  清脆木鱼  : 200Hz 主频 + 600Hz 谐波, 快速指数衰减, ~120ms
 * - MECHANICAL 机械轴体  : 1500Hz + 2400Hz, 极快衰减, ~50ms
 * - BUBBLE     柔和气泡  : 700Hz 短促 pop + 轻微正弦扫频, ~180ms
 * - TICK       经典滴答  : 1000Hz 锐利 tick, 极短衰减, ~40ms
 *
 * 线程安全: 仅持有 SoundPool 实例与配置字段, 内部用 supervisor scope 收集
 * 偏好变更。 [playClick] 可在任意线程调用 (SoundPool.play 本身线程安全)。
 *
 * 生命周期: 由 [com.taskflow.app.ServiceLocator] 持有的应用级单例, 随进程
 * 结束自动释放。也可显式调用 [release]。
 */
class SoundEffectManager(
    private val context: Context,
    private val preferences: UserPreferences
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ====== 实时配置 (由 UserPreferences Flow 同步) ======
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _type = MutableStateFlow(SoundType.WOOD_FISH)
    val type: StateFlow<SoundType> = _type.asStateFlow()

    private val _volume = MutableStateFlow(70)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _customUri = MutableStateFlow<String?>(null)
    val customUri: StateFlow<String?> = _customUri.asStateFlow()

    private var customPlayer: MediaPlayer? = null

    /** soundId registry: 每个 SoundType 对应一个已加载的 soundId (0 = 未加载). */
    private val soundIds = HashMap<SoundType, Int>()

    /** 当前已加载并就绪的 soundId, playClick 时按 type 查表。 */
    @Volatile
    private var ready: Boolean = false

    private var soundPool: SoundPool? = null

    init {
        // 初始化 SoundPool + 加载合成 PCM
        initSoundPool()
        // 收集偏好变更: 开关 / 类型 / 音量
        scope.launch {
            preferences.soundEnabled.collectLatest { _enabled.value = it }
        }
        scope.launch {
            preferences.soundType.collectLatest { _type.value = it }
        }
        scope.launch {
            preferences.soundVolume.collectLatest { _volume.value = it }
        }
        scope.launch {
            preferences.soundCustomUri.collectLatest { _customUri.value = it }
        }
    }

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        soundPool = pool
        // 异步加载 (load 本身是同步的, 但解码可能延迟; OnLoadCompleteListener 标记 ready)
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                Log.d(TAG, "soundPool: sample $sampleId loaded OK")
            } else {
                Log.w(TAG, "soundPool: sample $sampleId load FAILED status=$status")
            }
            // 任意一个就绪即可播放 (会查表跳过未加载的)
            ready = soundIds.values.any { it != 0 }
        }
        try {
            // SoundPool has no direct ByteArray overload in the public API, so we
            // persist each synthesized WAV to a temp file in the cache dir and
            // load via path. Files are small (< 20 KB) and auto-deleted on exit.
            SoundType.entries.forEach { st ->
                val wavBytes = synthesize(st)
                val tmp = File(context.cacheDir, "taskflow_snd_${st.key}.wav")
                tmp.outputStream().use { it.write(wavBytes) }
                soundIds[st] = pool.load(tmp.absolutePath, 1)
            }
            ready = true
            Log.d(TAG, "initSoundPool: loaded ${soundIds.size} sounds")
        } catch (t: Throwable) {
            Log.e(TAG, "initSoundPool: FAILED", t)
            ready = false
        }
    }

    /**
     * 在关键交互触发点播放点击音效。若总开关关闭则静默返回。
     * 默认按当前 [type] 播放, 也可显式传入 [override]。
     */
    fun playClick(override: SoundType? = null) {
        if (!_enabled.value) return
        val st = override ?: _type.value

        // CUSTOM type: play user-imported local audio file via MediaPlayer.
        if (st == SoundType.CUSTOM) {
            playCustomSound()
            return
        }

        val pool = soundPool ?: return
        val sid = soundIds[st] ?: return
        if (sid == 0) return
        // SoundPool volume: 0.0~1.0
        val v = (_volume.value.coerceIn(0, 100) / 100f)
        try {
            pool.play(sid, v, v, 1, 0, 1.0f)
        } catch (t: Throwable) {
            Log.w(TAG, "playClick: FAILED (ignored)", t)
        }
    }

    /** Play the user-imported custom sound file (if set). */
    private fun playCustomSound() {
        val uriStr = _customUri.value ?: run {
            Log.w(TAG, "playCustomSound: no custom URI set")
            return
        }
        try {
            customPlayer?.release()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, Uri.parse(uriStr))
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { it.release(); customPlayer = null }
            mp.setOnErrorListener { mpErr, _, _ ->
                Log.w(TAG, "playCustomSound: MediaPlayer error")
                mpErr.release(); customPlayer = null; true
            }
            mp.prepareAsync()
            customPlayer = mp
        } catch (t: Throwable) {
            Log.w(TAG, "playCustomSound: FAILED", t)
        }
    }

    /** 释放资源 (应用退出时调用, 单例场景一般不需要). */
    fun release() {
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        ready = false
        customPlayer?.release()
        customPlayer = null
    }

    // ====== PCM 合成 (无外部音频素材) ======

    private val sampleRate = 44100

    /**
     * 为每种 SoundType 合成一段 16-bit mono PCM, 封装成 WAV 字节流以便
     * SoundPool.load(byte[], offset, length, priority) 加载。
     *
     * 实现: 用 [AudioTrack] 共用的 AudioFormat 生成 short 数组, 再转
     * little-endian byte[] + 44 字节 WAV 头, 形成 .wav 完整字节流。
     */
    private fun synthesize(type: SoundType): ByteArray {
        val samples: ShortArray = when (type) {
            SoundType.WOOD_FISH -> synthWoodFish()
            SoundType.MECHANICAL -> synthMechanical()
            SoundType.BUBBLE -> synthBubble()
            SoundType.TICK -> synthTick()
            SoundType.CUSTOM -> synthWoodFish() // fallback: custom uses MediaPlayer, never reaches here
        }
        return encodeWav(samples, sampleRate)
    }

    /** 清脆木鱼: 200Hz 主 + 600Hz 谐波, 指数衰减 ~120ms. */
    private fun synthWoodFish(): ShortArray {
        val durMs = 120
        val n = sampleRate * durMs / 1000
        val out = ShortArray(n)
        val freq = 200.0
        val harm = 600.0
        val decay = 18.0 // 越大衰减越快
        var phase = 0.0
        val step = 2 * PI / sampleRate
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            val env = exp(-decay * t)
            val s = (0.6 * sin(phase * freq) + 0.3 * sin(phase * harm)) * env
            out[i] = (s * Short.MAX_VALUE * 0.85).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            phase += step
        }
        return out
    }

    /** 机械轴体: 1500Hz + 2400Hz, 极快衰减 ~50ms. */
    private fun synthMechanical(): ShortArray {
        val durMs = 50
        val n = sampleRate * durMs / 1000
        val out = ShortArray(n)
        val freq = 1500.0
        val harm = 2400.0
        val decay = 45.0
        var phase = 0.0
        val step = 2 * PI / sampleRate
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            val env = exp(-decay * t)
            val s = (0.5 * sin(phase * freq) + 0.45 * sin(phase * harm)) * env
            out[i] = (s * Short.MAX_VALUE * 0.8).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            phase += step
        }
        return out
    }

    /** 柔和气泡: 700Hz pop + 轻微扫频, ~180ms. */
    private fun synthBubble(): ShortArray {
        val durMs = 180
        val n = sampleRate * durMs / 1000
        val out = ShortArray(n)
        var phase = 0.0
        val step = 2 * PI / sampleRate
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            // 频率从 400 扫到 900, 模拟气泡上升
            val freq = 400.0 + 500.0 * (t / (durMs / 1000.0))
            val env = exp(-9.0 * t) * (1.0 - exp(-80.0 * t)) // attack-decay
            val s = sin(phase) * env * 0.7
            out[i] = (s * Short.MAX_VALUE * 0.85).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            phase += step * freq
        }
        return out
    }

    /** 经典滴答: 1000Hz 锐利 tick, 极短衰减 ~40ms. */
    private fun synthTick(): ShortArray {
        val durMs = 40
        val n = sampleRate * durMs / 1000
        val out = ShortArray(n)
        val freq = 1000.0
        val decay = 55.0
        var phase = 0.0
        val step = 2 * PI / sampleRate
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            val env = exp(-decay * t)
            val s = sin(phase * freq) * env * 0.75
            out[i] = (s * Short.MAX_VALUE * 0.9).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            phase += step
        }
        return out
    }

    /** 将 16-bit mono PCM short[] 封装成完整 .wav 字节流 (44 字节头 + PCM). */
    private fun encodeWav(samples: ShortArray, sr: Int): ByteArray {
        val byteRate = sr * 2 // mono * 16bit / 8
        val dataSize = samples.size * 2
        val baos = ByteArrayOutputStream(44 + dataSize)
        // RIFF header
        baos.write("RIFF".toByteArray())
        writeInt(baos, 36 + dataSize)
        baos.write("WAVE".toByteArray())
        // fmt chunk
        baos.write("fmt ".toByteArray())
        writeInt(baos, 16)          // PCM chunk size
        writeShort(baos, 1)         // audio format = PCM
        writeShort(baos, 1)         // mono
        writeInt(baos, sr)          // sample rate
        writeInt(baos, byteRate)    // byte rate
        writeShort(baos, 2)         // block align
        writeShort(baos, 16)        // bits per sample
        // data chunk
        baos.write("data".toByteArray())
        writeInt(baos, dataSize)
        val bb = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        baos.write(bb.array())
        return baos.toByteArray()
    }

    private fun writeInt(baos: ByteArrayOutputStream, v: Int) {
        baos.write(v and 0xFF)
        baos.write((v shr 8) and 0xFF)
        baos.write((v shr 16) and 0xFF)
        baos.write((v shr 24) and 0xFF)
    }

    private fun writeShort(baos: ByteArrayOutputStream, v: Int) {
        baos.write(v and 0xFF)
        baos.write((v shr 8) and 0xFF)
    }
}
