package expo.modules.video

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
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
        val event = mutableMapOf<String, Any>("timeRemaining" to timeRemaining)
        // Media3 reports C.TIME_UNSET until the duration is known, so only
        // report it once it's a real length.
        if (duration != C.TIME_UNSET && duration > 0) {
            event["duration"] = duration / 1000.0
        }
        onTimeRemainingChange(event)
        handler.postDelayed(this, 1000)
    }

    fun remove() {
        handler.removeCallbacks(this)
    }
}
