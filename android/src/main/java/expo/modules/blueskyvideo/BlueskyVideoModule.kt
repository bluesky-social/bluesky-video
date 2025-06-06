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
        var isAppInBackground: Boolean = false
    }

    private var savedPlayerStates: MutableMap<BlueskyVideoView, PlayerState> = mutableMapOf()

    data class PlayerState(
        val isPlaying: Boolean,
        val isMuted: Boolean
    )

    override fun definition() =
        ModuleDefinition {
            Name("BlueskyVideo")

            OnCreate {
                audioFocusManager = AudioFocusManager(appContext)
            }

            OnActivityEntersForeground {
                isAppInBackground = false

                savedPlayerStates.entries.toList().forEach { (view, state) ->
                    view.restorePlayerState(state)
                }
                savedPlayerStates.clear()

                ViewManager.onAppForegrounded()
            }

            OnActivityEntersBackground {
                isAppInBackground = true
                ViewManager.onAppBackgrounded()

                // Don't destroy players if any view is in fullscreen mode
                val hasFullscreenView = ViewManager.getAllViews().any { it.isFullscreen }
                if (!hasFullscreenView) {
                    savedPlayerStates.clear()

                    ViewManager.getAllViews().forEach { view ->
                        view.player?.let { player ->
                            val state = PlayerState(
                                isPlaying = player.isPlaying,
                                isMuted = view.isMuted
                            )
                            savedPlayerStates[view] = state
                        }
                        view.destroyForBackground()
                    }
                }
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
                    "onFullscreenChange",
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

                AsyncFunction("enterFullscreen") { view: BlueskyVideoView, keepDisplayOn: Boolean ->
                    view.enterFullscreen(keepDisplayOn)
                }
            }
        }
}
