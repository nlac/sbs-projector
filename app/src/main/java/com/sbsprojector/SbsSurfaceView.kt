package com.sbsprojector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Full-screen SurfaceView that draws the captured screen content twice, side-by-side (left eye |
 * right eye), forming an SBS stereo frame.
 *
 * Touch forwarding:
 * - onTouchEvent records the user's gesture path and transforms SBS overlay coordinates back to the
 * original app screen coordinates.
 * - Before injecting, SbsOverlayService adds FLAG_NOT_TOUCHABLE to the overlay window so the system
 * skips it and delivers the injected gesture directly to the original app. The flag is removed once
 * the gesture completes.
 */
class SbsSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val surfaceHolder: SurfaceHolder = holder
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint().apply { color = Color.BLACK }
    private val backButton = OverlayButton("BACK", 0xCC606060.toInt(), RectF())
    private val stopButton = OverlayButton("STOP SBS", 0xCC004090.toInt(), RectF())

    // Pre-allocated rects — reused every frame to avoid GC pressure
    private val srcRect = Rect()
    private val leftDst = RectF()
    private val rightDst = RectF()

    companion object {
        private const val BTN_W = 190f
        private const val BTN_H = 68f
    }

    @Volatile private var surfaceReady = false

    // Inset amounts (pixels) set by the service once insets are known.
    @Volatile var displayInsetLeft = 0
    @Volatile var displayInsetRight = 0

    // -------------------------------------------------------------------------
    // Geometry snapshot — written on capture thread, read on main thread
    // -------------------------------------------------------------------------

    private data class DrawGeometry(
            val halfW: Int,
            val leftDst: RectF,
            val rightDst: RectF,
            val srcL: Int,
            val srcW: Int,
            val srcH: Int
    )

    @Volatile private var lastGeometry: DrawGeometry? = null

    // -------------------------------------------------------------------------
    // Touch forwarding
    // -------------------------------------------------------------------------

    // Set by SbsOverlayService to toggle FLAG_NOT_TOUCHABLE on the overlay window.
    // Before injecting a gesture we make the window non-touchable so the system routes
    // the injected events directly to the projected app (which sits below our overlay).
    // Restored in the gesture completion callback.
    var setWindowTouchable: ((Boolean) -> Unit)? = null

    /** Called (on main thread) when the user taps the on-overlay stop button. */
    var onStopRequested: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val gesturePath = Path()
    private var gestureDownTime = 0L
    // True while a gesture is being injected — swallows incoming touches to prevent re-entry.
    private var injecting = false

    init {
        surfaceHolder.addCallback(this)
        // Transparent surface so the underlying app is visible when no frame yet
        setZOrderOnTop(true)
        surfaceHolder.setFormat(PixelFormat.TRANSLUCENT)
    }

    // -------------------------------------------------------------------------
    // Public API — called from ScreenCaptureManager's capture thread
    // -------------------------------------------------------------------------

    fun drawSbsFrame(bitmap: Bitmap) {
        if (!surfaceReady) {
            bitmap.recycle()
            return
        }

        val canvas: Canvas? = surfaceHolder.lockCanvas()
        if (canvas == null) {
            bitmap.recycle()
            return
        }

        try {
            val cW = canvas.width
            val cH = canvas.height
            // just to make sure
            if (cW <= 0 || cH <= 0) return
            val halfW = cW / 2

            // Crop to active content area, skipping camera-cutout (left) and
            // nav-bar (right) dead zones set from WindowInsets by the service.
            val srcL = displayInsetLeft
            val srcR = bitmap.width - displayInsetRight
            val srcW = srcR - srcL
            srcRect.set(srcL, 0, srcR, bitmap.height)

            // Fit cropped source into each half while preserving aspect ratio (letterbox)
            computeDestRect(srcW, bitmap.height, 0, halfW, cH, leftDst)
            computeDestRect(srcW, bitmap.height, halfW, cW, cH, rightDst)

            // Apply shrink: scale content uniformly around each rect's centre
            val shrink = SbsOverlayService.shrink
            val dInset = leftDst.width() * (1f - shrink) / 2f
            val vInset = leftDst.height() * (1f - shrink) / 2f
            leftDst.inset(dInset, vInset)
            rightDst.inset(dInset, vInset)

            // Apply closeness: shift centres relative to the default quarter positions.
            // quarterW * (1 - closeness) is zero when closeness=1, positive when <1 (spread out),
            // negative when >1 (pull inward).
            val closeness = SbsOverlayService.closeness
            val quarterW = halfW / 2f
            leftDst.offset(quarterW * (1f - closeness), 0f)
            rightDst.offset(quarterW * (closeness - 1f), 0f)

            // Snapshot geometry so onTouchEvent (main thread) can transform coordinates
            lastGeometry =
                    DrawGeometry(halfW, RectF(leftDst), RectF(rightDst), srcL, srcW, bitmap.height)

            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, srcRect, leftDst, paint)
            canvas.drawBitmap(bitmap, srcRect, rightDst, paint)
            // 1-pixel black divider between eyes
            canvas.drawRect(halfW - 1f, 0f, halfW + 1f, cH.toFloat(), dividerPaint)

            drawOverlayButtons(canvas, leftDst)
        } finally {
            surfaceHolder.unlockCanvasAndPost(canvas)
            bitmap.recycle()
        }
    }

    /** Draws the two action buttons above the first half-projection */
    private fun drawOverlayButtons(canvas: Canvas, leftContent: RectF) {
        val btnTop = leftContent.top - BTN_H
        val btnBot = leftContent.top
        backButton.updateRect(leftContent.left, btnTop, leftContent.left + BTN_W, btnBot)
        stopButton.updateRect(leftContent.right - BTN_W, btnTop, leftContent.right, btnBot)
        backButton.draw(canvas)
        stopButton.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (injecting) {
            Log.d("SbsInject", "onTouchEvent swallowed action=${event.actionMasked}")
            return true
        }

        // Button hit-tests — consume the entire touch sequence so it is never forwarded.
        if (backButton.hitTest(event) { SbsAccessibilityService.instance?.performBack() })
                return true
        if (stopButton.hitTest(event) { onStopRequested?.invoke() }) return true

        val geom = lastGeometry ?: return false
        val pt = transformToFullScreen(event.x, event.y, geom)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturePath.reset()
                gesturePath.moveTo(pt.x, pt.y)
                gestureDownTime = SystemClock.elapsedRealtime()
            }
            MotionEvent.ACTION_MOVE -> gesturePath.lineTo(pt.x, pt.y)
            MotionEvent.ACTION_UP -> {
                gesturePath.lineTo(pt.x, pt.y)
                val svc = SbsAccessibilityService.instance
                Log.d(
                        "SbsInject",
                        "svc=${if (svc != null) "connected" else "NULL — accessibility service not enabled"}"
                )
                if (svc != null) {
                    injecting = true
                    setWindowTouchable?.invoke(false)
                    val pathCopy = Path(gesturePath)
                    val durationMs = maxOf(50L, SystemClock.elapsedRealtime() - gestureDownTime)
                    // Wait 100 ms for FLAG_NOT_TOUCHABLE to propagate to the input dispatcher
                    // before firing the gesture, so it reaches the projected app instead of our
                    // overlay.
                    handler.postDelayed(
                            {
                                svc.injectGesture(pathCopy, durationMs) {
                                    injecting = false
                                    setWindowTouchable?.invoke(true)
                                }
                            },
                            100L
                    )
                }
                gesturePath.reset()
            }
            MotionEvent.ACTION_CANCEL -> gesturePath.reset()
        }
        return true
    }

    /**
     * Maps a touch point in SBS overlay space to the corresponding app screen coordinate. Touches
     * in the letterbox padding are clamped to the nearest content edge.
     */
    private fun transformToFullScreen(tx: Float, ty: Float, g: DrawGeometry): PointF {
        val dst = if (tx < g.halfW) g.leftDst else g.rightDst
        val cx = tx.coerceIn(dst.left, dst.right)
        val cy = ty.coerceIn(dst.top, dst.bottom)
        return PointF(
                g.srcL + (cx - dst.left) / dst.width() * g.srcW,
                (cy - dst.top) / dst.height() * g.srcH
        )
    }

    // -------------------------------------------------------------------------
    // Letterbox helper
    // -------------------------------------------------------------------------

    /**
     * Compute a destination RectF that fits [srcW × srcH] inside the horizontal band [xStart, xEnd]
     * × [0, canvasH], centred, preserving aspect ratio.
     */
    private fun computeDestRect(
            srcW: Int,
            srcH: Int,
            xStart: Int,
            xEnd: Int,
            canvasH: Int,
            dst: RectF
    ) {
        val availW = (xEnd - xStart).toFloat()
        val availH = canvasH.toFloat()
        val scale = minOf(availW / srcW, availH / srcH)
        val drawW = srcW * scale
        val drawH = srcH * scale
        val left = xStart + (availW - drawW) / 2f
        val top = (availH - drawH) / 2f
        dst.set(left, top, left + drawW, top + drawH)
    }

    // -------------------------------------------------------------------------
    // SurfaceHolder.Callback
    // -------------------------------------------------------------------------

    //override fun surfaceCreated(holder: SurfaceHolder) {
        // we need dimensions, so not setting the flag yet
        // surfaceReady = true
    //}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = width > 0 && height > 0
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }
}
