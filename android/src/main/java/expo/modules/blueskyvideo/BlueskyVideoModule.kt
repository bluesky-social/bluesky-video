package expo.modules.blueskyvideo

import android.net.Uri
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class BlueskyVideoModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BlueskyVideo")

    AsyncFunction("updateActiveVideoViewAsync") { value: String ->
    }

    View(BlueskyVideoView::class) {
      Prop("url") { view: BlueskyVideoView, prop: Uri ->
        view.url = prop
      }

      Prop("autoplay") { view: BlueskyVideoView, prop: Boolean ->
        view.autoplay = prop
      }

      AsyncFunction("togglePlayback") { view: BlueskyVideoView, value: Boolean ->
        view.togglePlayback()
      }

      AsyncFunction("toggleMuted") { view: BlueskyVideoView, value: Boolean ->
        view.toggleMuted()
      }

      AsyncFunction("enterFullscreen") { view: BlueskyVideoView, value: Boolean ->
        view.enterFullscreen()
      }
    }
  }
}
