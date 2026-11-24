import ExpoModulesCore
import AVKit

public class BlueskyVideoModule: Module {
  private var wasPlayingPlayer: AVPlayer?
  private var mediaServicesResetObserver: NSObjectProtocol?

  public func definition() -> ModuleDefinition {
    Name("BlueskyVideo")

    OnCreate {
      AudioManagement.shared.setPlayingVideo(false)

      // When the system resets its media services (e.g. after the media server crashes), every
      // player and the audio session configuration become invalid and have to be rebuilt.
      self.mediaServicesResetObserver = NotificationCenter.default.addObserver(
        forName: AVAudioSession.mediaServicesWereResetNotification,
        object: nil,
        queue: .main
      ) { _ in
        AudioManagement.shared.onMediaServicesReset()
        ViewManager.shared.reloadAllViews()
      }
    }

    OnDestroy {
      if let observer = self.mediaServicesResetObserver {
        NotificationCenter.default.removeObserver(observer)
        self.mediaServicesResetObserver = nil
      }
    }

    OnAppEntersForeground {
      let wasPlayingPlayer = self.wasPlayingPlayer
      self.wasPlayingPlayer = nil
      wasPlayingPlayer?.play()
      ViewManager.shared.reloadFailedViews(wasPlayingPlayer: wasPlayingPlayer)
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
        "onBufferingChange",
        "onTimeRemainingChange",
        "onFullscreenChange",
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

      AsyncFunction("enterFullscreen") { (view: VideoView, keepDisplayOn: Bool) in
        view.enterFullscreen(keepDisplayOn: keepDisplayOn)
      }
    }
  }
}
