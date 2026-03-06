package com.nlacsoft.sbsprojector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service that:
 * 1. Owns the TYPE_ACCESSIBILITY_OVERLAY window (trusted by the input system, unlike
 *    TYPE_APPLICATION_OVERLAY).  This prevents Android 12+'s untrusted-touch policy from
 *    marking injected gestures as obscured when they reach the projected app.
 * 2. Injects touch gestures on behalf of the SBS overlay via [injectGesture].
 *
 * The singleton [instance] is set when the system binds to the service (i.e. when the user
 * enables it in Settings → Accessibility).  SbsOverlayService calls [addOverlayWindow] /
 * [removeOverlayWindow] to manage the overlay lifetime, and SbsSurfaceView calls
 * [injectGesture] to forward translated touch paths.
 */
class SbsAccessibilityService : AccessibilityService() {

    companion object {
        /** Non-null while the service is bound (user has it enabled in Accessibility settings). */
        @Volatile var instance: SbsAccessibilityService? = null
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // -------------------------------------------------------------------------
    // Overlay window — TYPE_ACCESSIBILITY_OVERLAY is trusted by the input system
    // -------------------------------------------------------------------------

    fun addOverlayWindow(view: View, params: WindowManager.LayoutParams) {
        overlayView = view
        overlayParams = params
        windowManager.addView(view, params)
    }

    fun removeOverlayWindow() {
        val v = overlayView ?: return
        if (v.isAttachedToWindow) windowManager.removeView(v)
        overlayView = null
        overlayParams = null
    }

    /** Updates [WindowManager.LayoutParams.flags] on the live overlay window. */
    fun updateOverlayFlags(flags: Int) {
        val v = overlayView ?: return
        val p = overlayParams ?: return
        p.flags = flags
        if (v.isAttachedToWindow) windowManager.updateViewLayout(v, p)
    }

    // -------------------------------------------------------------------------
    // Global actions
    // -------------------------------------------------------------------------

    fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // -------------------------------------------------------------------------
    // Gesture injection
    // -------------------------------------------------------------------------

    /**
     * Dispatches [path] as a single-stroke gesture with the given [durationMs].
     * [onDone] is invoked on the main thread when the gesture completes or is cancelled.
     * Safe to call from any thread; [dispatchGesture] is thread-safe.
     */
    fun injectGesture(path: Path, durationMs: Long, onDone: () -> Unit) {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d("SbsInject", "gesture completed")
                onDone()
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.d("SbsInject", "gesture CANCELLED")
                onDone()
            }
        }, null)
        Log.d("SbsInject", "dispatchGesture returned $dispatched, durationMs=$durationMs, activeWindow=${rootInActiveWindow?.packageName}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}
