package expo.modules.blueskyvideo

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi

/*
 * Decides which video view is active and which are preloaded, driven
 * natively by scroll position instead of JS round trips.
 *
 * BlueskyVideoView attaches scroll listeners to the scroll views enclosing
 * it; scroll events and view add/remove schedule a recompute coalesced to
 * one per frame. The active view is the most-visible on-screen view
 * (>= 0.5 visible, ties broken topmost) - the same rule as before. The
 * preload window is every live view within `PRELOAD_VIEWPORTS` viewports of
 * the screen edges; those views hold a prepared, paused player so that
 * scrolling them into view starts playback instantly. The preload pool is
 * capped (`MAX_PRELOADED`), nearest to the viewport first.
 */
@UnstableApi
class ViewManager {
    companion object {
        private val views = mutableSetOf<BlueskyVideoView>()
        private var currentlyActiveView: BlueskyVideoView? = null

        /* How many viewport heights beyond the screen a view may sit and
         * still hold a preloaded player. */
        private const val PRELOAD_VIEWPORTS = 1.0f
        /* Cap on simultaneously preloaded (prepared, paused) players,
         * excluding the active one. */
        private const val MAX_PRELOADED = 3

        private val handler = Handler(Looper.getMainLooper())

        /* Set while a recompute has been scheduled but not yet run. */
        private var recomputeScheduled = false

        /*
         * True while any observed list is flinging. A fast fling carries a
         * view through the whole preload window in a few hundred ms, so
         * preparing players mid-fling just creates and destroys them for
         * nothing. Preload setup waits for the fling to settle; the active
         * view is never gated.
         */
        private var settlingScrolls = 0

        /* True while the user's finger is dragging any observed list. */
        private var draggingScrolls = 0

        val isSettling: Boolean
            get() = settlingScrolls > 0

        fun addView(view: BlueskyVideoView) {
            views.add(view)
            scheduleRecompute()
        }

        fun removeView(view: BlueskyVideoView) {
            views.remove(view)
            if (currentlyActiveView === view) {
                currentlyActiveView = null
            }
            scheduleRecompute()
        }

        /*
         * Public entry point, also still called from JS. Schedules a
         * recompute; scroll observation makes most of these redundant but
         * they cover non-scroll layout changes.
         */
        fun updateActiveView() {
            scheduleRecompute()
        }

        /* Called from BlueskyVideoView's RecyclerView scroll listener. */
        fun onScrollStateChanged(settling: Boolean) {
            val wasSettling = settlingScrolls > 0
            settlingScrolls += if (settling) 1 else -1
            if (settlingScrolls < 0) settlingScrolls = 0
            /* When the last fling ends, recompute right away so the videos
             * near where it landed get prepared. */
            if (wasSettling && settlingScrolls == 0) {
                scheduleRecompute()
            }
        }

        /*
         * Decoding+compositing video while the list scrolls costs frames on
         * low-end chips, so the active video pauses on drag start and
         * resumes when the scroll goes idle.
         */
        fun onUserScrollChanged(scrolling: Boolean) {
            val wasDragging = draggingScrolls > 0
            draggingScrolls += if (scrolling) 1 else -1
            if (draggingScrolls < 0) draggingScrolls = 0

            if (scrolling && !wasDragging) {
                currentlyActiveView?.pauseForScroll()
            }
            if (wasDragging && draggingScrolls == 0) {
                scheduleRecompute()
            }
        }

        fun setActiveView(view: BlueskyVideoView) {
            if (currentlyActiveView !== view) {
                clearActiveView()
            }
            val didSet = view.setIsCurrentlyActive(true)
            if (didSet) {
                currentlyActiveView = view
            }
        }

        fun getActiveView(): BlueskyVideoView? = currentlyActiveView

        // Recompute

        private fun scheduleRecompute() {
            /*
             * Recompute walks every view's window position (a Binder call
             * each), so during a drag we do nothing at all: the active view
             * is already paused, preload setup is deferred, and the recompute
             * at drag end picks up wherever the finger landed. Flings still
             * recompute (activation must track the moving viewport).
             */
            if (draggingScrolls > 0) {
                return
            }
            if (recomputeScheduled) {
                return
            }
            recomputeScheduled = true
            handler.post {
                recomputeScheduled = false
                recompute()
            }
        }

        private fun recompute() {
            val anyView = views.firstOrNull() ?: return

            val screenBounds = Rect()
            anyView.getWindowVisibleDisplayFrame(screenBounds)
            val screenHeight = maxOf(screenBounds.height(), 1)

            data class Candidate(
                val view: BlueskyVideoView,
                val visibility: Float,
                val minY: Int,
                val distanceInScreens: Float,
            )

            val candidates = mutableListOf<Candidate>()
            for (view in views) {
                val position = view.getPositionOnScreen() ?: continue
                val visibility = view.calculateVisibilityPercentage()

                /* Signed distance from the view's nearest edge to the
                 * screen's nearest edge, in viewports. Zero while any part
                 * of the view overlaps the screen. */
                val verticalDistance =
                    when {
                        position.bottom < screenBounds.top -> screenBounds.top - position.bottom
                        position.top > screenBounds.bottom -> position.top - screenBounds.bottom
                        else -> 0
                    }

                candidates.add(
                    Candidate(
                        view = view,
                        visibility = visibility,
                        minY = position.top,
                        distanceInScreens = verticalDistance.toFloat() / screenHeight,
                    ),
                )
            }

            /* Active view: >= 0.5 visible, most visible wins, ties broken
             * topmost - the rule this manager has always used. */
            val activeCandidate = candidates
                .filter { it.visibility >= 0.5f }
                .maxWithOrNull(
                    compareBy<Candidate> { it.visibility }.thenByDescending { it.minY },
                )

            /* Preload window: on-screen or near-screen views, nearest first,
             * capped. The active view is always included. */
            val preloadViews = mutableSetOf<BlueskyVideoView>()
            if (activeCandidate != null) {
                preloadViews.add(activeCandidate.view)
            }
            for (candidate in candidates
                .filter { it.distanceInScreens <= PRELOAD_VIEWPORTS }
                .sortedBy { it.distanceInScreens }) {
                if (preloadViews.size >= MAX_PRELOADED + 1) {
                    break
                }
                preloadViews.add(candidate.view)
            }

            /* Apply. Views losing the active slot only pause; views outside
             * the preload window release their player.
             *
             * Setting up a player costs a few ms of main-thread ExoPlayer
             * work, so at most one preload setup happens per pass (nearest
             * first); the rest follow on later passes instead of piling into
             * one frame during a fling. The active view is never deferred. */
            var preloadSetupBudget = 1
            var deferredPreload = false
            val byDistance = candidates.associate { it.view to it.distanceInScreens }
            val liveViews = views.sortedBy { byDistance[it] ?: Float.MAX_VALUE }
            for (view in liveViews) {
                var shouldPreload = preloadViews.contains(view)
                val isActive = activeCandidate?.view === view

                if (shouldPreload && !isActive && !view.hasPlayer) {
                    if (settlingScrolls > 0) {
                        // mid-fling: don't prepare players the fling will
                        // carry straight past; recompute on settle picks
                        // up whatever is near the landing spot
                        shouldPreload = false
                        deferredPreload = true
                    } else if (preloadSetupBudget > 0) {
                        preloadSetupBudget -= 1
                    } else {
                        shouldPreload = false
                        deferredPreload = true
                    }
                }

                if (isActive) {
                    if (currentlyActiveView !== view) {
                        clearActiveView()
                        val didSet = view.setIsCurrentlyActive(true)
                        if (didSet) {
                            currentlyActiveView = view
                        }
                    }
                } else if (currentlyActiveView === view) {
                    view.setIsCurrentlyActive(false)
                    currentlyActiveView = null
                }

                view.setIsPreloaded(shouldPreload)
            }

            /* Finish the deferred setups even if scrolling has stopped,
             * about one frame later rather than on the next scroll event. */
            if (deferredPreload) {
                handler.postDelayed({ scheduleRecompute() }, 16)
            }
        }

        private fun clearActiveView() {
            currentlyActiveView?.setIsCurrentlyActive(false)
            currentlyActiveView = null
        }
    }
}
