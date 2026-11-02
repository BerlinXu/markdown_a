package com.qoder.mdreader.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Respect system bars and the IME when targeting Android 15+ edge-to-edge windows. */
fun AppCompatActivity.applyInsets(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    root.fitsSystemWindows = false
    val left = root.paddingLeft; val top = root.paddingTop
    val right = root.paddingRight; val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, keyboard.bottom))
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
