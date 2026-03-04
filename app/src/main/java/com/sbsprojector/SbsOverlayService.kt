package com.sbsprojector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast

/**
 * Foreground service that:
 * 1. Holds the MediaProjection token (survives Activity lifecycle)
 * 2. Adds a full-screen TYPE_APPLICATION_OVERLAY window via WindowManager
 * 3. Passes the projection token to ScreenCaptureManager
 * 4. Exposes a stop action via the persistent notification
 */
class SbsOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val ACTION_STOP = "com.sbsprojector.ACTION_STOP"

        private const val TAG = "SbsOverlay"
        private const val CHANNEL_ID = "sbs_overlay_channel"
        private const val NOTIFICATION_ID = 1

        // Checked by MainActivity to update button state
        @Volatile var isRunning = false

        // View geometry — written by MainActivity sliders, read by capture thread every frame
        @Volatile var shrink: Float = 0.65f // 0..1: 1 = fills half-screen, 0 = invisible
        @Volatile
        var closeness: Float = 0.70f // 0.3..1.3: >1 brings eyes inward, <1 pushes outward
    }

    private lateinit var windowManager: WindowManager
    private lateinit var sbsView: SbsSurfaceView
    private var captureManager: ScreenCaptureManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Activity.RESULT_OK == -1 on Android, so -1 is a VALID resultCode.
        // Use Int.MIN_VALUE as the "not provided" sentinel to avoid the collision.
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val projectionData: Intent? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }

        // startForeground() MUST be called before any bail/return, or Android throws
        // ForegroundServiceDidNotStartInTimeException 5 seconds after startForegroundService().
        try {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED", e)
            toast("SBS crash @ startForeground: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (resultCode == Int.MIN_VALUE || projectionData == null) {
            Log.e(TAG, "Missing projection token — resultCode=$resultCode data=$projectionData")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            acquireWakeLock()
            setupOverlayWindow(resultCode, projectionData)
        } catch (e: Exception) {
            Log.e(TAG, "setupOverlayWindow FAILED", e)
            toast("SBS crash @ setup: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    // -------------------------------------------------------------------------
    // Overlay window
    // -------------------------------------------------------------------------

    private fun setupOverlayWindow(resultCode: Int, projectionData: Intent) {
        val wm = windowManager.currentWindowMetrics
        val screenW = wm.bounds.width()
        val screenH = wm.bounds.height()

        sbsView = SbsSurfaceView(this)

        // Tell the view how many pixels to crop from each side of the captured bitmap so
        // the camera-cutout (left) and nav-bar (right) dead zones are excluded before drawing.
        val insets =
                wm.windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
        sbsView.displayInsetLeft = insets.left
        sbsView.displayInsetRight = insets.right

        // Base flags used for normal (touchable) operation.
        // FLAG_SECURE excludes the overlay window from VirtualDisplay capture at the compositor
        // level, so the overlay never appears in the captured frames regardless of whether the
        // user chose "Share entire screen" or "Share one app".  Without it, "Share entire screen"
        // would feed the overlay back into itself, causing an infinite-mirror feedback loop.
        val baseFlags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SECURE

        val params =
                WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                baseFlags,
                                PixelFormat.TRANSLUCENT
                        )
                        .apply {
                            gravity = Gravity.TOP or Gravity.START
                            // Cover the camera cutout so the overlay spans the full display width and the SBS centre divider lands exactly at the physical screen midpoint.
                            layoutInDisplayCutoutMode =
                                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        }

        // Give SbsSurfaceView a way to temporarily disable touch interception so that injected accessibility gestures reach the projected app (which sits below our overlay). Called on the main thread before gesture injection; restored in the gesture callback.
        sbsView.setWindowTouchable = { touchable ->
            val flags =
                    if (touchable) baseFlags
                    else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            SbsAccessibilityService.instance?.updateOverlayFlags(flags)
        }
        sbsView.onStopRequested = { stopSelf() }

        val accSvc = SbsAccessibilityService.instance
                ?: throw IllegalStateException(
                        "Accessibility service not connected — enable SBS Projector in Settings → Accessibility"
                )
        accSvc.addOverlayWindow(sbsView, params)

        captureManager =
                ScreenCaptureManager(
                        context = this,
                        resultCode = resultCode,
                        projectionData = projectionData,
                        screenWidth = screenW,
                        screenHeight = screenH,
                        onFrameAvailable = { bitmap -> sbsView.drawSbsFrame(bitmap) }
                )
        captureManager!!.start()
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onDestroy() {
        isRunning = false
        captureManager?.stop()
        if (::sbsView.isInitialized) {
            sbsView.setWindowTouchable = null
            sbsView.onStopRequested = null
            SbsAccessibilityService.instance?.removeOverlayWindow()
        }
        wakeLock?.release()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Wake lock — keeps screen on during drone flight
    // -------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "SBSProjector::OverlayWakeLock")
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // max 4 hours
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel =
                NotificationChannel(CHANNEL_ID, "SBS Overlay", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "SBS stereoscopic overlay service" }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // Debug helper — shows error on screen even without logcat
    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent =
                PendingIntent.getService(
                        this,
                        0,
                        Intent(this, SbsOverlayService::class.java).apply { action = ACTION_STOP },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
        val openAppIntent =
                PendingIntent.getActivity(
                        this,
                        1,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                )
        return Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("SBS Projector Active")
                .setContentText("Stereoscopic overlay is running")
                .setContentIntent(openAppIntent)
                .addAction(
                        Notification.Action.Builder(
                                        Icon.createWithResource(
                                                this,
                                                android.R.drawable.ic_menu_close_clear_cancel
                                        ),
                                        "Stop SBS",
                                        stopIntent
                                )
                                .build()
                )
                .setOngoing(true)
                .build()
    }
}
