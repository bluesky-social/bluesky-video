package expo.modules.blueskyvideo

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(playerView) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, navBarInsets.bottom)
            insets
        }

        val keepDisplayOn = this.intent.getBooleanExtra("keepDisplayOn", false)
        if (keepDisplayOn) {
            this.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        asscVideoView?.get()?.onExitFullscreen()
    }
}
