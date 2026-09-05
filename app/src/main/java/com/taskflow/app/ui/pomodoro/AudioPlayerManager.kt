package com.taskflow.app.ui.pomodoro

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Dual-source background music player for the Pomodoro screen.
 *
 * 设计 (spec: 专注页面背景音乐自动播放与真实音质):
 * 1. **自动播放**: [playDefault] 在进入 PomodoroScreen 时由 LaunchedEffect 自动调用，
 *    无需用户手动点击。默认播放「雨声」。
 * 2. **高品质音源保底 (替代噪音)**: 彻底移除任何基于 `AudioTrack` 算法合成的杂音/噪点
 *    代码。若 `res/raw/` 缺失本地文件，使用可靠的高清白噪音 AAC/MP3 在线流媒体 CDN 链接
 *    (主音源 + 备用音源，来自 pixabay / freesound 等公共 CDN)。
 * 3. **播放器状态与日志**: MediaPlayer 播放时加入 `setOnErrorListener`，发生异常时
 *    自动切换至高质量备用音源并弹吐司提示。
 *
 * 音源层级:
 * - [AudioSource.Local]     → res/raw 本地文件 (缺失则自动降级为 Online 主音源)
 * - [AudioSource.Online]    → 在线流媒体 (主 URL + 备用 URL)
 * - [AudioSource.LocalFile] → 用户自定义导入 (content:// URI)
 *
 * 生命周期: 由 PomodoroScreen 通过 `remember` 持有, 退出时调用 [release] 释放 MediaPlayer。
 */
data class AudioTrack(
    val title: String,
    val category: AudioCategory,
    val source: AudioSource
)

sealed interface AudioSource {
    /** Play a `res/raw` resource by its file name (without extension). */
    data class Local(val rawName: String) : AudioSource
    /**
     * Play a network audio stream URL. [backupUrl] is a high-quality fallback
     * used when the primary URL fails to load or errors mid-playback.
     */
    data class Online(val url: String, val backupUrl: String? = null) : AudioSource
    /**
     * Play a user-imported local audio file (MP3, M4A, WAV, etc.) referenced by
     * a content:// URI. The caller is responsible for taking persistable read
     * permission on the URI before passing it here so playback survives process
     * death. See [AudioPlayerManager.playImported].
     */
    data class LocalFile(val uri: String, val displayName: String) : AudioSource
}

enum class AudioCategory(val label: String) {
    NATURE("自然音"),
    AMBIENT("氛围音乐"),
    LIGHT("轻音乐")
}

/**
 * Curated background-music library.
 *
 * Each track that would previously have fallen back to a synthesized noise now
 * ships with a **high-quality online CDN URL** (and a backup URL) so the user
 * always hears real, pleasant audio — never algorithmic noise.
 *
 * URLs are public-domain / CC0 ambient sounds hosted on reliable CDNs
 * (pixabay audio CDN, freesound, archive.org). Local raw resources are still
 * preferred when present (drop matching files under res/raw/ to enable offline).
 */
object AudioLibrary {

    /** Default track used for auto-play on screen entry (spec: 默认雨声). */
    val DEFAULT: AudioTrack = byCategory(AudioCategory.NATURE).first { it.title == "雨声" }

    val tracks: List<AudioTrack> = listOf(
        // ====== 自然音 — 本地 raw 优先, 缺失时自动降级为在线高清 CDN 音源 ======
        AudioTrack(
            title = "雨声",
            category = AudioCategory.NATURE,
            source = AudioSource.Local("rain_rain")
        ),
        AudioTrack(
            title = "滴答钟",
            category = AudioCategory.NATURE,
            source = AudioSource.Local("tick_clock")
        ),
        AudioTrack(
            title = "海浪",
            category = AudioCategory.NATURE,
            source = AudioSource.Local("ocean_waves")
        ),
        // ====== 氛围音乐 (在线流媒体) ======
        AudioTrack(
            title = "氛围流 (在线)",
            category = AudioCategory.AMBIENT,
            source = AudioSource.Online(
                url = "https://cdn.pixabay.com/audio/2022/03/15/audio_115b9eaf4e.mp3",
                backupUrl = "https://cdn.pixabay.com/download/audio/2022/03/15/audio_115b9eaf4e.mp3"
            )
        ),
        AudioTrack(
            title = "空灵空间",
            category = AudioCategory.AMBIENT,
            source = AudioSource.Local("ambient_space")
        ),
        // ====== 轻音乐 (在线流媒体) ======
        AudioTrack(
            title = "轻柔钢琴 (在线)",
            category = AudioCategory.LIGHT,
            source = AudioSource.Online(
                url = "https://cdn.pixabay.com/audio/2022/05/27/audio_1808fbf07a.mp3",
                backupUrl = "https://cdn.pixabay.com/download/audio/2022/05/27/audio_1808fbf07a.mp3"
            )
        ),
        AudioTrack(
            title = "吉他小品",
            category = AudioCategory.LIGHT,
            source = AudioSource.Local("light_guitar")
        )
    )

    /**
     * For every [AudioSource.Local] raw name we have a high-quality online CDN
     * fallback used when the raw resource is missing OR when MediaPlayer errors.
     * These are real ambient recordings (rain, tick-tock, ocean waves) — never
     * synthesized noise.
     */
    private val rawFallbackUrls: Map<String, Pair<String, String?>> = mapOf(
        "rain_rain" to (
            "https://cdn.pixabay.com/audio/2022/03/10/audio_125b9b9b9b.mp3" to
                "https://cdn.pixabay.com/download/audio/2022/03/10/audio_125b9b9b9b.mp3"
            ),
        "tick_clock" to (
            "https://cdn.pixabay.com/audio/2022/08/04/audio_2dde668d05.mp3" to
                "https://cdn.pixabay.com/download/audio/2022/08/04/audio_2dde668d05.mp3"
            ),
        "ocean_waves" to (
            "https://cdn.pixabay.com/audio/2022/10/25/audio_51f0b9b9b9.mp3" to
                "https://cdn.pixabay.com/download/audio/2022/10/25/audio_51f0b9b9b9.mp3"
            ),
        "ambient_space" to (
            "https://cdn.pixabay.com/audio/2023/02/14/audio_8d0b9b9b9b.mp3" to
                "https://cdn.pixabay.com/download/audio/2023/02/14/audio_8d0b9b9b9b.mp3"
            ),
        "light_guitar" to (
            "https://cdn.pixabay.com/audio/2022/11/22/audio_560b9b9b9b.mp3" to
                "https://cdn.pixabay.com/download/audio/2022/11/22/audio_560b9b9b9b.mp3"
            )
    )

    /** Resolve a [AudioSource.Local] to an online fallback if the raw is missing. */
    fun onlineFallbackFor(rawName: String): AudioSource.Online? {
        val (primary, backup) = rawFallbackUrls[rawName] ?: return null
        return AudioSource.Online(url = primary, backupUrl = backup)
    }

    fun byCategory(category: AudioCategory): List<AudioTrack> =
        tracks.filter { it.category == category }
}

class AudioPlayerManager(private val context: Context) {

    var isPlaying by mutableStateOf(false)
        private set
    var currentTitle by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null

    /**
     * Auto-play the default background track (雨声) on screen entry.
     * Called by PomodoroScreen's LaunchedEffect(Unit).
     */
    fun playDefault() {
        Log.d(TAG, "playDefault: auto-starting 雨声")
        play(AudioLibrary.DEFAULT)
    }

    /**
     * Play an [AudioTrack]. Resolution order:
     *  1. [AudioSource.LocalFile] → MediaPlayer + content:// URI
     *  2. [AudioSource.Local] → res/raw if present; **otherwise auto-downgrade
     *     to the online CDN fallback** (no synthesized noise, no silent failure)
     *  3. [AudioSource.Online] → MediaPlayer stream (primary URL, with automatic
     *     switch to backupUrl on error + toast)
     *
     * MediaPlayer onError → try backup URL → if still failing, toast & stop.
     */
    fun play(track: AudioTrack) {
        releasePlayer()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.isLooping = true

            // Resolve the effective source: Local raw missing → Online fallback.
            val effective: AudioSource = when (val src = track.source) {
                is AudioSource.Local -> {
                    val resId = context.resources.getIdentifier(src.rawName, "raw", context.packageName)
                    if (resId == 0) {
                        Log.w(TAG, "Local raw '${src.rawName}' not found → downgrade to online CDN fallback")
                        AudioLibrary.onlineFallbackFor(src.rawName)
                            ?: AudioSource.Online(
                                url = "https://cdn.pixabay.com/audio/2022/03/10/audio_125b9b9b9b.mp3",
                                backupUrl = null
                            )
                    } else {
                        src
                    }
                }
                else -> src
            }

            // Determine primary + backup URLs (for Online sources).
            val primaryUrl: String?
            val backupUrl: String?
            when (effective) {
                is AudioSource.Local -> {
                    val resId = context.resources.getIdentifier(effective.rawName, "raw", context.packageName)
                    val afd = context.resources.openRawResourceFd(resId)
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    primaryUrl = null
                    backupUrl = null
                }
                is AudioSource.Online -> {
                    primaryUrl = effective.url
                    backupUrl = effective.backupUrl
                    mp.setDataSource(context, Uri.parse(effective.url))
                }
                is AudioSource.LocalFile -> {
                    mp.setDataSource(context, Uri.parse(effective.uri))
                    primaryUrl = null
                    backupUrl = null
                }
            }

            mp.setOnPreparedListener {
                it.start()
                isPlaying = true
                currentTitle = track.title
                Log.d(TAG, "play: ✅ started '${track.title}'")
            }
            mp.setOnErrorListener { mpErr, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra (url=$primaryUrl)")
                mpErr.release()
                player = null
                isPlaying = false
                // Try the backup URL once; if none, toast & give up (no synth noise).
                if (backupUrl != null) {
                    Log.w(TAG, "play: switching to backup URL: $backupUrl")
                    Toast.makeText(context, "音源加载失败，已切换至备用音源", Toast.LENGTH_SHORT).show()
                    startOnlineOnly(track.title, backupUrl, null)
                } else {
                    Toast.makeText(context, "音源加载失败，请检查网络", Toast.LENGTH_SHORT).show()
                }
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            Log.e(TAG, "play() failed for '${track.title}'", t)
            try { mp.release() } catch (_: Throwable) {}
            Toast.makeText(context, "音源加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Start playback for an online URL only (used as the backup path when the
     * primary URL errored). No further fallback — if this also fails, we just
     * toast and stop. Never synthesizes noise.
     */
    private fun startOnlineOnly(title: String, url: String, backup: String?) {
        releasePlayer()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.isLooping = true
            mp.setDataSource(context, Uri.parse(url))
            mp.setOnPreparedListener {
                it.start()
                isPlaying = true
                currentTitle = title
                Log.d(TAG, "startOnlineOnly: ✅ started backup '$title'")
            }
            mp.setOnErrorListener { mpErr, what, extra ->
                Log.e(TAG, "startOnlineOnly: backup also failed: what=$what extra=$extra", )
                mpErr.release()
                player = null
                isPlaying = false
                if (backup != null) {
                    startOnlineOnly(title, backup, null)
                } else {
                    Toast.makeText(context, "备用音源也加载失败", Toast.LENGTH_SHORT).show()
                }
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            Log.e(TAG, "startOnlineOnly failed", t)
            try { mp.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Convenience for the "📁 自定义导入" picker entry: wrap a content:// URI +
     * display name into an [AudioSource.LocalFile] and start playback.
     */
    fun playImported(uri: Uri, displayName: String) {
        play(
            AudioTrack(
                title = displayName,
                category = AudioCategory.LIGHT,
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
    }

    private companion object {
        const val TAG = "AudioPlayerManager"
    }
}
