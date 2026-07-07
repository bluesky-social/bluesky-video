package expo.modules.blueskyvideo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.lang.ref.WeakReference

@UnstableApi
class FullscreenActivity : AppCompatActivity() {
    companion object {
        var asscVideoView: WeakReference<BlueskyVideoView>? = null

        private const val MORPH_ANIM_DURATION = 300L
        private const val CROSSFADE_DURATION = 150L
        private const val CROSSFADE_TIMEOUT = 300L
        private const val FADE_OUT_DURATION = 180L
    }

    private lateinit var rootView: FrameLayout
    private lateinit var backdropView: View
    private lateinit var playerView: PlayerView

    private var snapshotView: ImageView? = null
    private var didAnimateExit = false

    private var firstFrameListener: Player.Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Critically damped spring (iOS-style, no bounce): x(t) = 1 - (1 + ωt)e^(-ωt),
    // normalized so f(1) = 1. Fast attack, smooth settle, never overshoots. ω = 10
    // over a 300ms duration ≈ a UISpringTimingParameters response of ~0.2s.
    private val springInterpolator =
        TimeInterpolator { t ->
            val omega = 10f
            val decay = { x: Float ->
                1f - (1f + omega * x) * Math.exp((-omega * x).toDouble()).toFloat()
            }
            decay(t.coerceIn(0f, 1f)) / decay(1f)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val player = asscVideoView?.get()?.player

        if (player == null) {
            TransitionSnapshot.clear()
            finish()
            return
        }

        // Enable edge-to-edge mode but keep navigation bar persistent
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Transparent root so, on API 30+ (translucent theme), the inline feed +
        // inline poster show through the margins during the morph.
        this.rootView =
            FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

        // Black backdrop behind the player. A plain View (alpha works, unlike a
        // SurfaceView) that fades in with the enter morph and out with the exit, so
        // the feed dims smoothly instead of snapping to black.
        this.backdropView =
            View(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        this.rootView.addView(this.backdropView)

        val playerView =
            PlayerView(this).apply {
                setBackgroundColor(Color.BLACK)
                setShowSubtitleButton(true)
                setShowNextButton(false)
                setShowPreviousButton(false)
                setFullscreenButtonClickListener {
                    this@FullscreenActivity.startAnimatedExit()
                }
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                useController = true
                controllerAutoShow = false
                controllerHideOnTouch = true
            }
        playerView.player = player
        this.playerView = playerView
        this.rootView.addView(playerView)
        setContentView(this.rootView)

        ViewCompat.setOnApplyWindowInsetsListener(playerView) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, systemBarsInsets.top, 0, systemBarsInsets.bottom)
            insets
        }

        // 31 or higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.insetsController?.let {
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_DEFAULT
            }
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        val keepDisplayOn = this.intent.getBooleanExtra("keepDisplayOn", false)
        if (keepDisplayOn) {
            this.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Route the system back button through the same unified animated exit.
        this.onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    this@FullscreenActivity.startAnimatedExit()
                }
            },
        )

        val enterBitmap = TransitionSnapshot.bitmap
        val enterRect = TransitionSnapshot.enterRect
        val shouldAnimateEnter =
            savedInstanceState == null &&
                this.intent.getBooleanExtra("animate", false) &&
                enterBitmap != null &&
                enterRect != null

        if (shouldAnimateEnter) {
            this.startEnterAnimation(enterBitmap!!, enterRect!!)
        } else {
            // Plain launch: no morph. Make the window opaque again on API 30+ so a
            // portrait-locked host doesn't leave us permanently translucent (and so the
            // feed doesn't peek through). The black PlayerView background fills the window.
            this.setActivityTranslucent(false)
        }
    }

    // Enter morph: snapshot starts at the inline rect and animates its bounds to the
    // letterboxed fit rect, then the live player is revealed behind it and the
    // snapshot crossfades out.
    private fun startEnterAnimation(
        bitmap: Bitmap,
        enterRect: Rect,
    ) {
        // SurfaceView ignores View alpha (it punches a hole in the window), so alpha
        // can't hide the player during the morph. INVISIBLE would, but an invisible
        // SurfaceView doesn't create or render its surface, which forces a visible
        // stall at morph end while the first frame gets ready. Instead, park the
        // player off-screen via translation: it keeps rendering there, so the swap at
        // morph end is instant. INVISIBLE only until layout gives us a width to
        // translate by.
        this.playerView.visibility = View.INVISIBLE

        // Fade the backdrop in with the morph so the feed dims smoothly.
        this.backdropView.alpha = 0f

        this.rootView.doOnLayout {
            // enterRect is in screen coordinates; convert to rootView-local coordinates
            // here, once the root has been laid out and knows its own screen position.
            val localEnterRect = this.screenRectToLocal(enterRect)
            val snapshot = this.makeSnapshotView(bitmap, localEnterRect)
            this.snapshotView = snapshot
            this.rootView.addView(snapshot)

            this.backdropView
                .animate()
                .alpha(1f)
                .setDuration(MORPH_ANIM_DURATION)
                .setInterpolator(this.springInterpolator)
                .start()

            // Pre-warm the surface off-screen during the morph.
            this.playerView.translationX = this.rootView.width.toFloat()
            this.playerView.visibility = View.VISIBLE

            var morphDone = false
            var crossfaded = false
            val crossfade = crossfade@{
                if (crossfaded || !morphDone) {
                    return@crossfade
                }
                crossfaded = true
                this.removeFirstFrameListener()
                this.playerView.translationX = 0f
                snapshot
                    .animate()
                    .alpha(0f)
                    .setDuration(CROSSFADE_DURATION)
                    .withEndAction {
                        this.removeSnapshot()
                        // Restore free rotation now the morph is done (API 30+).
                        this.setActivityTranslucent(false)
                    }.start()
            }

            // Listen for the first frame from the start of the morph - it usually
            // renders (off-screen) before the morph lands.
            var firstFrameRendered = false
            val player = this.asscPlayer()
            if (player != null) {
                val listener =
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            firstFrameRendered = true
                            crossfade()
                        }
                    }
                this.firstFrameListener = listener
                player.addListener(listener)
            }

            val fitRect = this.computeFitRect()
            this.animateBounds(snapshot, localEnterRect, fitRect) {
                morphDone = true
                if (firstFrameRendered || player == null) {
                    crossfade()
                } else {
                    // Safety timeout in case onRenderedFirstFrame doesn't re-fire on
                    // re-attach.
                    this.mainHandler.postDelayed({ crossfade() }, CROSSFADE_TIMEOUT)
                }
            }
        }
    }

    // Unified animated exit for both the fullscreen button and the back button.
    private fun startAnimatedExit() {
        if (this.didAnimateExit) {
            return
        }
        this.didAnimateExit = true

        val view = asscVideoView?.get()
        // Fresh inline rect in screen coordinates; convert to rootView-local coordinates
        // so it lines up with the (edge-to-edge) fullscreen window's layout.
        val localExitRect = view?.getScreenRect()?.let { this.screenRectToLocal(it) }
        val screenRect = Rect(0, 0, this.rootView.width, this.rootView.height)

        // Viability check: the inline view must exist, be on-screen, and its (fresh) rect
        // must actually intersect the screen. getScreenRect() does not clip to the
        // screen, so a recycled-but-attached off-screen item returns an off-screen rect.
        val viable =
            view != null &&
                localExitRect != null &&
                Rect.intersects(localExitRect, screenRect)

        if (!viable) {
            this.fadeOutAndFinish()
            return
        }

        // PixelCopy the current fullscreen frame (video has progressed since enter).
        this.captureCurrentFrame { bitmap ->
            if (bitmap == null) {
                this.fadeOutAndFinish()
                return@captureCurrentFrame
            }

            // Hand the fresh still back to the inline view as an updated poster so the
            // shrinking snapshot lands on a matching image.
            view!!.updatePoster(bitmap)

            // Reveal the feed + inline poster behind the shrinking snapshot (API 30+).
            this.setActivityTranslucent(true)

            val fitRect = this.computeFitRect()
            val snapshot = this.makeSnapshotView(bitmap, fitRect)
            this.snapshotView = snapshot
            this.rootView.addView(snapshot)

            // SurfaceView ignores View alpha - INVISIBLE actually hides the live video
            // (and the PlayerView's black background) so only the snapshot is visible.
            this.playerView.visibility = View.INVISIBLE

            // Fade the backdrop out with the shrink so the feed brightens smoothly.
            this.backdropView
                .animate()
                .alpha(0f)
                .setDuration(MORPH_ANIM_DURATION)
                .setInterpolator(this.springInterpolator)
                .start()

            this.animateBounds(snapshot, fitRect, localExitRect!!) {
                this.finish()
            }
        }
    }

    // Converts a rect in absolute screen coordinates to rootView-local coordinates by
    // subtracting the root's own screen position. Handles any status-bar/decor offset
    // between the RN activity's window and this edge-to-edge window on both ends.
    private fun screenRectToLocal(rect: Rect): Rect {
        val rootPosition = intArrayOf(0, 0)
        this.rootView.getLocationOnScreen(rootPosition)
        return Rect(
            rect.left - rootPosition[0],
            rect.top - rootPosition[1],
            rect.right - rootPosition[0],
            rect.bottom - rootPosition[1],
        )
    }

    private fun fadeOutAndFinish() {
        this.rootView
            .animate()
            .alpha(0f)
            .setDuration(FADE_OUT_DURATION)
            .withEndAction { this.finish() }
            .start()
    }

    // Computes the letterboxed video rect from the player's videoSize (including
    // pixelWidthHeightRatio) fitted into the playerView's padded content area — the
    // insets listener pads the playerView for the system bars, so the video is
    // letterboxed within that area, not the full root bounds. Falls back to the
    // content bounds if the video size is unknown.
    private fun computeFitRect(): Rect {
        val contentLeft = this.playerView.paddingLeft
        val contentTop = this.playerView.paddingTop
        val contentW = this.rootView.width - contentLeft - this.playerView.paddingRight
        val contentH = this.rootView.height - contentTop - this.playerView.paddingBottom
        val fullRect = Rect(contentLeft, contentTop, contentLeft + contentW, contentTop + contentH)

        val player = this.asscPlayer() ?: return fullRect
        val videoSize = player.videoSize
        if (videoSize.width <= 0 || videoSize.height <= 0 || contentW <= 0 || contentH <= 0) {
            return fullRect
        }

        val videoAspect =
            (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
        val contentAspect = contentW.toFloat() / contentH.toFloat()

        val fitW: Int
        val fitH: Int
        if (videoAspect > contentAspect) {
            fitW = contentW
            fitH = (contentW / videoAspect).toInt()
        } else {
            fitH = contentH
            fitW = (contentH * videoAspect).toInt()
        }

        val left = contentLeft + (contentW - fitW) / 2
        val top = contentTop + (contentH - fitH) / 2
        return Rect(left, top, left + fitW, top + fitH)
    }

    private fun makeSnapshotView(
        bitmap: Bitmap,
        rect: Rect,
    ): ImageView =
        ImageView(this).apply {
            // The captured bitmap is the FULL video frame: PixelCopy reads the surface
            // buffer, and RESIZE_MODE_ZOOM oversizes the inline SurfaceView beyond the
            // view bounds (the parent clips it). CENTER_CROP therefore reproduces the
            // inline ZOOM framing at the inline rect and becomes an exact fit at the
            // letterboxed fit rect (whose aspect matches the video) - so the morph is a
            // crop→fit reveal with no distortion at any point.
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            layoutParams = this@FullscreenActivity.rectToLayoutParams(rect)
        }

    private fun rectToLayoutParams(rect: Rect): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }

    private fun animateBounds(
        target: View,
        from: Rect,
        to: Rect,
        onEnd: () -> Unit,
    ) {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = MORPH_ANIM_DURATION
        animator.interpolator = springInterpolator
        animator.addUpdateListener {
            val f = it.animatedValue as Float
            val left = lerp(from.left, to.left, f)
            val top = lerp(from.top, to.top, f)
            val right = lerp(from.right, to.right, f)
            val bottom = lerp(from.bottom, to.bottom, f)
            val lp = target.layoutParams as FrameLayout.LayoutParams
            lp.width = right - left
            lp.height = bottom - top
            lp.leftMargin = left
            lp.topMargin = top
            target.layoutParams = lp
        }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }
            },
        )
        animator.start()
    }

    private fun lerp(
        from: Int,
        to: Int,
        fraction: Float,
    ): Int = (from + (to - from) * fraction).toInt()

    // Captures the current fullscreen frame via PixelCopy (API 24+). Resolves null on
    // any failure so callers degrade to the fade fallback.
    private fun captureCurrentFrame(callback: (Bitmap?) -> Unit) {
        val surfaceView = this.findSurfaceView(this.playerView)
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

    private fun findSurfaceView(view: View): SurfaceView? {
        (this.playerView.videoSurfaceView as? SurfaceView)?.let {
            return it
        }
        if (view is SurfaceView) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                this.findSurfaceView(view.getChildAt(i))?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun setActivityTranslucent(translucent: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.applyTranslucent(translucent)
        }
    }

    // setTranslucent is API 30+ and can return false on some OEM skins; tolerate that.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyTranslucent(translucent: Boolean) {
        try {
            this.setTranslucent(translucent)
        } catch (e: Exception) {
            // No-op: enter/exit still function without the translucency toggle.
        }
    }

    private fun asscPlayer(): Player? = asscVideoView?.get()?.player

    private fun removeFirstFrameListener() {
        this.firstFrameListener?.let {
            this.asscPlayer()?.removeListener(it)
        }
        this.firstFrameListener = null
    }

    private fun removeSnapshot() {
        this.snapshotView?.let {
            this.rootView.removeView(it)
        }
        this.snapshotView = null
    }

    private var wasPlaying = false

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) {
            asscVideoView?.get()?.player?.let { player ->
                wasPlaying = player.isPlaying
                player.pause()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (wasPlaying) {
            asscVideoView?.get()?.player?.play()
            wasPlaying = false
        }
    }

    override fun onDestroy() {
        this.removeFirstFrameListener()
        if (isChangingConfigurations() != true) {
            asscVideoView?.get()?.onExitFullscreen()
        }
        super.onDestroy()
    }
}
