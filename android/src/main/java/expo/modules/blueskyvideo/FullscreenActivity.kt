package expo.modules.blueskyvideo

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.lang.ref.WeakReference

@UnstableApi
class FullscreenActivity : AppCompatActivity() {
    companion object {
        var asscVideoView: WeakReference<BlueskyVideoView>? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val player = asscVideoView?.get()?.player

        if (player == null) {
            finish()
            return
        }

        // Enable full-screen mode for older Android versions while avoiding double insets
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        } else {
            window.setDecorFitsSystemWindows(false)
        }

        // Keep screen on if requested
        val keepDisplayOn = this.intent.getBooleanExtra("keepDisplayOn", false)
        if (keepDisplayOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Update the player view with conditional insets
        val playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setFullscreenButtonClickListener {
                finish()
            }

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerAutoShow = false
            controllerHideOnTouch = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Apply padding dynamically for modern Android
                setOnApplyWindowInsetsListener { _, insets ->
                    val systemInsets = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    setPadding(0, systemInsets.top, 0, systemInsets.bottom)
                    insets
                }
            } else {
                // Apply fixed padding for older Android versions
                setPadding(0, getStatusBarHeight(), 0, getNavigationBarHeight())
            }
        }
        playerView.player = player

        setContentView(playerView)
    }

    override fun onDestroy() {
        if (isChangingConfigurations() != true) {
            asscVideoView?.get()?.onExitFullscreen()
        }
        super.onDestroy()
    }

    // Helper methods to calculate insets for status bar and navigation bar
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun getNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }
}
