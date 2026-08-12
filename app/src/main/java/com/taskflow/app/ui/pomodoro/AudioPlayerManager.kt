package com.taskflow.app.ui.pomodoro

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
}

enum class AudioCategory(val label: String) {
    NATURE("自然音"),
    AMBIENT("氛围音乐"),
    LIGHT("轻音乐")
}

object AudioLibrary {
    val tracks: List<AudioTrack> = listOf(
        // 自然音
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
            when (val src = track.source) {
                is AudioSource.Local -> {
                    val resId = context.resources.getIdentifier(src.rawName, "raw", context.packageName)
                    if (resId == 0) {
                        Toast.makeText(
                            context,
                            "暂无本地音源「${track.title}」，请将 ${src.rawName}.mp3 放入 res/raw",
                            Toast.LENGTH_LONG
                        ).show()
                        mp.release()
                        return
                    }
                    val afd = context.resources.openRawResourceFd(resId)
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                is AudioSource.Online -> {
                    mp.setDataSource(context, Uri.parse(src.url))
                }
            }
            mp.setOnPreparedListener {
                it.start()
                isPlaying = true
                currentTitle = track.title
            }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(context, "音频播放失败，请检查网络或音源", Toast.LENGTH_SHORT).show()
                isPlaying = false
                currentTitle = null
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            Toast.makeText(context, "无法播放「${track.title}」", Toast.LENGTH_SHORT).show()
            mp.release()
            isPlaying = false
        }
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
}
