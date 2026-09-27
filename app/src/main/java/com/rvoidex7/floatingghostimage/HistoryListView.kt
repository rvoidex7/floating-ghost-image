package com.rvoidex7.floatingghostimage

import android.content.Context
import android.util.AttributeSet
import android.widget.ListView

/**
 * Exposes AbsListView's protected vertical scroll metrics so the fisheye peak can be
 * driven by the list's own exact scroll geometry — exact even with rows of varying
 * height. Behaviour is unchanged; the base class simply scrolls as usual.
 */
class HistoryListView(context: Context, attrs: AttributeSet? = null) : ListView(context, attrs) {
    fun verticalScrollOffset(): Int = computeVerticalScrollOffset()
    fun verticalScrollExtent(): Int = computeVerticalScrollExtent()
    fun verticalScrollRange(): Int = computeVerticalScrollRange()
}