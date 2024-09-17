package expo.modules.blueskyvideo

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

@UnstableApi
class BlueskyVideoView(
    context: Context,
    appContext: AppContext,
) : ExpoView(context, appContext) {
    private var playerScope: CoroutineScope? = null

    private val playerView: PlayerView
    var player: ExoPlayer? = null

    private var progressTracker: ProgressTracker? = null

    var url: Uri? = null
    var autoplay = false
    var beginMuted = true
    var ignoreAutoplay = false

    private var isFullscreen: Boolean = false
        set(value) {
            field = value
            this.playerView.useController = value
        }

    private var isPlaying: Boolean = false
        set(value) {
            field = value
            onStatusChange(
                mapOf(
                    "status" to if (value) "playing" else "paused",
                ),
            )
        }

    private var isMuted: Boolean = true
        set(value) {
            field = value
            onMutedChange(
                mapOf(
                    "isMuted" to value,
                ),
            )
        }

    private var isLoading: Boolean = false
        set(value) {
            field = value
            onLoadingChange(
                mapOf(
                    "isLoading" to value,
                ),
            )
        }

    private var isViewActive: Boolean = false
        set(value) {
            field = value
            onActiveChange(
                mapOf(
                    "isActive" to value,
                ),
            )
        }

    var forceTakeover: Boolean = false
        set(value) {
            if (value == field) {
                return
            }

            field = value
            if (value) {
                ViewManager.setActiveView(this)
            }
        }

    private val onActiveChange by EventDispatcher()
    private val onLoadingChange by EventDispatcher()
    private val onMutedChange by EventDispatcher()
    private val onPlayerPress by EventDispatcher()
    private val onStatusChange by EventDispatcher()
    private val onTimeRemainingChange by EventDispatcher()
    private val onError by EventDispatcher()

    private var enteredFullscreenMuteState = true

    init {
        val playerView =
            PlayerView(context).apply {
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
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        this.playerView = playerView
    }

    // Lifecycle

    private fun setup() {
        // We shouldn't encounter this scenario, but would rather be safe than sorry here and just
        // skip setup if we do.
        if (this.player != null) {
            return
        }


        this.isLoading = true

        val player = this.createExoPlayer()
        this.player = player
        this.playerView.player = player

        val playerScope = CoroutineScope(Job() + Dispatchers.Main)
        playerScope.launch {
            val mediaItem = createMediaItem()
            player.setMediaItem(mediaItem)
            player.prepare()
        }
        this.playerScope = playerScope
    }

    private fun destroy() {
        val player = this.player ?: return

        this.isLoading = false
        this.ignoreAutoplay = false

        this.pause()

        player.release()
        this.player = null
        this.playerView.player = null
        this.playerScope?.cancel()
        this.playerScope = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewManager.addView(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ViewManager.removeView(this)
        this.destroy()
    }

    // Controls

    private fun play() {
        this.addProgressTracker()
        this.player?.play()
        this.isPlaying = true
    }

    private fun pause() {
        this.player?.pause()
        this.removeProgressTracker()
        this.isPlaying = false
    }

    fun togglePlayback() {
        if (this.isPlaying) {
            this.pause()
        } else {
            if (this.player == null) {
                ViewManager.setActiveView(this)
                this.ignoreAutoplay = true
                this.setup()
            } else {
                this.play()
            }
        }
    }

    private fun mute() {
        this.player?.volume = 0f
        this.isMuted = true
        AudioFocusManager(appContext).abandonAudioFocus()
    }

    private fun unmute() {
        AudioFocusManager(appContext).requestAudioFocus()
        this.player?.volume = 1f
        this.isMuted = false
    }

    fun toggleMuted() {
        if (this.isMuted) {
            this.unmute()
        } else {
            this.mute()
        }
    }

    // Fullscreen handling

    fun enterFullscreen() {
        val currentActivity = this.appContext.currentActivity ?: return

        this.enteredFullscreenMuteState = this.isMuted

        // We always want to start with unmuted state and playing. Fire those from here so the
        // event dispatcher gets called
        this.unmute()
        if (!this.isPlaying) {
            this.play()
        }

        // Remove the player from this view, but don't null the player!
        this.playerView.player = null

        // create the intent and give it a view
        val intent = Intent(context, FullscreenActivity::class.java)
        FullscreenActivity.asscVideoView = WeakReference(this)

        // fire the fullscreen event and launch the intent
        this.isFullscreen = true
        currentActivity.startActivity(intent)
    }

    fun onExitFullscreen() {
        this.isFullscreen = false
        if (this.enteredFullscreenMuteState) {
            this.mute()
        }
        if (this.autoplay) {
            this.play()
        } else {
            this.pause()
        }
        this.playerView.player = this.player
    }

    // Visibility

    fun setIsCurrentlyActive(isActive: Boolean): Boolean {
        if (this.isFullscreen) {
            return false
        }

        this.isViewActive = isActive
        if (isActive) {
            if (this.autoplay || this.forceTakeover) {
                this.setup()
            }
        } else {
            this.destroy()
        }
        return true
    }

    // Visibility

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

    // Setup helpers

    private suspend fun createMediaItem(): MediaItem =
        withContext(Dispatchers.IO) {
            MediaItem
                .Builder()
                .setUri(url.toString())
                .build()
        }

    private fun createExoPlayer(): ExoPlayer =
        ExoPlayer
            .Builder(context)
            .apply {
                setLooper(context.mainLooper)
                setSeekForwardIncrementMs(5000)
                setSeekBackIncrementMs(5000)
            }.build()
            .apply {
                repeatMode = ExoPlayer.REPEAT_MODE_ALL
                playWhenReady = false
                volume = 0f
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            super.onPlaybackStateChanged(playbackState)
                            when (playbackState) {
                                ExoPlayer.STATE_READY -> {
                                    val view = this@BlueskyVideoView
                                    if (view.autoplay || view.ignoreAutoplay) {
                                        view.isLoading = false
                                        view.play()
                                        if (!view.beginMuted) {
                                            view.unmute()
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }

    private fun addProgressTracker() {
        val player = this.playerView.player ?: return
        this.progressTracker = ProgressTracker(player, onTimeRemainingChange)
    }

    private fun removeProgressTracker() {
        this.progressTracker?.remove()
        this.progressTracker = null
    }

    // Layout Hack
    // Code is borrowed from expo-video, which borrows it from react native...
    // https://github.com/expo/expo/blob/f81c18237c3cd5f0aa2b4db31fdf5b865281cb71/packages/expo-video/android/src/main/java/expo/modules/video/VideoView.kt#L125
    // https://github.com/facebook/react-native/blob/d19afc73f5048f81656d0b4424232ce6d69a6368/ReactAndroid/src/main/java/com/facebook/react/views/toolbar/ReactToolbar.java#L166
    //
    // Essentially, the resize mode does not work without this hack, and will sometimes simply show
    // the video in a poorly scaled way.
    private val mLayoutRunnable = Runnable {
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        layout(left, top, right, bottom)
    }

    override fun requestLayout() {
        super.requestLayout()
        post(mLayoutRunnable)
    }
}
