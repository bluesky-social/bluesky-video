package expo.modules.blueskyvideo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import expo.modules.kotlin.AppContext

// Borrowed from https://github.com/expo/expo/blob/f81c18237c3cd5f0aa2b4db31fdf5b865281cb71/packages/expo-video/android/src/main/java/expo/modules/video/AudioFocusManager.kt
@UnstableApi
class AudioFocusManager(
    private val appContext: AppContext,
) : AudioManager.OnAudioFocusChangeListener {
    private var currentFocusRequest: AudioFocusRequest? = null

    private val audioManager by lazy {
        appContext.reactContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (currentFocusRequest != null) {
                return
            }

            val newFocusRequest =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                    setAudioAttributes(
                        AudioAttributes.Builder().run {
                            setUsage(AudioAttributes.USAGE_MEDIA)
                            setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            setOnAudioFocusChangeListener(this@AudioFocusManager)
                            build()
                        },
                    ).build()
                }
            currentFocusRequest = newFocusRequest
            audioManager?.requestAudioFocus(newFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    fun abandonAudioFocus() {
        currentFocusRequest?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager?.abandonAudioFocusRequest(it)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(this)
            }
        }
        currentFocusRequest = null
    }

    override fun onAudioFocusChange(focusChange: Int) {
        // Do nothing
    }
}
