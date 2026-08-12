package com.taskflow.app.ui.pomodoro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tracks for the Pomodoro background-music BottomSheet. Grouped by the three
 * preset tabs (自然音 / 氛围音乐 / 轻音乐). Each track is either a local
 * `res/raw` resource (looked up by name at runtime) or an online stream URL.
 *
 * Local raw resources are optional — if a named resource is missing the player
 * shows a friendly toast instead of crashing, so the build stays green without
 * bundling binary audio assets. Drop matching files under `res/raw/` to enable
 * offline playback for a given track.
 */
data class AudioTrack(
    val title: String,
    val category: AudioCategory,
    val source: AudioSource
)

sealed interface AudioSource {
    /** Play a `res/raw` resource by its file name (without extension). */
    data class Local(val rawName: String) : AudioSource
    /** Play a network audio stream URL. */
    data class Online(val url: String) : AudioSource
    /**
     * Play a user-imported local audio file (MP3, M4A, WAV, etc.) referenced by
     * a content:// URI. The caller is responsible for taking persistable read
     * permission on the URI before passing it here so playback survives process
     * death. See [AudioPlayerManager.playImported].
     */
    data class LocalFile(val uri: String, val displayName: String) : AudioSource
    /**
     * Synthesize a relaxing ambient sound at runtime using [AudioTrack].
     * Used as a guaranteed fallback when no local raw resource or online URL
     * is available — the user always hears something.
     */
    data class Synthesized(val type: SynthType) : AudioSource
}

/** Types of ambient sounds that can be synthesized at runtime. */
enum class SynthType(val label: String) {
    RAIN("雨声"),
    OCEAN("海浪"),
    TICK("滴答钟"),
    WHITE_NOISE("白噪音")
}

enum class AudioCategory(val label: String) {
    NATURE("自然音"),
    AMBIENT("氛围音乐"),
    LIGHT("轻音乐")
}

object AudioLibrary {
    val tracks: List<AudioTrack> = listOf(
        // 自然音 — 本地 raw 优先, 缺失时自动降级为 AudioTrack 合成
        AudioTrack("雨声", AudioCategory.NATURE, AudioSource.Local("rain_rain")),
        AudioTrack("滴答钟", AudioCategory.NATURE, AudioSource.Local("tick_clock")),
        AudioTrack("海浪", AudioCategory.NATURE, AudioSource.Local("ocean_waves")),
        // 氛围音乐
        AudioTrack("氛围流 (在线)", AudioCategory.AMBIENT, AudioSource.Online(
            "https://cdn.pixabay.com/audio/2022/03/15/audio_115b9eaf4e.mp3"
        )),
        AudioTrack("空灵空间", AudioCategory.AMBIENT, AudioSource.Local("ambient_space")),
        // 轻音乐
        AudioTrack("轻柔钢琴 (在线)", AudioCategory.LIGHT, AudioSource.Online(
            "https://cdn.pixabay.com/audio/2022/05/27/audio_1808fbf07a.mp3"
        )),
        AudioTrack("吉他小品", AudioCategory.LIGHT, AudioSource.Local("light_guitar"))
    )

    fun byCategory(category: AudioCategory): List<AudioTrack> =
        tracks.filter { it.category == category }
}

/**
 * Dual-source background music player for the Pomodoro screen.
 *
 * - [play] accepts any [AudioSource]; local raw resources are resolved via
 *   [android.content.res.Resources.getIdentifier], online URLs are streamed.
 * - Playback is looped (focus background music should be continuous).
 * - [isPlaying] / [currentTitle] are observable for Compose.
 *
 * Lifecycle: owned by the Pomodoro screen via `remember`. Call [release] on
 * disposal to free the native MediaPlayer.
 */
class AudioPlayerManager(private val context: Context) {

    var isPlaying by mutableStateOf(false)
        private set
    var currentTitle by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null
    private var synthTrack: android.media.AudioTrack? = null
    private var synthThread: Thread? = null
    @Volatile private var synthRunning = false

    fun play(track: AudioTrack) {
        releasePlayer()
        stopSynth()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.isLooping = true
            when (val src = track.source) {
                is AudioSource.Local -> {
                    val resId = context.resources.getIdentifier(src.rawName, "raw", context.packageName)
                    if (resId == 0) {
                        // ====== 保底机制: 本地 raw 缺失 → 自动降级为 AudioTrack 合成 ======
                        Log.w(TAG, "Local raw '${src.rawName}' not found → fallback to synthesized audio")
                        mp.release()
                        val synthType = mapRawNameToSynth(src.rawName)
                        startSynth(synthType, track.title)
                        return
                    }
                    val afd = context.resources.openRawResourceFd(resId)
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                is AudioSource.Online -> {
                    mp.setDataSource(context, Uri.parse(src.url))
                }
                is AudioSource.LocalFile -> {
                    // User-imported audio (content:// URI from file picker).
                    mp.setDataSource(context, Uri.parse(src.uri))
                }
                is AudioSource.Synthesized -> {
                    // Direct synthesis request — no MediaPlayer needed.
                    mp.release()
                    startSynth(src.type, track.title)
                    return
                }
            }
            mp.setOnPreparedListener {
                it.start()
                isPlaying = true
                currentTitle = track.title
            }
            mp.setOnErrorListener { _, _, _ ->
                Log.e(TAG, "MediaPlayer error → fallback to synthesized audio")
                Toast.makeText(context, "音源加载失败，已切换至合成白噪音", Toast.LENGTH_SHORT).show()
                mp.release()
                player = null
                // Fallback: synthesize white noise so the user always hears something.
                startSynth(SynthType.WHITE_NOISE, track.title)
                isPlaying = false
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            Log.e(TAG, "play() failed → fallback to synthesized audio", t)
            mp.release()
            // Final fallback: synthesize white noise.
            startSynth(SynthType.WHITE_NOISE, track.title)
        }
    }

    /**
     * Convenience for the "📁 自定义导入" picker entry: wrap a content:// URI +
     * display name into an [AudioSource.LocalFile] and start playback. The
     * caller should have already taken persistable read permission on [uri]
     * (via `ContentResolver.takePersistableUriPermission`) so the URI remains
     * usable after process death.
     */
    fun playImported(uri: Uri, displayName: String) {
        play(
            AudioTrack(
                title = displayName,
                category = AudioCategory.LIGHT, // imported tracks land in 轻音乐 tab
                source = AudioSource.LocalFile(uri.toString(), displayName)
            )
        )
    }

    fun pause() {
        player?.let {
            if (it.isPlaying) it.pause()
            isPlaying = false
        }
    }

    fun resume() {
        player?.let {
            if (!it.isPlaying) {
                it.start()
                isPlaying = true
            }
        }
    }

    fun stop() {
        releasePlayer()
        stopSynth()
        isPlaying = false
        currentTitle = null
    }

    private fun releasePlayer() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Throwable) {
            }
            it.release()
        }
        player = null
    }

    fun release() {
        releasePlayer()
        stopSynth()
    }

    // ====== AudioTrack 合成保底 (spec: 绝不直接报错静音) ======

    private val TAG = "AudioPlayerManager"

    /** Map a missing raw resource name to the closest synth type. */
    private fun mapRawNameToSynth(rawName: String): SynthType = when {
        rawName.contains("rain") -> SynthType.RAIN
        rawName.contains("ocean") || rawName.contains("wave") -> SynthType.OCEAN
        rawName.contains("tick") || rawName.contains("clock") -> SynthType.TICK
        else -> SynthType.WHITE_NOISE
    }

    /**
     * Start a background thread that continuously synthesizes ambient audio
     * via [AudioTrack]. The user always hears something — no silent failure.
     */
    private fun startSynth(type: SynthType, title: String) {
        synthRunning = true
        currentTitle = title
        isPlaying = true
        synthThread = Thread {
            try {
                val sampleRate = 44100
                val bufferSize = android.media.AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
                synthTrack = track
                track.play()
                val chunkSize = bufferSize / 2 // samples
                val buffer = ShortArray(chunkSize)
                var phase = 0.0
                val step = 2.0 * PI / sampleRate
                val rng = java.util.Random(42)
                while (synthRunning) {
                    when (type) {
                        SynthType.RAIN -> {
                            // Rain: filtered white noise with occasional drops
                            for (i in 0 until chunkSize) {
                                val noise = rng.nextGaussian() * 0.15
                                val drop = if (rng.nextInt(200) == 0) 0.5 else 0.0
                                val s = (noise + drop) * Short.MAX_VALUE * 0.5
                                buffer[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }
                        SynthType.OCEAN -> {
                            // Ocean waves: low-freq amplitude modulation of noise
                            for (i in 0 until chunkSize) {
                                val t = phase
                                val wave = (0.5 + 0.5 * sin(t * 0.5)).toFloat()
                                val noise = rng.nextGaussian() * 0.2
                                val s = (noise * wave) * Short.MAX_VALUE * 0.6
                                buffer[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                phase += step
                            }
                        }
                        SynthType.TICK -> {
                            // Tick-tock: 1kHz beep every 0.5s
                            for (i in 0 until chunkSize) {
                                val sampleIdx = i + (phase / step).toInt()
                                val cycle = sampleIdx % (sampleRate / 2) // 0.5s period
                                val s = if (cycle < sampleRate / 50) {
                                    // First 20ms: tick
                                    sin(phase * 1000.0) * 0.4 * Short.MAX_VALUE
                                } else 0.0
                                buffer[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                phase += step
                            }
                        }
                        SynthType.WHITE_NOISE -> {
                            // Soft white noise: gentle and continuous
                            for (i in 0 until chunkSize) {
                                val noise = rng.nextGaussian() * 0.2
                                val s = noise * Short.MAX_VALUE * 0.4
                                buffer[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }
                    }
                    track.write(buffer, 0, chunkSize)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startSynth: FAILED for $type", t)
            }
        }.also { it.start() }
    }

    private fun stopSynth() {
        synthRunning = false
        try { synthThread?.join(500) } catch (_: Throwable) {}
        synthThread = null
        try { synthTrack?.stop() } catch (_: Throwable) {}
        try { synthTrack?.release() } catch (_: Throwable) {}
        synthTrack = null
    }
}
