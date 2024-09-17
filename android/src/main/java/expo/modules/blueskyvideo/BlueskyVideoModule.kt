package expo.modules.blueskyvideo

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

@UnstableApi
class BlueskyVideoModule : Module() {
    companion object {
        lateinit var audioFocusManager: AudioFocusManager
    }

    private var wasPlayingPlayer: Player? = null

    override fun definition() =
        ModuleDefinition {
            Name("BlueskyVideo")

            OnCreate {
                audioFocusManager = AudioFocusManager(appContext)
            }

            OnActivityEntersForeground {
                val view = ViewManager.getActiveView() ?: return@OnActivityEntersForeground
                val player = view.player ?: return@OnActivityEntersForeground

                if (player.isPlaying) {
                    wasPlayingPlayer = player
                    player.pause()
                }
            }

            OnActivityEntersBackground {
                val player = wasPlayingPlayer ?: return@OnActivityEntersBackground
                player.play()
                wasPlayingPlayer = null
            }

            AsyncFunction("updateActiveVideoViewAsync") {
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    ViewManager.updateActiveView()
                }
            }

            View(BlueskyVideoView::class) {
                Events(
                    "onActiveChange",
                    "onLoadingChange",
                    "onMutedChange",
                    "onPlayerPress",
                    "onStatusChange",
                    "onTimeRemainingChange",
                    "onError",
                )

                Prop("url") { view: BlueskyVideoView, prop: Uri ->
                    view.url = prop
                }

                Prop("autoplay") { view: BlueskyVideoView, prop: Boolean ->
                    view.autoplay = prop
                }

                Prop("beginMuted") { view: BlueskyVideoView, prop: Boolean ->
                    view.beginMuted = prop
                }

                Prop("forceTakeover") { view: BlueskyVideoView, prop: Boolean ->
                    view.forceTakeover = prop
                }

                AsyncFunction("togglePlayback") { view: BlueskyVideoView ->
                    view.togglePlayback()
                }

                AsyncFunction("toggleMuted") { view: BlueskyVideoView ->
                    view.toggleMuted()
                }

                AsyncFunction("enterFullscreen") { view: BlueskyVideoView ->
                    view.enterFullscreen()
                }
            }
        }
}
