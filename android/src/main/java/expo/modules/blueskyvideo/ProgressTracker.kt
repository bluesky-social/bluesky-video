package expo.modules.video

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import expo.modules.kotlin.viewevent.ViewEventCallback
import kotlin.math.floor

@androidx.annotation.OptIn(UnstableApi::class)
class ProgressTracker(
    private val player: Player,
    private val onTimeRemainingChange: ViewEventCallback<Map<String, Any>>,
) : Runnable {
    private val handler: Handler = Handler(Looper.getMainLooper())

    init {
        handler.post(this)
    }

    override fun run() {
        val currentPosition = player.currentPosition
        val duration = player.duration
        val timeRemaining = floor(((duration - currentPosition) / 1000).toDouble())
        onTimeRemainingChange(
            mapOf(
                "timeRemaining" to timeRemaining,
            ),
        )
        handler.postDelayed(this, 1000)
    }

    fun remove() {
        handler.removeCallbacks(this)
    }
}
