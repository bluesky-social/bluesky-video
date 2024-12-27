package expo.modules.blueskyvideo

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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

        // Enable edge-to-edge mode but keep navigation bar persistent
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val playerView =
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
    }

    override fun onDestroy() {
        if (isChangingConfigurations() != true) {
            asscVideoView?.get()?.onExitFullscreen()
        }
        super.onDestroy()
    }
}
