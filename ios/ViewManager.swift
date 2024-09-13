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
    self.prevCount = self.count()
  }

  func updateActiveView() {
    DispatchQueue.main.async {
      var activeView: VideoView?

      if self.count() == 1 {
        // get the first one
        guard let view = self.getEnumerator()?.nextObject() as? VideoView else {
          return
        }
        if view.isViewableEnough() {
          activeView = view
        }
      } else if self.count() > 1 {
        guard let views = self.getEnumerator() else {
          return
        }

        var mostVisibleView: VideoView?
        var mostVisiblePosition: CGRect?

        views.forEach { view in
          guard let view = view as? VideoView else {
            return
          }

          if !view.isViewableEnough() {
            return
          }

          guard let position = view.getPositionOnScreen() else {
            return
          }

          if position.minY >= 150 {
            if mostVisiblePosition == nil {
              mostVisiblePosition = position
            }

            if let unwrapped = mostVisiblePosition,
               position.minY <= unwrapped.minY {
              mostVisibleView = view
              mostVisiblePosition = position
            }
          }
        }

        activeView = mostVisibleView
      }

      if activeView == self.currentlyActiveView {
        return
      }

      self.clearActiveView()
      if let view = activeView {
        self.setActiveView(view)
      }
    }
  }

  private func clearActiveView() {
    if let currentlyActiveView = self.currentlyActiveView {
      _ = currentlyActiveView.setIsCurrentlyActive(active: false)
      self.currentlyActiveView = nil
    }
  }

  func setActiveView(_ view: VideoView) {
    if self.currentlyActiveView != nil {
      self.clearActiveView()
    }
    let didUpdate = view.setIsCurrentlyActive(active: true)
    if didUpdate {
      self.currentlyActiveView = view
    }
  }
}
