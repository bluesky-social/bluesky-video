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

    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val player = asscVideoView?.get()?.player

        if (player == null) {
            finish()
            return
        }

        playerView =
            PlayerView(this).apply {
                setBackgroundColor(Color.BLACK)
                setShowSubtitleButton(true)
                setShowNextButton(false)
                setShowPreviousButton(false)
                setFullscreenButtonClickListener {
                    finish()
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
        setContentView(playerView)

        hideSystemBars()

        val keepDisplayOn = this.intent.getBooleanExtra("keepDisplayOn", false)
        if (keepDisplayOn) {
            this.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            playerView.windowInsetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            playerView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        asscVideoView?.get()?.onExitFullscreen()
    }
}
