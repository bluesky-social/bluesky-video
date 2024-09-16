package expo.modules.blueskyvideo

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
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

        this.window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )

        // Update the player viewz
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
    }

    override fun onDestroy() {
        asscVideoView?.get()?.onExitFullscreen()
        super.onDestroy()
    }
}
