package com.mhw.phone.extensions

import androidx.recyclerview.widget.RecyclerView

// Callbacks are dropped once the list is detached, so a finished screen is never touched.
fun RecyclerView.runAfterAnimations(callback: () -> Unit) {
    if (isComputingLayout) {
        postOnAnimation { runAfterAnimations(callback) }
        return
    }

    val runIfAttached = Runnable { if (isAttachedToWindow) callback() }
    val animator = itemAnimator
    if (animator == null) {
        postOnAnimation(runIfAttached)
    } else {
        animator.isRunning {
            postOnAnimation(runIfAttached)
        }
    }
}
