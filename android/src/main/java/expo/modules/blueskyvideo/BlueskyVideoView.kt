package expo.modules.blueskyvideo

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import expo.modules.video.ProgressTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@UnstableApi
class BlueskyVideoView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val playerScope = CoroutineScope(Job() + Dispatchers.Main)
  private val playerView: PlayerView
  private var progressTracker: ProgressTracker? = null

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

  init {
    val playerView = PlayerView(context).apply {
      setShowSubtitleButton(true)
      setShowNextButton(true)
      setShowPreviousButton(true)
      resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
      useController = true
      layoutParams = ViewGroup.LayoutParams(
        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.BLACK)
    }
    this.addView(playerView)
    this.playerView = playerView
  }

  // Lifecycle

  private fun playVideo() {
    if (this.playerView.player != null) {
      return
    }

    val player = this.createExoPlayer()
    this.playerView.player = player

    playerScope.launch {
      Log.d("BlueskyVideoView", "Creating media item")
      val mediaItem = createMediaItem()
      player.setMediaItem(mediaItem)
      player.prepare()
      Log.d("BlueskyVideoView", "Player prepared")
      player.playWhenReady = autoplay
    }

    this.progressTracker = ProgressTracker(player, onTimeRemainingChange)
  }

  private fun removeVideo() {
    val player = this.playerView.player ?: return

    this.mute()
    this.pause()
    this.isLoading = true

    this.progressTracker?.remove()
    this.progressTracker = null

    player.release()
    this.playerView.player = null
    this.isLoading = false
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    ViewManager.addView(this)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    ViewManager.removeView(this)
  }

  // Controls

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

  private suspend fun createMediaItem(): MediaItem {
    return withContext(Dispatchers.IO) {
      MediaItem.Builder()
        .setUri(url.toString())
        .build()
    }
  }

  private fun createExoPlayer(): ExoPlayer {
    return ExoPlayer.Builder(context)
      .apply {
        setLooper(context.mainLooper)
        setSeekForwardIncrementMs(5000)
        setSeekBackIncrementMs(5000)
      }
      .build().apply {
        repeatMode = ExoPlayer.REPEAT_MODE_ALL
      }
  }
}
