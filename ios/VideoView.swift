import AVFoundation
import ExpoModulesCore

/*
 * Renders the inline (feed) video. A bare AVPlayerLayer-backed view is far
 * cheaper on the main thread than AVPlayerViewController, whose view
 * hierarchy is created, laid out and drawn even with its controls hidden.
 */
private class PlayerLayerView: UIView {
  override class var layerClass: AnyClass {
    return AVPlayerLayer.self
  }

  var playerLayer: AVPlayerLayer {
    // swiftlint:disable:next force_cast
    return layer as! AVPlayerLayer
  }
}

class VideoView: ExpoView, AVPlayerViewControllerDelegate {
  private let playerLayerView = PlayerLayerView()
  /* Only exists while presenting fullscreen, see `enterFullscreen`. */
  private var pViewController: AVPlayerViewController?
  private var player: AVPlayer?
  private var periodicTimeObserver: Any?

  // props
  var autoplay: Bool = true
  var beginMuted = true
  var url: URL? {
    didSet {
      if url == nil || url == oldValue {
        return
      }

      // reload any prepared player too, not just an active one
      if self.isViewActive || self.player != nil {
        self.destroy()
        if self.isViewActive {
          self.setup()
        }
      }
    }
  }

  // controls
  private var isLoading: Bool = false {
    didSet {
      if isLoading == oldValue {
        return
      }
      self.onLoadingChange([
        "isLoading": isLoading
      ])
    }
  }

  private var isPlaying: Bool = false {
    didSet {
      if isPlaying == oldValue {
        return
      }

      self.onStatusChange([
        "status": isPlaying ? "playing" : "paused"
      ])
    }
  }

  private var isViewActive: Bool = false {
    didSet {
      if isViewActive == oldValue {
        return
      }
      self.onActiveChange([
        "isActive": isViewActive
      ])
    }
  }

  private var isFullscreen: Bool = false {
    didSet {
      if isFullscreen {
        self.pViewController?.showsPlaybackControls = isFullscreen
        self.play()
      } else {
        self.pViewController?.showsPlaybackControls = false
      }

      self.onFullscreenChange([
        "isFullscreen": isFullscreen
      ])
    }
  }

  var forceTakeover: Bool = false {
    didSet {
      if forceTakeover == oldValue {
        return
      }
      if forceTakeover {
        ViewManager.shared.setActiveView(self)
      }
    }
  }

  // event handlers
  private let onActiveChange = EventDispatcher()
  private let onLoadingChange = EventDispatcher()
  private let onMutedChange = EventDispatcher()
  private let onStatusChange = EventDispatcher()
  private let onTimeRemainingChange = EventDispatcher()
  private let onFullscreenChange = EventDispatcher()
  private let onError = EventDispatcher()

  private var enteredFullScreenMuted = true
  private var enteredFullScreenWithIdleTimerDisabled = false
  private var ignoreAutoplay = false
  private var isDestroyed = true

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    self.clipsToBounds = true

    self.playerLayerView.frame = self.bounds
    self.playerLayerView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    self.playerLayerView.backgroundColor = .clear
    self.playerLayerView.isUserInteractionEnabled = false
    self.playerLayerView.playerLayer.videoGravity = .resizeAspectFill
    self.addSubview(self.playerLayerView)
  }

  // MARK: - lifecycle

  private func setup() {
    guard let url = url, self.player == nil else {
      return
    }

    self.isDestroyed = false
    self.isLoading = true

    // Recycle the current player if there is one
    if let currentPlayer = self.player {
      PlayerManager.shared.recyclePlayer(currentPlayer)
    }

    // Get a new player to use
    let player = PlayerManager.shared.dequeuePlayer()

    // Add observers to the player
    self.periodicTimeObserver = self.createPeriodicTimeObserver(player)

    self.playerLayerView.playerLayer.player = player
    self.player = player

    // Get the player item and add it to the player
    DispatchQueue.global(qos: .background).async { [weak self] in
      let playerItem = AVPlayerItem(url: url)
      playerItem.preferredForwardBufferDuration = 5

      DispatchQueue.main.async {
        self?.player?.replaceCurrentItem(with: playerItem)
        self?.addObserversToPlayerItem(playerItem)
      }
    }
  }

  private func destroy() {
    self.isDestroyed = true

    guard let player = self.player else {
      return
    }

    self.ignoreAutoplay = false

    // Fire final events
    self.pause()
    self.isLoading = false

    // Remove period time observer and nil it
    if let periodicTimeObserver = self.periodicTimeObserver {
      self.player?.removeTimeObserver(periodicTimeObserver)
      self.periodicTimeObserver = nil
    }

    // Remove any observers from the player item and nil the item
    if let playerItem = self.player?.currentItem {
      removeObserversFromPlayerItem(playerItem)
    }

    // Recycle the player and nil the player
    PlayerManager.shared.recyclePlayer(player)
    self.player = nil

    self.playerLayerView.playerLayer.player = nil
    self.removeFullscreenController()
  }

  /* Tears down the controller created for fullscreen, if any. */
  private func removeFullscreenController() {
    guard let pViewController = self.pViewController else {
      return
    }
    pViewController.player = nil
    pViewController.view.removeFromSuperview()
    pViewController.removeFromParent()
    self.pViewController = nil
  }

  override func didMoveToWindow() {
    // also called after leaving a window, which `willMove(toWindow:)` handles
    if self.window != nil {
      ViewManager.shared.add(self)
    }
  }

  override func willMove(toWindow newWindow: UIWindow?) {
    // Ignore anything that happens whenever we enter fullscreen. It's expected that the view will unmount here
    if self.isFullscreen {
      return
    }

    if newWindow == nil {
      ViewManager.shared.remove(self)
      self.destroy()
    }
  }

  // MARK: - observers

  @objc func playerDidFinishPlaying(notification: NSNotification) {
    self.player?.seek(to: CMTime.zero)
    self.play()
  }

  override func observeValue(
    forKeyPath keyPath: String?,
    of object: Any?,
    change: [NSKeyValueChangeKey: Any]?,
    context: UnsafeMutableRawPointer?
  ) {

    // This shouldn't happen, but just guard nil values
    guard let player = self.player,
      let playerItem = player.currentItem
    else {
      return
    }

    // status changes for the player item, i.e. for loading
    if keyPath == "status" {
      if playerItem.status == AVPlayerItem.Status.readyToPlay {
        self.isLoading = false
        // a preloaded view holds its player paused; only the active view plays
        if (self.autoplay || self.ignoreAutoplay) && self.isViewActive {
          self.play()

          if !self.beginMuted {
            self.unmute()
          }
        }
      }
      if playerItem.status == AVPlayerItem.Status.failed {
        self.onError([
          "error": "Failed to load video",
          "errorDescription": playerItem.error?.localizedDescription ?? "",
        ])
      }
    }
  }

  func createPeriodicTimeObserver(_ player: AVPlayer) -> Any? {
    let interval = CMTime(
      seconds: 1,
      preferredTimescale: CMTimeScale(NSEC_PER_SEC))

    return player.addPeriodicTimeObserver(
      forInterval: interval,
      queue: .main
    ) { [weak self] time in
      guard let duration = self?.player?.currentItem?.duration else {
        return
      }
      let timeRemaining = (duration.seconds - time.seconds).rounded()
      self?.onTimeRemainingChange([
        "timeRemaining": timeRemaining
      ])
    }
  }

  func addObserversToPlayerItem(_ playerItem: AVPlayerItem) {
    if self.isDestroyed {
      return
    }

    NotificationCenter.default.addObserver(
      self,
      selector: #selector(playerDidFinishPlaying),
      name: .AVPlayerItemDidPlayToEndTime,
      object: playerItem)
    playerItem.addObserver(self, forKeyPath: "status", options: [.old, .new], context: nil)
  }

  func removeObserversFromPlayerItem(_ playerItem: AVPlayerItem) {
    NotificationCenter.default.removeObserver(
      self, name: .AVPlayerItemDidPlayToEndTime, object: playerItem)
    playerItem.removeObserver(self, forKeyPath: "status")
  }

  // MARK: - AVPlayerViewControllerDelegate

  func playerViewController(
    _ playerViewController: AVPlayerViewController,
    willEndFullScreenPresentationWithAnimationCoordinator coordinator:
      UIViewControllerTransitionCoordinator
  ) {
    coordinator.animate(alongsideTransition: nil) { context in
      if context.isCancelled {
        return
      }

      self.isFullscreen = false
      // back inline: the lightweight player layer takes over again
      self.removeFullscreenController()
      if self.enteredFullScreenMuted {
        self.mute()
      }
      self.play()
      if !self.enteredFullScreenWithIdleTimerDisabled {
        UIApplication.shared.isIdleTimerDisabled = false
      }
    }
  }

  // MARK: - visibility

  /*
   * Views near the viewport (the preload window) hold a prepared, paused
   * player so that becoming active is instant. Activation still controls
   * playback; leaving the preload window (not just losing the active slot)
   * is what releases the player.
   */
  var hasPlayer: Bool {
    return self.player != nil
  }

  func setIsPreloaded(_ preloaded: Bool) {
    if self.isFullscreen {
      return
    }

    if preloaded {
      if self.player == nil {
        self.setup()
      }
    } else if !self.isViewActive {
      self.destroy()
    }
  }

  func setIsCurrentlyActive(active: Bool) -> Bool {
    if self.isFullscreen {
      return false
    }

    self.isViewActive = active
    if active {
      if self.autoplay || self.forceTakeover || self.ignoreAutoplay {
        if self.player == nil {
          self.setup()
        } else if self.player?.currentItem?.status == AVPlayerItem.Status.readyToPlay {
          // preloaded: item already buffered, start immediately
          self.play()

          if !self.beginMuted {
            self.unmute()
          }
        }
        // else: preloaded but still loading; the item status observer
        // starts playback once ready (it now also checks isViewActive)
      }
    } else {
      // stay prepared while inside the preload window; only pause here
      self.pause()
    }
    return true
  }

  // MARK: - controls

  private func play() {
    self.player?.play()
    self.isPlaying = true
  }

  private func pause() {
    self.player?.pause()
    self.isPlaying = false
  }

  func togglePlayback() {
    if self.isPlaying {
      self.pause()
    } else {
      if self.player == nil {
        ViewManager.shared.setActiveView(self)
        self.ignoreAutoplay = true
        self.setup()
      } else {
        self.play()
      }
    }
  }

  private func mute() {
    AudioManagement.shared.setPlayingVideo(false)
    self.player?.isMuted = true
    onMutedChange([
      "isMuted": true
    ])
  }

  private func unmute() {
    AudioManagement.shared.setPlayingVideo(true)
    self.player?.isMuted = false
    onMutedChange([
      "isMuted": false
    ])
  }

  func toggleMuted() {
    if self.player?.isMuted == true {
      self.unmute()
    } else if self.player?.isMuted == false {
      self.mute()
    }
  }

  func enterFullscreen(keepDisplayOn: Bool) {
    guard let player = self.player, !isFullscreen else {
      return
    }

    /*
     * Inline playback renders through `playerLayerView`; the system
     * fullscreen presentation needs an AVPlayerViewController, so create one
     * over the inline view only now. It is removed again when fullscreen
     * ends (see the delegate method) or the player is destroyed.
     */
    let pViewController = self.pViewController ?? AVPlayerViewController()
    if self.pViewController == nil {
      pViewController.view.frame = self.bounds
      pViewController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      pViewController.view.backgroundColor = .clear
      pViewController.showsPlaybackControls = false
      pViewController.delegate = self
      pViewController.videoGravity = .resizeAspectFill
      if #available(iOS 16.0, *) {
        pViewController.allowsVideoFrameAnalysis = false
      }
      pViewController.player = player
      self.addSubview(pViewController.view)
      pViewController.view.layoutIfNeeded()
      self.pViewController = pViewController
    }

    let selectorName = "enterFullScreenAnimated:completionHandler:"
    let selectorToForceFullScreenMode = NSSelectorFromString(selectorName)

    if pViewController.responds(to: selectorToForceFullScreenMode) {
      pViewController.perform(selectorToForceFullScreenMode, with: true, with: nil)
      self.enteredFullScreenMuted = self.player?.isMuted ?? true
      self.unmute()
      self.isFullscreen = true
      self.enteredFullScreenWithIdleTimerDisabled = UIApplication.shared.isIdleTimerDisabled
      if keepDisplayOn {
        UIApplication.shared.isIdleTimerDisabled = true
      }
    } else {
      self.removeFullscreenController()
    }
  }
}

// 🚨 DANGER 🚨
// These functions need to be called from the main thread. Xcode will warn you if you call one of them
// off the main thread, so pay attention!
extension UIView {
  func getPositionOnScreen() -> CGRect? {
    if let window = self.window {
      return self.convert(self.bounds, to: window)
    }
    return nil
  }

  func isViewableEnough() -> Bool {
    return calculateVisibilityPercentage() >= 0.5
  }

  func calculateVisibilityPercentage() -> CGFloat {
    guard let window = self.window else {
      return 0
    }

    let position = self.convert(self.bounds, to: window)

    // Create screen bounds with 100px top margin to account for fixed header
    var screenBounds = window.bounds
    screenBounds.origin.y += 100
    screenBounds.size.height -= 100

    let intersection = position.intersection(screenBounds)
    let viewArea = position.width * position.height
    let visibleArea = intersection.width * intersection.height

    return viewArea > 0 ? visibleArea / viewArea : 0
  }
}
