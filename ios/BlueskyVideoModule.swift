import ExpoModulesCore

public class BlueskyVideoModule: Module {
  private var wasPlayingPlayer: AVPlayer?

  public func definition() -> ModuleDefinition {
    Name("BlueskyVideo")

    OnAppEntersForeground {
      self.wasPlayingPlayer?.play()
      self.wasPlayingPlayer = nil
    }

    OnAppEntersBackground {
      PlayerManager.shared.allPlayers().forEach { player in
        if player.isPlaying {
          player.pause()
          self.wasPlayingPlayer = player
          return
        }
      }
    }

    AsyncFunction("updateActiveVideoViewAsync") {
      ViewManager.shared.updateActiveView()
    }

    View(VideoView.self) {
      Events([
        "onActiveChange",
        "onLoadingChange",
        "onMutedChange",
        "onStatusChange",
        "onTimeRemainingChange",
        "onError"
      ])

      Prop("url") { (view: VideoView, prop: URL) in
        view.url = prop
      }

      Prop("autoplay") { (view: VideoView, prop: Bool) in
        view.autoplay = prop
      }

      Prop("beginMuted") { (view: VideoView, prop: Bool) in
        view.beginMuted = prop
      }
      
      Prop("forceTakeover") { (view: VideoView, prop: Bool) in
        view.forceTakeover = prop
      }

      AsyncFunction("togglePlayback") { (view: VideoView) in
        view.togglePlayback()
      }

      AsyncFunction("toggleMuted") { (view: VideoView) in
        view.toggleMuted()
      }

      AsyncFunction("enterFullscreen") { (view: VideoView) in
        view.enterFullscreen()
      }
    }
  }
}
