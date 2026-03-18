package expo.modules.blueskyvideo

import android.graphics.Rect
import androidx.media3.common.util.UnstableApi

@UnstableApi
class ViewManager {
    companion object {
        private val views = mutableSetOf<BlueskyVideoView>()
        private var currentlyActiveView: BlueskyVideoView? = null
        private var prevCount = 0

        fun addView(view: BlueskyVideoView) {
            views.add(view)
            if (prevCount == 0) {
                this.updateActiveView()
            }
            prevCount = views.count()
        }

        fun removeView(view: BlueskyVideoView) {
            views.remove(view)
            prevCount = views.count()
        }

        fun updateActiveView() {
            var activeView: BlueskyVideoView? = null
            val count = views.count()

            if (count == 1) {
                val view = views.first()
                if (view.isViewableEnough()) {
                    activeView = view
                }
            } else if (count > 1) {
                var mostVisibleView: BlueskyVideoView? = null
                var mostVisiblePosition: Rect? = null

                views.forEach { view ->
                    if (!view.isViewableEnough()) {
                        return
                    }

                    val position = view.getPositionOnScreen() ?: return@forEach
                    val topY = position.centerY() - (position.height() / 2)

                    if (topY >= 150) {
                        if (mostVisiblePosition == null) {
                            mostVisiblePosition = position
                        }

                        if (position.centerY() <= mostVisiblePosition!!.centerY()) {
                            mostVisibleView = view
                            mostVisiblePosition = position
                        }
                    }
                }

                activeView = mostVisibleView
            }

            if (activeView == currentlyActiveView) {
                return
            }

            this.clearActiveView()
            if (activeView != null) {
                this.setActiveView(activeView)
            }
        }

        private fun clearActiveView() {
            currentlyActiveView?.setIsCurrentlyActive(false)
            currentlyActiveView = null
        }

        fun setActiveView(view: BlueskyVideoView) {
            if (this.currentlyActiveView != null) {
                this.clearActiveView()
            }

            val didSet = view.setIsCurrentlyActive(true)
            if (didSet) {
                currentlyActiveView = view
            }
        }

        fun getActiveView(): BlueskyVideoView? = currentlyActiveView
    }
}
