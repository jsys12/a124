package io.github.jsys12.bastion.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView

/** WebView that tracks top overscroll so pull-to-refresh only triggers at the real page top. */
@SuppressLint("ViewConstructor")
class BastionWebView(context: Context, val tab: Tab) : WebView(context) {
    var overscrolledTop = false
        private set
    var lastTouchX = 0f
        private set
    var lastTouchY = 0f
        private set

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        overscrolledTop = clampedY && scrollY <= 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            overscrolledTop = false
            lastTouchX = event.x
            lastTouchY = event.y
        }
        return super.onTouchEvent(event)
    }

    fun canPullToRefresh(): Boolean = scrollY <= 0 && overscrolledTop
}
