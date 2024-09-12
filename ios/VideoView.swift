import ExpoModulesCore
import AVFoundation

class VideoView: ExpoView, AVPlayerViewControllerDelegate {
  var pViewController: AVPlayerViewController? = nil
  var player: AVPlayer? = nil
  
  // controls
  var isPlaying: Bool = false {
    didSet {
      if isPlaying == oldValue {
        return
      }
      
      self.onStatusChange([
        "status": isPlaying ? "playing" : "paused"
      ])
    }
  }
  
  var isLoading: Bool = true {
    didSet {
      if isLoading == oldValue {
        return
      }
      self.onLoadingChange([
        "isLoading": isLoading
      ])
    }
  }
  
  var isViewActive: Bool = false {
    didSet {
      if isViewActive == oldValue {
        return
      }
      self.onActiveChange([
        "isActive": isViewActive
      ])
    }
  }
  
  var isFullscreen: Bool = false {
    didSet {
      if isFullscreen {
        self.pViewController?.showsPlaybackControls = isFullscreen
        self.play()
      } else {
        self.pViewController?.showsPlaybackControls = false
      }
    }
  }
  
  // props
  var autoplay: Bool = true
  var url: URL? = nil
  
  // event handlers
  let onStatusChange = EventDispatcher()
  let onLoadingChange = EventDispatcher()
  let onError = EventDispatcher()
  let onMutedChange = EventDispatcher()
  let onTimeRemainingChange = EventDispatcher()
  let onActiveChange = EventDispatcher()
  
  // observers
  var periodicTimeObserver: Any? = nil
  
  required init(appContext: AppContext? = nil) {
    self.pViewController = AVPlayerViewController()
    super.init(appContext: appContext)
    self.clipsToBounds = true
  }
  
  // MARK: - lifecycle
  
  func playVideo() {
    guard let url = url, self.player == nil else {
      return
    }
    
    // Setup the view controller
    let pViewController = AVPlayerViewController()
    pViewController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    pViewController.view.backgroundColor = .clear
    pViewController.view.frame = self.frame
    pViewController.showsPlaybackControls = false
    pViewController.delegate = self
    pViewController.videoGravity = .resizeAspectFill
    
    // Recycle the current player if there is one
    if let currentPlayer = self.player {
      PlayerManager.shared.recyclePlayer(currentPlayer)
    }
    
    // Get a new player to use
    let player = PlayerManager.shared.dequeuePlayer()
    
    // Add observers to the player
    self.periodicTimeObserver = self.createPeriodicTimeObserver(player)
    
    // Get the player item and add it to the player
    DispatchQueue.global(qos: .background).async { [weak self] in
      let playerItem = AVPlayerItem(url: url)
      playerItem.preferredForwardBufferDuration = 5
      
      DispatchQueue.main.async {
        player.replaceCurrentItem(with: playerItem)
        self?.addObserversToPlayerItem(playerItem)
      }
    }

    pViewController.player = player
    self.addSubview(pViewController.view)
    
    self.pViewController = pViewController
    self.player = player
  }
  
  func removeVideo() {
    guard let player = self.player else {
      return
    }
    
    // Fire final events
    self.mute()
    self.pause()
    self.isLoading = true
    
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
    
    // Remove the player from the controller
    self.pViewController?.player = nil
    
    // Remove the view controller
    self.pViewController?.view.removeFromSuperview()
    self.pViewController?.removeFromParent()
    self.pViewController = nil
  }
  
  override func willMove(toWindow newWindow: UIWindow?) {
    // Ignore anything that happens whenever we enter fullscreen. It's expected that the view will unmount here
    if self.isFullscreen {
      return
    }
    
    if newWindow == nil {
      ViewManager.shared.remove(self)
      self.removeVideo()
    } else {
      ViewManager.shared.add(self)
    }
  }
  
  deinit {
    self.removeVideo()
  }
  
  // MARK: - observers
  
  @objc func playerDidFinishPlaying(notification: NSNotification) {
    self.player?.seek(to: CMTime.zero)
    self.play()
  }
  
  override func observeValue(forKeyPath keyPath: String?,
                             of object: Any?,
                             change: [NSKeyValueChangeKey : Any]?,
                             context: UnsafeMutableRawPointer?) {
    
    // This shouldn't happen, but just guard nil values
    guard let player = self.player,
          let playerItem = player.currentItem else {
      return
    }
    
    // status changes for the player item, i.e. for loading
    if keyPath == "status" {
      if playerItem.status == AVPlayerItem.Status.readyToPlay {
        self.isLoading = false
        if self.autoplay {
          self.play()
        }
      }
      if playerItem.status == AVPlayerItem.Status.failed {
        self.onError([
          "error": "Failed to load video",
          "errorDescription": playerItem.error?.localizedDescription ?? ""
        ])
      }
    }
  }
  
  func createPeriodicTimeObserver(_ player: AVPlayer) -> Any? {
    let interval = CMTime(seconds: 1,
                          preferredTimescale: CMTimeScale(NSEC_PER_SEC))
    
    return player.addPeriodicTimeObserver(forInterval: interval,
                                                               queue: .main) { [weak self] time in
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
    NotificationCenter.default.addObserver(self,
                                           selector: #selector(playerDidFinishPlaying),
                                           name: .AVPlayerItemDidPlayToEndTime,
                                           object: playerItem)
    playerItem.addObserver(self, forKeyPath: "status", options: [.old, .new], context: nil)
  }
  
  func removeObserversFromPlayerItem(_ playerItem: AVPlayerItem) {
    NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: playerItem)
    playerItem.removeObserver(self, forKeyPath: "status")
  }
  
  // MARK: - AVPlayerViewControllerDelegate
  
  func playerViewController(_ playerViewController: AVPlayerViewController,
                            willEndFullScreenPresentationWithAnimationCoordinator coordinator: UIViewControllerTransitionCoordinator) {
    coordinator.animate(alongsideTransition: nil) { transitionContext in
      self.isFullscreen = false
      self.mute()
      self.play()
    }
  }
  
  // MARK: - visibility
  
  func setIsCurrentlyActive(active: Bool) -> Bool {
    if self.isFullscreen {
      return false
    }

    self.isViewActive = active
    if active {
      self.playVideo()
    } else {
      self.removeVideo()
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
      self.play()
    } else {
      self.pause()
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
      "isMuted": false,
    ])
  }
  
  func toggleMuted() {
    if self.player?.isMuted == true {
      self.unmute()
    } else if self.player?.isMuted == false {
      self.mute()
    }
  }
  
  func enterFullscreen() {
    guard let pViewController = self.pViewController,
          !isFullscreen else {
      return
    }
    
    let selectorName = "enterFullScreenAnimated:completionHandler:"
    let selectorToForceFullScreenMode = NSSelectorFromString(selectorName)

    if pViewController.responds(to: selectorToForceFullScreenMode) {
      pViewController.perform(selectorToForceFullScreenMode, with: true, with: nil)
      self.unmute()
      self.isFullscreen = true
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
    guard let window = self.window else {
      return false
    }

    let viewFrameOnScreen = self.convert(self.bounds, to: window)
    let screenBounds = window.bounds
    let intersection = viewFrameOnScreen.intersection(screenBounds)

    let viewHeight = viewFrameOnScreen.height
    let intersectionHeight = intersection.height

    return intersectionHeight >= 0.5 * viewHeight
  }
}
