package expo.modules.blueskyvideo

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.launch

@UnstableApi
class BlueskyVideoModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BlueskyVideo")

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
