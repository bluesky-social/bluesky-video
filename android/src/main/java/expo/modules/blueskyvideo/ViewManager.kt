package expo.modules.blueskyvideo

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs

@UnstableApi
class ViewManager {
    companion object {
        private val views = mutableSetOf<BlueskyVideoView>()
        private var currentlyActiveView: BlueskyVideoView? = null
        private var prevCount = 0

        var isAppBackgrounded = false

        // A view only becomes active once it has moved less than SETTLE_MAX_TRAVEL_DP
        // between two checks SETTLE_CHECK_INTERVAL_MS apart (500 dp/s), so that videos
        // scrolling past during a fling do not each create and release a player.
        private const val SETTLE_CHECK_INTERVAL_MS = 100L
        private const val SETTLE_MAX_TRAVEL_DP = 50

        private val handler = Handler(Looper.getMainLooper())
        private var pendingView: BlueskyVideoView? = null
        private var pendingViewPosition: Rect? = null
        private val checkPendingView = Runnable { this.activatePendingViewIfSettled() }

        fun addView(view: BlueskyVideoView) {
            views.add(view)
            if (prevCount == 0) {
                this.updateActiveView()
            }
            prevCount = views.count()
        }

        fun removeView(view: BlueskyVideoView) {
            views.remove(view)
            if (pendingView == view) {
                this.cancelPendingView()
            }
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
                        return@forEach
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
                this.cancelPendingView()
                return
            }

            this.clearActiveView()
            if (activeView != null) {
                this.activateWhenSettled(activeView)
            } else {
                this.cancelPendingView()
            }
        }

        private fun activateWhenSettled(view: BlueskyVideoView) {
            if (pendingView == view) {
                return
            }
            handler.removeCallbacks(checkPendingView)
            pendingView = view
            pendingViewPosition = view.getPositionOnScreen()
            handler.postDelayed(checkPendingView, SETTLE_CHECK_INTERVAL_MS)
        }

        private fun activatePendingViewIfSettled() {
            val view = pendingView ?: return
            val position = view.getPositionOnScreen()
            val prevPosition = pendingViewPosition
            if (position == null) {
                this.cancelPendingView()
                return
            }

            val maxTravel = SETTLE_MAX_TRAVEL_DP * view.resources.displayMetrics.density
            if (prevPosition == null ||
                abs(position.left - prevPosition.left) + abs(position.top - prevPosition.top) > maxTravel
            ) {
                pendingViewPosition = position
                handler.postDelayed(checkPendingView, SETTLE_CHECK_INTERVAL_MS)
                return
            }

            this.setActiveView(view)
        }

        private fun cancelPendingView() {
            handler.removeCallbacks(checkPendingView)
            pendingView = null
            pendingViewPosition = null
        }

        private fun clearActiveView() {
            currentlyActiveView?.setIsCurrentlyActive(false)
            currentlyActiveView = null
        }

        fun setActiveView(view: BlueskyVideoView) {
            this.cancelPendingView()
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
