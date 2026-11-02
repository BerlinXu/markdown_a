package com.qoder.mdreader.ui

import android.os.Bundle
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.qoder.mdreader.core.ImageLoader
import com.qoder.mdreader.core.Prefs
import java.io.File

class ImageViewerActivity : AppCompatActivity() {
    private var request: java.util.concurrent.Future<*>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ImageView(this).apply { setBackgroundColor(0xff151515.toInt()); scaleType = ImageView.ScaleType.MATRIX }
        setContentView(view)
        val transform = Matrix()
        val base = Matrix()
        var lastX = 0f
        var lastY = 0f
        var scale = 1f
        val zoom = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (scale * detector.scaleFactor).coerceIn(1f, 8f)
                transform.postScale(next / scale, next / scale, detector.focusX, detector.focusY)
                scale = next; view.imageMatrix = transform; return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                transform.set(base); scale = 1f; view.imageMatrix = transform; return true
            }
        })
        view.setOnTouchListener { _, event ->
            zoom.onTouchEvent(event); gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !zoom.isInProgress && scale > 1f) {
                transform.postTranslate(event.x - lastX, event.y - lastY); view.imageMatrix = transform
            }
            lastX = event.x; lastY = event.y; true
        }
        view.post {
            request = ImageLoader.load(this, intent.getStringExtra("url") ?: "", intent.getStringExtra("base")?.let { File(it) },
                view.width, Prefs(this).loadNetImage) { bitmap, _ ->
                if (isDestroyed) return@load
                if (bitmap == null) { hint("图片不可用"); finish(); return@load }
                view.setImageBitmap(bitmap)
                val ratio = minOf(view.width.toFloat() / bitmap.width, view.height.toFloat() / bitmap.height)
                base.setScale(ratio, ratio)
                base.postTranslate((view.width - bitmap.width * ratio) / 2, (view.height - bitmap.height * ratio) / 2)
                transform.set(base); view.imageMatrix = transform
            }
        }
    }
    override fun onDestroy() { request?.cancel(true); super.onDestroy() }
}
