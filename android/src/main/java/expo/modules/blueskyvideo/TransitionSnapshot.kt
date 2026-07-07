package expo.modules.blueskyvideo

import android.graphics.Bitmap
import android.graphics.Rect

// Holds the transition snapshot bitmap + inline rect used to morph between the
// inline BlueskyVideoView and the FullscreenActivity. We stash it in a static
// holder rather than Intent extras because a bitmap can easily exceed the ~1MB
// binder transaction limit.
//
// We never recycle() the bitmap - it may still be referenced by an ImageView
// mid-animation, and the GC will collect it once nothing holds it.
object TransitionSnapshot {
    var bitmap: Bitmap? = null
    var enterRect: Rect? = null

    fun consumeBitmap(): Bitmap? {
        val bmp = this.bitmap
        this.bitmap = null
        return bmp
    }

    fun clear() {
        this.bitmap = null
        this.enterRect = null
    }
}
