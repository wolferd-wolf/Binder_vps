package com.coucou.android

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * The root of the overlay window, and the only place two of the collapse gestures can be
 * seen.
 *
 * The window hosts the collapsed rectangle and the expanded prompt box as siblings, so
 * one attached root serves both and neither has to be re-created on a swap.
 *
 * Two events are delivered here and nowhere else:
 *  - [MotionEvent.ACTION_OUTSIDE], which only arrives while the window carries
 *    [android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH]. That is how a
 *    tap on the app behind the prompt box closes it.
 *  - [KeyEvent.KEYCODE_BACK], which only arrives while the window holds focus. The window
 *    holds focus exactly when the chat field does, so this is the back-dismissal and it
 *    costs nothing when the keyboard is not up.
 *
 * Hidden children are [View.GONE] rather than [View.INVISIBLE]: a `GONE` child is not
 * measured, so the collapsed window can be `WRAP_CONTENT` and come out *exactly* the
 * size of the rectangle, with every touch beside it falling through to the app below.
 */
internal class OverlayHostLayout(
    context: Context,
    private val onOutsideTouch: () -> Unit,
    private val onBackPressed: () -> Unit
) : FrameLayout(context) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
