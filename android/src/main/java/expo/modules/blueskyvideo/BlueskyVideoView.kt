package expo.modules.blueskyvideo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
    private val posterView: ImageView
    var player: ExoPlayer? = null

    private var posterHideListener: Player.Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var progressTracker: ProgressTracker? = null
    private var isRecoveringFromBackground = false
    private var savedPosition: Long = 0L

    var url: Uri? = null
    var autoplay = false
    var beginMuted = true
    var ignoreAutoplay = false

    var isFullscreen: Boolean = false
        private set(value) {
            field = value
            this.playerView.useController = value

            onFullscreenChange(
                mapOf(
                    "isFullscreen" to value,
                )
            )
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
    private val onFullscreenChange by EventDispatcher()
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

        // Poster sits on top of the playerView. During the enter transition we
        // capture the inline frame and show it here so the inline view isn't a
        // black rectangle behind the (possibly translucent) fullscreen morph.
        // CENTER_CROP matches the playerView's RESIZE_MODE_ZOOM framing.
        val posterView =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
        this.addView(
            posterView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        this.posterView = posterView
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

        this.removePosterHideListener()
        this.hidePoster()

        this.pause()

        player.release()
        this.player = null
        this.playerView.player = null
        this.playerScope?.cancel()
        this.playerScope = null
    }

    internal fun onAppBackgrounded(): Boolean {
        if (this.isFullscreen) return false
        val player = this.player ?: return false
        val wasPlaying = player.isPlaying
        this.savedPosition = player.currentPosition
        this.pause()
        this.mute()
        player.stop()
        return wasPlaying
    }

    internal fun onAppForegrounded(wasPlaying: Boolean) {
        if (this.isFullscreen) return
        val player = this.player ?: return
        if (player.playbackState == Player.STATE_IDLE) {
            this.isLoading = true
            this.isRecoveringFromBackground = true

            val shouldPlay = wasPlaying || this.autoplay
            val listener = object : Player.Listener {
                private fun cleanup() {
                    player.removeListener(this)
                    this@BlueskyVideoView.isRecoveringFromBackground = false
                    this@BlueskyVideoView.isLoading = false
                }

                override fun onRenderedFirstFrame() {
                    cleanup()
                    if (shouldPlay) {
                        this@BlueskyVideoView.play()
                        if (!this@BlueskyVideoView.beginMuted) {
                            this@BlueskyVideoView.unmute()
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    cleanup()
                }
            }
            player.addListener(listener)
            player.prepare()
            if (this.savedPosition > 0) {
                player.seekTo(this.savedPosition)
                this.savedPosition = 0L
            }
        }
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
        this.isPlaying = true
        this.player?.play()
    }

    private fun pause() {
        this.isPlaying = false
        this.player?.pause()
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

    fun mute() {
        this.player?.volume = 0f
        this.isMuted = true
        BlueskyVideoModule.audioFocusManager.abandonAudioFocus()
    }

    private fun unmute() {
        BlueskyVideoModule.audioFocusManager.requestAudioFocus()
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

    fun enterFullscreen(keepDisplayOn: Boolean) {
        val currentActivity = this.appContext.currentActivity ?: return

        this.enteredFullscreenMuteState = this.isMuted

        // We always want to start with unmuted state and playing. Fire those from here so the
        // event dispatcher gets called
        this.unmute()
        if (!this.isPlaying) {
            this.play()
        }

        // Grab the current inline rect (in screen coords) and attempt to capture the
        // still-attached inline SurfaceView. Capture is async (PixelCopy), so the rest
        // of the launch happens inside the completion callback.
        val enterRect = this.getScreenRect()
        val surfaceView = this.findSurfaceView()

        this.captureFrame(surfaceView) { bitmap ->
            if (bitmap != null && enterRect != null) {
                TransitionSnapshot.bitmap = bitmap
                TransitionSnapshot.enterRect = enterRect
                this.showPoster(bitmap)
            } else {
                // No usable snapshot - clear the holder and fall back to a plain launch.
                TransitionSnapshot.clear()
            }

            this.launchFullscreen(
                currentActivity = currentActivity,
                keepDisplayOn = keepDisplayOn,
                animate = bitmap != null && enterRect != null,
            )
        }
    }

    private fun launchFullscreen(
        currentActivity: android.app.Activity,
        keepDisplayOn: Boolean,
        animate: Boolean,
    ) {
        // Remove the player from this view, but don't null the player! The poster (if
        // shown) now covers the blanked surface.
        this.playerView.player = null

        // create the intent and give it a view
        val intent = Intent(context, FullscreenActivity::class.java)
        intent.putExtra("keepDisplayOn", keepDisplayOn)
        intent.putExtra("animate", animate)
        FullscreenActivity.asscVideoView = WeakReference(this)

        // fire the fullscreen event and launch the intent
        this.isFullscreen = true
        currentActivity.startActivity(intent)
    }

    // Finds the SurfaceView the player renders into by scanning the playerView's
    // children. media3's PlayerView.videoSurfaceView returns a View? which defaults
    // to a SurfaceView for our config; fall back to a manual scan otherwise.
    private fun findSurfaceView(): SurfaceView? {
        (this.playerView.videoSurfaceView as? SurfaceView)?.let {
            return it
        }
        return this.findSurfaceViewIn(this.playerView)
    }

    private fun findSurfaceViewIn(view: View): SurfaceView? {
        if (view is SurfaceView) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                this.findSurfaceViewIn(view.getChildAt(i))?.let {
                    return it
                }
            }
        }
        return null
    }

    // Captures the given SurfaceView into a bitmap via PixelCopy (API 24+). Any
    // failure (old API, invalid surface, zero size, exception, non-success status)
    // resolves the callback with null so callers degrade gracefully.
    private fun captureFrame(
        surfaceView: SurfaceView?,
        callback: (Bitmap?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || surfaceView == null) {
            callback(null)
            return
        }

        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) {
            callback(null)
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        callback(bitmap)
                    } else {
                        callback(null)
                    }
                },
                this.mainHandler,
            )
        } catch (e: Exception) {
            callback(null)
        }
    }

    private fun showPoster(bitmap: Bitmap) {
        this.posterView.setImageBitmap(bitmap)
        this.posterView.visibility = View.VISIBLE
    }

    private fun hidePoster() {
        this.posterView.visibility = View.GONE
        this.posterView.setImageBitmap(null)
    }

    private fun removePosterHideListener() {
        this.posterHideListener?.let {
            this.player?.removeListener(it)
        }
        this.posterHideListener = null
    }

    fun onExitFullscreen() {
        this.isFullscreen = false
        if (this.enteredFullscreenMuteState) {
            this.mute()
        }

        // Restore the player to this view
        this.playerView.player = this.player

        // If a poster is showing, keep it up until the inline surface has rendered its
        // first frame again so we don't flash black on re-attach. A paused non-autoplay
        // player may never render, so fall back to a safety timeout.
        if (this.posterView.visibility == View.VISIBLE) {
            this.removePosterHideListener()
            val player = this.player
            if (player != null) {
                val listener =
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            this@BlueskyVideoView.removePosterHideListener()
                            this@BlueskyVideoView.hidePoster()
                        }
                    }
                this.posterHideListener = listener
                player.addListener(listener)
                this.mainHandler.postDelayed({
                    this.removePosterHideListener()
                    this.hidePoster()
                }, 400)
            } else {
                this.hidePoster()
            }
        }

        // Only pause if autoplay is false, otherwise keep playing
        if (!this.autoplay && this.isPlaying) {
            this.pause()
        }

        // Restore progress tracker now that playerView has a player again
        if (this.player?.isPlaying == true) {
            this.addProgressTracker()
        }

        // The transition is complete; release the snapshot holder.
        TransitionSnapshot.clear()
    }

    // Called from the fullscreen activity's exit animation with a fresh capture of the
    // current fullscreen frame, so the shrinking snapshot lands on a matching still
    // rather than the stale enter-frame.
    fun updatePoster(bitmap: Bitmap) {
        this.showPoster(bitmap)
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

    // Like getPositionOnScreen(), but in true SCREEN coordinates (getLocationOnScreen)
    // rather than window coordinates. The fullscreen activity's window is edge-to-edge
    // while the RN activity's may not be, so window coordinates don't align between the
    // two windows; screen coordinates do. Used only for the fullscreen transition rects -
    // the feed-visibility logic keeps using getPositionOnScreen().
    fun getScreenRect(): Rect? {
        if (!this.isShown) {
            return null
        }

        val screenPosition = intArrayOf(0, 0)
        this.getLocationOnScreen(screenPosition)
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
                                    if (!view.isRecoveringFromBackground && (view.autoplay || view.ignoreAutoplay)) {
                                        view.isLoading = false
                                        view.play()
                                        if (!view.beginMuted) {
                                            view.unmute()
                                        }
                                    }
                                }
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            val view = this@BlueskyVideoView
                            if (view.isPlaying != isPlaying) {
                                view.isPlaying = isPlaying
                            }
                            if (isPlaying) {
                                view.addProgressTracker()
                            } else {
                                view.removeProgressTracker()
                            }
                        }
                    },
                )
            }

    private fun addProgressTracker() {
        // Remove any existing tracker first to prevent multiple timers
        this.removeProgressTracker()

        val player = this.player ?: return
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
    private val mLayoutRunnable =
        Runnable {
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            layout(left, top, right, bottom)
        }

    override fun requestLayout() {
        super.requestLayout()
        post(mLayoutRunnable)
    }
}
