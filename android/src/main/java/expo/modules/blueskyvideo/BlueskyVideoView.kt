package expo.modules.blueskyvideo

import android.content.Context
import android.net.Uri
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView

class BlueskyVideoView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  var url: Uri? = null

  var autoplay = false

  var isFullscreen: Boolean = false
    set(value) {

    }

  var isPlaying: Boolean = false
    set(value) {
      field = value
      onStatusChange(mapOf(
        "isPlaying" to value
      ))
    }

  var isMuted: Boolean = false
    set(value) {
      field = value
      onMutedChange(mapOf(
        "isMuted" to value
      ))
    }

  var isLoading: Boolean = false
    set(value) {
      field = value
      onLoadingChange(mapOf(
        "isLoading" to value
      ))
    }

  val onStatusChange by EventDispatcher()
  val onLoadingChange by EventDispatcher()
  val onActiveChange by EventDispatcher()
  val onTimeRemainingChange by EventDispatcher()
  val onMutedChange by EventDispatcher()
  val onError by EventDispatcher()

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
  }

  private fun play() {
    this.isPlaying = true
  }

  private fun pause() {
    this.isPlaying = false
  }

  fun togglePlayback() {
    if (this.isPlaying) {
      pause()
    } else {
      play()
    }
  }

  private fun mute() {
    this.isMuted = true
  }

  private fun unmute() {
    this.isMuted = false
  }

  fun toggleMuted() {
    if (this.isMuted) {
      unmute()
    } else {
      mute()
    }
  }

  fun enterFullscreen() {

  }
}
