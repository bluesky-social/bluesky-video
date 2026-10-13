package expo.modules.blueskyvideo

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
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
    private var isRecoveringFromBackground = false
    private var savedPosition: Long = 0L

    var url: Uri? = null
    var autoplay = false
    var beginMuted = true
    var ignoreAutoplay = false

    /*
     * True once the player has reached STATE_READY at least once since setup.
     * Gates all autoplay-on-activate paths: a view that is still buffering
     * starts from the player listener instead, so a preloaded view never
     * begins playing just because it became active.
     */
    private var hasBeenReady = false

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
        this.hasBeenReady = false
        this.ignoreAutoplay = false

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

            /*
             * Only resume if this view is still the active one; otherwise
             * stay prepared-but-paused as a member of the preload window.
             */
            val shouldPlay = wasPlaying || (this.autoplay && this.isViewActive)
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
        this.attachScrollListeners()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        this.detachScrollListeners()
        ViewManager.removeView(this)
        this.destroy()
    }

    /*
     * Scroll views enclosing this view get a listener so that scrolling
     * recomputes the active and preload sets natively, without a JS round
     * trip. Listeners are keyed by view identity and coalesced by the
     * manager, so FlatList recycling (which detaches and reattaches cells)
     * simply re-registers them.
     */
    private val scrollParents = mutableMapOf<Int, Pair<View, Any>>()

    private fun attachScrollListeners() {
        var parent = this.parent
        while (parent is View) {
            when (parent) {
                is RecyclerView -> {
                    val listener =
                        object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                if (dy != 0) {
                                    ViewManager.updateActiveView()
                                }
                            }

                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                ViewManager.onScrollStateChanged(
                                    settling = newState == RecyclerView.SCROLL_STATE_SETTLING,
                                )
                                ViewManager.onUserScrollChanged(
                                    scrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING,
                                )
                            }
                        }
                    parent.addOnScrollListener(listener)
                    this.scrollParents[System.identityHashCode(parent)] = parent to listener
                }
                is android.widget.ScrollView,
                is android.widget.HorizontalScrollView,
                -> {
                    val listener =
                        ViewTreeObserver.OnScrollChangedListener {
                            ViewManager.updateActiveView()
                        }
                    parent.viewTreeObserver.addOnScrollChangedListener(listener)
                    this.scrollParents[System.identityHashCode(parent)] = parent to listener
                }
            }
            parent = parent.parent
        }
    }

    private fun detachScrollListeners() {
        for ((_, entry) in this.scrollParents) {
            val (parent, listener) = entry
            when (parent) {
                is RecyclerView -> parent.removeOnScrollListener(listener as RecyclerView.OnScrollListener)
                else -> parent.viewTreeObserver.removeOnScrollChangedListener(
                    listener as ViewTreeObserver.OnScrollChangedListener,
                )
            }
        }
        this.scrollParents.clear()
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
            } else if (!this.isViewActive) {
                /*
                 * Tapped a preloaded (prepared, paused) video that isn't the
                 * active view: take over the active slot so the manager
                 * doesn't fight this play() on its next recompute.
                 */
                this.ignoreAutoplay = true
                ViewManager.setActiveView(this)
                if (this.hasBeenReady) {
                    this.play()
                }
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

        // Remove the player from this view, but don't null the player!
        this.playerView.player = null

        // create the intent and give it a view
        val intent = Intent(context, FullscreenActivity::class.java)
        intent.putExtra("keepDisplayOn", keepDisplayOn)
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

        // Restore the player to this view
        this.playerView.player = this.player

        // Only pause if autoplay is false, otherwise keep playing
        if (!this.autoplay && this.isPlaying) {
            this.pause()
        }

        // Restore progress tracker now that playerView has a player again
        if (this.player?.isPlaying == true) {
            this.addProgressTracker()
        }
    }

    // Visibility

    /*
     * Views near the viewport (the preload window) hold a prepared, paused
     * player so that becoming active is instant. Activation controls
     * playback; leaving the preload window (not just losing the active slot)
     * is what releases the player.
     */
    val hasPlayer: Boolean
        get() = this.player != null

    fun setIsPreloaded(preloaded: Boolean) {
        if (this.isFullscreen) {
            return
        }

        if (preloaded) {
            if (this.player == null) {
                this.setup()
            }
        } else if (!this.isViewActive) {
            this.destroy()
        }
    }

    /*
     * True while the active video is paused because the user is scrolling;
     * see ViewManager.onUserScrollChanged.
     */
    private var pausedForScroll = false

    /*
     * Pauses playback while the user scrolls; the next activation (the
     * recompute at scroll end re-activates the current view) resumes it.
     * Only applies to autoplay-driven playback - a user who explicitly
     * started a video with a tap keeps it playing through scrolls.
     */
    fun pauseForScroll() {
        if (this.ignoreAutoplay || this.isFullscreen) {
            return
        }
        if (this.isPlaying) {
            this.pause()
            this.pausedForScroll = true
        }
    }

    fun setIsCurrentlyActive(isActive: Boolean): Boolean {
        if (this.isFullscreen) {
            return false
        }

        this.isViewActive = isActive
        if (isActive) {
            if (this.autoplay || this.forceTakeover || this.ignoreAutoplay) {
                if (this.player == null) {
                    this.setup()
                } else if (this.hasBeenReady && !ViewManager.isSettling) {
                    /*
                     * Preloaded: item already buffered, start immediately -
                     * but not mid-fling. Starting decode+render while the
                     * list settles costs frames; the recompute that fires
                     * when the fling ends activates again and starts it.
                     */
                    this.play()
                    this.pausedForScroll = false
                    if (!this.beginMuted) {
                        this.unmute()
                    }
                }
                // else: preloaded but still loading; the player listener
                // starts playback on STATE_READY (it checks isViewActive)
            }
        } else {
            // stay prepared while inside the preload window; only pause here
            this.pause()
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

    fun calculateVisibilityPercentage(): Float {
        if (!this.isShown || this.width == 0 || this.height == 0) {
            return 0f
        }

        val screenPosition = intArrayOf(0, 0)
        this.getLocationInWindow(screenPosition)
        val position = Rect(
            screenPosition[0],
            screenPosition[1],
            screenPosition[0] + this.width,
            screenPosition[1] + this.height,
        )

        // Screen bounds with a top margin to account for the fixed header
        val screenBounds = Rect()
        this.getWindowVisibleDisplayFrame(screenBounds)
        screenBounds.top += 100

        val intersection = Rect()
        if (!intersection.setIntersect(position, screenBounds)) {
            return 0f
        }
        val viewArea = position.width() * position.height()
        val visibleArea = intersection.width() * intersection.height()
        return visibleArea.toFloat() / viewArea
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
                                    // background recovery has its own listener
                                    if (view.isRecoveringFromBackground) {
                                        return
                                    }
                                    view.hasBeenReady = true
                                    view.isLoading = false
                                    /*
                                     * A preloaded view reaches READY while
                                     * paused; only the active view plays.
                                     */
                                    if ((view.autoplay || view.ignoreAutoplay) && view.isViewActive) {
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
