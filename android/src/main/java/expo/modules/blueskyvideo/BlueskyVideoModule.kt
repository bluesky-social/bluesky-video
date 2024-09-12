package expo.modules.blueskyvideo

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

@UnstableApi
class BlueskyVideoModule : Module() {
  private var wasPlaying = false

  override fun definition() = ModuleDefinition {
    Name("BlueskyVideo")

    OnActivityEntersForeground {
      val view = ViewManager.getActiveView() ?: return@OnActivityEntersForeground
      val player = view.player ?: return@OnActivityEntersForeground

      if (player.isPlaying) {
        wasPlaying = true
        player.pause()
      }
    }

    OnActivityEntersBackground {
      if (!wasPlaying) {
        return@OnActivityEntersBackground
      }
      val view = ViewManager.getActiveView() ?: return@OnActivityEntersBackground
      val player = view.player ?: return@OnActivityEntersBackground
      player.play()
    }

    AsyncFunction("updateActiveVideoViewAsync") {
      val handler = Handler(Looper.getMainLooper())
      handler.post {
        ViewManager.updateActiveView()
      }
    }

    View(BlueskyVideoView::class) {
      Events(
        "onStatusChange",
        "onMutedChange",
        "onTimeRemainingChange",
        "onLoadingChange",
        "onActiveChange",
        "onPlayerPress",
        "onError",
      )

      Prop("url") { view: BlueskyVideoView, prop: Uri ->
        view.url = prop
      }

      Prop("autoplay") { view: BlueskyVideoView, prop: Boolean ->
        view.autoplay = prop
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
