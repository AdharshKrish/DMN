package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioPlaybackState(
    val activeAudioName: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

class DmnAudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(AudioPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    fun togglePlay(scope: CoroutineScope, audioName: String, uri: Uri) {
        val current = _playbackState.value
        if (current.activeAudioName == audioName && mediaPlayer != null) {
            if (current.isPlaying) {
                mediaPlayer?.pause()
                _playbackState.value = current.copy(isPlaying = false)
            } else {
                mediaPlayer?.start()
                _playbackState.value = current.copy(isPlaying = true)
            }
            return
        }

        stop()
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                start()
            }
            mediaPlayer = mp
            _playbackState.value = AudioPlaybackState(
                activeAudioName = audioName,
                isPlaying = true,
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(0)
            )
            mp.setOnCompletionListener {
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    positionMs = 0
                )
            }
            progressJob = scope.launch(Dispatchers.Main) {
                while (isActive && mediaPlayer != null) {
                    mediaPlayer?.let { player ->
                        if (player.isPlaying) {
                            _playbackState.value = _playbackState.value.copy(
                                positionMs = player.currentPosition,
                                durationMs = player.duration.coerceAtLeast(1)
                            )
                        }
                    }
                    delay(250L)
                }
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        mediaPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        _playbackState.value = AudioPlaybackState()
    }
}
