//
//  ViewManager.swift
//  BlueskyVideo
//
//  Created by Hailey on 9/10/24.
//

import UIKit

/*
 * Decides which video view is active and which are preloaded, driven
 * natively by scroll position instead of JS round trips.
 *
 * Scroll views enclosing any live video view are observed (KVO on
 * contentOffset) and recomputes are coalesced to one per runloop tick.
 * The active view is the most-visible on-screen view (>= 0.5 visible,
 * ties broken topmost) - the same rule as before. The preload window
 * is every live view within `PRELOAD_VIEWPORTS` viewports of the
 * screen edges; those views hold a prepared, paused player so that
 * scrolling them into view starts playback instantly. The preload
 * pool is capped (`MAX_PRELOADED`), nearest to the viewport first.
 */
class ViewManager: Manager<VideoView> {
  static let shared = ViewManager()

  private var currentlyActiveView: VideoView?

  /* How many viewport heights beyond the screen a view may sit and
   * still hold a preloaded player. */
  private let PRELOAD_VIEWPORTS: CGFloat = 1.0
  /* Cap on simultaneously preloaded (prepared, paused) players,
   * excluding the active one. */
  private let MAX_PRELOADED = 3

  /* Scroll views currently observed, keyed by ObjectIdentifier. */
  private var observedScrollViews: [ObjectIdentifier: NSKeyValueObservation] = [:]
  /* Set while a recompute has been scheduled but not yet run. */
  private var recomputeScheduled = false

  override func add(_ object: VideoView) {
    super.add(object)

    self.observeScrollViews(around: object)
    /* A newly mounted view may already be on screen (initial load,
     * pagination); recompute is coalesced so this stays cheap. */
    self.scheduleRecompute()
  }

  override func remove(_ object: VideoView) {
    super.remove(object)

    if self.currentlyActiveView === object {
      self.currentlyActiveView = nil
    }

    self.pruneScrollObservers()
    self.scheduleRecompute()
  }

  /*
   * Public entry point, also still called from JS. Schedules a
   * recompute; scroll observation makes most of these redundant but
   * they cover non-scroll layout changes.
   */
  func updateActiveView() {
    self.scheduleRecompute()
  }

  func setActiveView(_ view: VideoView) {
    if self.currentlyActiveView !== view {
      self.clearActiveView()
    }
    let didUpdate = view.setIsCurrentlyActive(active: true)
    if didUpdate {
      self.currentlyActiveView = view
    }
  }

  // MARK: - recompute

  private func scheduleRecompute() {
    if self.recomputeScheduled {
      return
    }
    self.recomputeScheduled = true
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      self.recomputeScheduled = false
      self.recompute()
    }
  }

  private func recompute() {
    guard let window = UIApplication.shared.connectedScenes
      .compactMap({ ($0 as? UIWindowScene)?.keyWindow })
      .first
    else {
      return
    }

    let screenBounds = window.bounds

    var candidates: [Candidate] = []
    self.getEnumerator()?.forEach { obj in
      guard let view = obj as? VideoView else { return }
      guard let position = view.getPositionOnScreen() else { return }

      let visibility = view.calculateVisibilityPercentage()

      /* Signed distance from the view's nearest edge to the screen's
       * nearest edge, in viewports. Negative = (partially) on screen. */
      let verticalDistance: CGFloat
      if position.maxY < screenBounds.minY {
        verticalDistance = screenBounds.minY - position.maxY
      } else if position.minY > screenBounds.maxY {
        verticalDistance = position.minY - screenBounds.maxY
      } else {
        verticalDistance = 0
      }
      let distanceInScreens =
        verticalDistance / max(screenBounds.height, 1)

      candidates.append(
        Candidate(
          view: view,
          visibility: visibility,
          minY: position.minY,
          distanceInScreens: distanceInScreens
        )
    )
    }

    /* Active view: >= 0.5 visible, most visible wins, ties broken
     * topmost - the rule this manager has always used. */
    let activeCandidate = candidates
      .filter({ $0.visibility >= 0.5 })
      .max(by: { a, b in
        if a.visibility != b.visibility {
          return a.visibility < b.visibility
        }
        return a.minY > b.minY
      })

    /* Preload window: on-screen or near-screen views, nearest first,
     * capped. The active view is always included. */
    var preloadViews = Set<ObjectIdentifier>()
    if let activeCandidate = activeCandidate {
      preloadViews.insert(ObjectIdentifier(activeCandidate.view))
    }
    for candidate in candidates
      .filter({ $0.distanceInScreens <= PRELOAD_VIEWPORTS })
      .sorted(by: { $0.distanceInScreens < $1.distanceInScreens })
    {
      if preloadViews.count >= MAX_PRELOADED + 1 {
        break
      }
      preloadViews.insert(ObjectIdentifier(candidate.view))
    }

    /* Apply. Views losing the active slot only pause; views outside
     * the preload window release their player.
     *
     * Setting up a player costs a few ms of main-thread AVKit work, so
     * at most one preload setup happens per pass (nearest first); the
     * rest follow on later passes instead of piling into one frame
     * during a fling. The active view is never deferred. */
    var preloadSetupBudget = 1
    var deferredPreload = false
    let byDistance = Dictionary(
      candidates.map({ (ObjectIdentifier($0.view), $0.distanceInScreens) }),
      uniquingKeysWith: { a, _ in a }
    )
    let liveViews = ((self.getEnumerator()?.allObjects as? [VideoView]) ?? [])
      .sorted(by: {
        (byDistance[ObjectIdentifier($0)] ?? .infinity) < (byDistance[ObjectIdentifier($1)] ?? .infinity)
      })
    for view in liveViews {
      var shouldPreload = preloadViews.contains(ObjectIdentifier(view))
      let isActive = activeCandidate.map({ $0.view === view }) ?? false

      if shouldPreload && !isActive && !view.hasPlayer {
        if preloadSetupBudget > 0 {
          preloadSetupBudget -= 1
        } else {
          shouldPreload = false
          deferredPreload = true
        }
      }

      if isActive {
        if self.currentlyActiveView !== view {
          self.clearActiveView()
          let didUpdate = view.setIsCurrentlyActive(active: true)
          if didUpdate {
            self.currentlyActiveView = view
          }
        }
      } else if self.currentlyActiveView === view {
        _ = view.setIsCurrentlyActive(active: false)
        self.currentlyActiveView = nil
      }

      view.setIsPreloaded(shouldPreload)
    }

    /* Finish the deferred setups even if scrolling has stopped, about
     * one frame later rather than on the next runloop tick. */
    if deferredPreload {
      DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(16)) { [weak self] in
        self?.scheduleRecompute()
      }
    }
  }

  private func clearActiveView() {
    if let currentlyActiveView = self.currentlyActiveView {
      _ = currentlyActiveView.setIsCurrentlyActive(active: false)
      self.currentlyActiveView = nil
    }
  }

  // MARK: - scroll observation

  /* Observe the scroll views enclosing a newly added video view so
   * that scrolling recomputes the active and preload sets without a
   * JS round trip. */
  private func observeScrollViews(around view: VideoView) {
    var superview = view.superview
    while let current = superview {
      if let scrollView = current as? UIScrollView {
        let key = ObjectIdentifier(scrollView)
        if self.observedScrollViews[key] == nil {
          self.observedScrollViews[key] = scrollView.observe(
            \.contentOffset,
            options: [.new]
          ) { [weak self] _, _ in
            guard let self = self else { return }
            /* Scrolling coalesces to one recompute per runloop tick;
             * KVO fires per frame otherwise. */
            DispatchQueue.main.async { self.scheduleRecompute() }
          }
        }
      }
      superview = current.superview
    }
  }

  /* Drop observers for scroll views that no longer contain any live
   * video view. */
  private func pruneScrollObservers() {
    let liveViews = (self.getEnumerator()?.allObjects as? [VideoView]) ?? []

    var scrollViewsInUse = Set<ObjectIdentifier>()
    for view in liveViews {
      var superview = view.superview
      while let current = superview {
        if current is UIScrollView {
          scrollViewsInUse.insert(ObjectIdentifier(current))
        }
        superview = current.superview
      }
    }

    for key in self.observedScrollViews.keys
      where !scrollViewsInUse.contains(key)
    {
      self.observedScrollViews.removeValue(forKey: key)?.invalidate()
    }
  }
}

/* Snapshot of a video view's geometry used for one recompute pass. */
private struct Candidate {
  let view: VideoView
  let visibility: CGFloat
  let minY: CGFloat
  let distanceInScreens: CGFloat
}
