package dev.terrakok.cozyspace

import kuusisto.tinysound.Music
import kuusisto.tinysound.TinySound
import java.net.URI
import java.util.concurrent.Executors

actual fun createSoundPlayer(
    tracks: List<String>,
    volumes: List<Float>
): SoundPlayer = JavaSoundPlayer(tracks, volumes)

private class JavaSoundPlayer(
    private val tracks: List<String>,
    volumes: List<Float>
) : SoundPlayer {
    // All TinySound work (init + full OGG decode per track) runs on this
    // dedicated thread: it takes seconds and used to freeze the UI thread —
    // on Windows it even froze the OS cursor through the tray's WH_MOUSE_LL
    // hook. All state below is confined to this thread.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CozySound").apply { isDaemon = true }
    }
    private val volumes: MutableList<Float> = volumes.toMutableList()
    private val musics: Array<Music?> = arrayOfNulls(tracks.size)
    private var playing = false

    init {
        executor.execute {
            if (!TinySound.isInitialized()) TinySound.init()
            tracks.indices.forEach { index ->
                if (this.volumes[index] > 0f) loadMusic(index)
            }
        }
    }

    private fun loadMusic(index: Int): Music? {
        musics[index]?.let { return it }
        val url = URI.create(tracks[index]).toURL()
        return TinySound.loadMusic(url, true)?.also { music ->
            music.volume = volumes[index].toDouble()
            music.setLoop(true)
            if (playing) music.play(true)
            musics[index] = music
        }
    }

    private fun unloadMusic(index: Int) {
        musics[index]?.let { music ->
            music.stop()
            music.unload()
            musics[index] = null
        }
    }

    override fun updateVolume(index: Int, value: Float) {
        executor.execute {
            volumes[index] = value
            if (value > 0f) {
                loadMusic(index)?.volume = value.toDouble()
            } else {
                // Track is muted/disabled — free its memory.
                unloadMusic(index)
            }
        }
    }

    override fun play() {
        executor.execute {
            playing = true
            musics.forEach { it?.play(true) }
        }
    }

    override fun pause() {
        executor.execute {
            playing = false
            musics.forEach { it?.pause() }
        }
    }

    override fun shutdown() {
        executor.execute {
            musics.indices.forEach { unloadMusic(it) }
            TinySound.shutdown()
        }
        executor.shutdown()
    }
}
