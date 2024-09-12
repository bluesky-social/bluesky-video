package expo.modules.blueskyvideo

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@UnstableApi
class BlueskyVideoView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val playerScope = CoroutineScope(Job() + Dispatchers.Main)
  private var player: ExoPlayer? = null
  private var playerView: PlayerView? = null

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

  var isViewActive: Boolean = false
    set(value) {
      field = value
      onActiveChange(mapOf(
        "isActive" to value
      ))
    }

  val onStatusChange by EventDispatcher()
  val onLoadingChange by EventDispatcher()
  val onActiveChange by EventDispatcher()
  val onTimeRemainingChange by EventDispatcher()
  val onMutedChange by EventDispatcher()
  val onError by EventDispatcher()

  fun playVideo() {
    if (url == null || player != null) {
      return
    }

    val player = PlayerManager(context).dequeuePlayer()
    val playerView = PlayerView(context)
    playerView.setShowSubtitleButton(true)
    playerView.setShowNextButton(false)
    playerView.setShowPreviousButton(false)
    this.addView(playerView)

    playerScope.launch {
      val mediaItem = createMediaItem()
      player.setMediaItem(mediaItem)
      player.prepare()

      playerView.player = player
      player.playWhenReady = autoplay
    }

    this.player = player
    this.playerView = playerView
  }

  fun removeVideo() {
    val player = this.player ?: return
    val playerView = this.playerView ?: return

    this.mute()
    this.pause()
    this.isLoading = true

    playerScope.launch {
      player.stop()
      player.release()
      PlayerManager(context).recyclePlayer(player)
      this@BlueskyVideoView.removeView(playerView)
      this@BlueskyVideoView.player = null
      this@BlueskyVideoView.playerView = null
      this@BlueskyVideoView.isLoading = false
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    ViewManager().addView(this)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    ViewManager().removeView(this)
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
//      val intent = Intent(context, FullscreenPlayerActivity::class.java)
//      intent.putExtra(VideoManager.INTENT_PLAYER_KEY, id)
//      currentActivity.startActivity(intent)
//
//      // Disable the enter transition
//      if (Build.VERSION.SDK_INT >= 34) {
//        currentActivity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
//      } else {
//        @Suppress("DEPRECATION")
//        currentActivity.overridePendingTransition(0, 0)
//      }
//      onFullscreenEnter(Unit)
//      isInFullscreen = true
  }

  // Visibility

  fun setIsCurrentlyActive(isActive: Boolean): Boolean {
    if (this.isFullscreen) {
      return false
    }

    this.isViewActive = isActive
    if (isActive) {
      this.playVideo()
    } else {
      this.removeVideo()
    }
    return true
  }

  fun getPositionOnScreen(): Rect? {
    if (!this.isShown) {
      return null
    }

    val screenPosition = intArrayOf(0, 0)
    this.getLocationInWindow(screenPosition)
    return Rect(
      screenPosition[0],
      screenPosition[1],
      screenPosition[0] + this.width,
      screenPosition[1] + this.height,
    )
  }

  fun isViewableEnough(): Boolean {
    val positionOnScreen = this.getPositionOnScreen() ?: return false
    val visibleArea = positionOnScreen.width() * positionOnScreen.height()
    val totalArea = this.width * this.height
    return visibleArea >= 0.5 * totalArea
  }

  suspend fun createMediaItem(): MediaItem {
    return withContext(Dispatchers.IO) {
      MediaItem.Builder()
        .setUri(url.toString())
        .build()
    }
  }
}
