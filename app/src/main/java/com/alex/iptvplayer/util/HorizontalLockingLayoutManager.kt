package com.alex.iptvplayer.util

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager

/**
 * Ein horizontaler LinearLayoutManager für Android TV,
 * der verhindert, dass der D-Pad-Fokus am rechten Ende einer Liste
 * in eine andere Zeile/Sektion ausbricht (Hardlock).
 */
class HorizontalLockingLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (direction == View.FOCUS_RIGHT) {
            val pos = getPosition(focused)
            if (pos >= itemCount - 1) {
                // Hardlock: Bleibe auf dem aktuellen Element, statt in eine andere Liste zu springen
                return focused
            }
        }
        return super.onInterceptFocusSearch(focused, direction)
    }
}
