package com.qoder.mdreader.ui

import android.content.Context
import android.widget.Toast

/**
 * 全局复用一个 Toast，先取消上一条再显示。
 *
 * 连续 Toast 在安卓里是排队的：快速点主题切换会堆好几条，
 * 加上切主题会重建页面，提示能滞留十几秒还挡住界面。
 */
private var lastToast: Toast? = null

fun Context.hint(msg: CharSequence) {
    lastToast?.cancel()
    lastToast = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).apply { show() }
}

fun Context.hint(resId: Int) = hint(getString(resId))

fun Context.hint(resId: Int, vararg args: Any) = hint(getString(resId, *args))
