//
//  ViewManager.swift
//  BlueskyVideo
//
//  Created by Hailey on 9/10/24.
//

import Foundation

class ViewManager: Manager<VideoView> {
  static let shared = ViewManager()

  private var currentlyActiveView: VideoView?
  private var stagedViews: [VideoView] = []
  private var screenHeight = UIScreen.main.bounds.height
  private var prevCount = 0

  override func add(_ object: VideoView) {
    super.add(object)

    if self.prevCount == 0 {
      self.updateActiveView()
    }
    self.prevCount = self.count()
  }

  override func remove(_ object: VideoView) {
    super.remove(object)
    
    // Remove from staged views if present
    if let index = self.stagedViews.firstIndex(of: object) {
      self.stagedViews.remove(at: index)
    }
    
    // Clear active view if it's the one being removed
    if self.currentlyActiveView == object {
      self.currentlyActiveView = nil
    }
    
    self.prevCount = self.count()
  }

  func updateActiveView() {
    DispatchQueue.main.async {
      guard let views = self.getEnumerator() else {
        return
      }

      // Collect all views with their visibility info
      var visibleViews: [(view: VideoView, visibility: CGFloat, position: CGFloat)] = []
      
      views.forEach { view in
        guard let view = view as? VideoView else {
          return
        }

        guard let position = view.getPositionOnScreen() else {
          return
        }

        let visibilityPercentage = view.calculateVisibilityPercentage()

        // Only consider videos that have any visibility (>0%)
        if visibilityPercentage > 0 {
          visibleViews.append((view: view, visibility: visibilityPercentage, position: position.minY))
        }
      }

      // Sort by visibility percentage (descending), then by position (ascending for topmost)
      visibleViews.sort { first, second in
        if first.visibility == second.visibility {
          return first.position < second.position
        }
        return first.visibility > second.visibility
      }

      // Take the top 3 visible videos
      let topViews = Array(visibleViews.prefix(3))
      
      // The most visible video (≥50%) becomes active, others become staged
      var newActiveView: VideoView?
      var newStagedViews: [VideoView] = []
      
      for (index, viewInfo) in topViews.enumerated() {
        if index == 0 && viewInfo.visibility >= 0.5 {
          // First video with ≥50% visibility becomes active
          newActiveView = viewInfo.view
        } else {
          // Other visible videos become staged
          newStagedViews.append(viewInfo.view)
        }
      }

      // Check if we need to update anything
      let activeViewChanged = newActiveView != self.currentlyActiveView
      let stagedViewsChanged = !Set(newStagedViews).isSubset(of: Set(self.stagedViews)) || 
                               !Set(self.stagedViews).isSubset(of: Set(newStagedViews))

      if !activeViewChanged && !stagedViewsChanged {
        return
      }

      // Clear current state
      self.clearActiveView()
      self.clearStagedViews()

      // Destroy players for views that are no longer in top 3
      let newTopViews = Set([newActiveView].compactMap { $0 } + newStagedViews)
      self.destroyViewsNotInSet(newTopViews)

      // Set new active view
      if let activeView = newActiveView {
        self.setActiveView(activeView)
      }

      // Set new staged views
      for stagedView in newStagedViews {
        self.setStagedView(stagedView)
      }
    }
  }


  private func clearActiveView() {
    if let currentlyActiveView = self.currentlyActiveView {
      _ = currentlyActiveView.transitionToInactive()
      self.currentlyActiveView = nil
    }
  }

  private func clearStagedViews() {
    for stagedView in self.stagedViews {
      _ = stagedView.transitionToInactive()
    }
    self.stagedViews.removeAll()
  }

  func setActiveView(_ view: VideoView) {
    if self.currentlyActiveView != nil {
      self.clearActiveView()
    }
    let didUpdate = view.transitionToActive()
    if didUpdate {
      self.currentlyActiveView = view
    }
  }

  func setStagedView(_ view: VideoView) {
    let didUpdate = view.transitionToStaged()
    if didUpdate {
      self.stagedViews.append(view)
    }
  }

  private func destroyViewsNotInSet(_ topViews: Set<VideoView>) {
    guard let views = self.getEnumerator() else {
      return
    }
    
    views.forEach { view in
      guard let view = view as? VideoView else {
        return
      }
      
      // If this view is not in the top 3, destroy its player
      if !topViews.contains(view) {
        view.destroy()
      }
    }
  }
}
