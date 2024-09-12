package expo.modules.blueskyvideo

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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

  private var isFullscreen: Boolean = false
    set(value) {
      field = value
      if (value) {
        this.playerView.useController = true
        this.playerView.player?.play()
      } else {
        this.playerView.useController = false
      }
    }

  private var isPlaying: Boolean = false
    set(value) {
      field = value
      onStatusChange(mapOf(
        "status" to if (value) "playing" else "paused"
      ))
    }

  private var isMuted: Boolean = false
    set(value) {
      field = value
      onMutedChange(mapOf(
        "isMuted" to value
      ))
    }

  private var isLoading: Boolean = false
    set(value) {
      field = value
      onLoadingChange(mapOf(
        "isLoading" to value
      ))
    }

  private var isViewActive: Boolean = false
    set(value) {
      field = value
      onActiveChange(mapOf(
        "isActive" to value
      ))
    }

  private val onStatusChange by EventDispatcher()
  private val onLoadingChange by EventDispatcher()
  private val onActiveChange by EventDispatcher()
  private val onTimeRemainingChange by EventDispatcher()
  private val onMutedChange by EventDispatcher()
  private val onError by EventDispatcher()
  private val onPlayerPress by EventDispatcher()

  init {
    val playerView = PlayerView(context).apply {
      setShowSubtitleButton(true)
      setShowNextButton(true)
      setShowPreviousButton(true)
      setBackgroundColor(Color.BLACK)
      resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
      useController = false
      setOnClickListener { _ ->
        onPlayerPress(mapOf())
      }
    }
    this.addView(
      playerView,
      ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    )
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
      val mediaItem = createMediaItem()
      player.setMediaItem(mediaItem)
      player.prepare()
    }
  }

  private fun removeVideo() {
    val player = this.playerView.player ?: return

    this.mute()
    this.pause()
    this.isLoading = true

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
    this.addProgressTracker()
    this.playerView.player?.play()
    this.isPlaying = true
  }

  private fun pause() {
    this.removeProgressTracker()
    this.playerView.player?.pause()
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
    this.playerView.player?.volume = 0f
    this.isMuted = true
  }

  private fun unmute() {
    this.playerView.player?.volume = 1f
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
        isMuted = true
      }
      .build().apply {
        repeatMode = ExoPlayer.REPEAT_MODE_ALL
        addListener(object : Player.Listener {
          override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
              ExoPlayer.STATE_READY -> {
                if (this@BlueskyVideoView.autoplay) {
                  this@BlueskyVideoView.isLoading = false
                  this@BlueskyVideoView.play()
                }
              }
            }
          }
        })
      }
  }

  private fun addProgressTracker() {
    val player = this.playerView.player ?: return
    this.progressTracker = ProgressTracker(player, onTimeRemainingChange)
  }

  private fun removeProgressTracker() {
    this.progressTracker?.remove()
    this.progressTracker = null
  }
}
