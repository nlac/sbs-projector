package com.nlacsoft.sbsprojector

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Manages the MediaProjection session and ImageReader pipeline.
 *
 * Strategy for low latency:
 *  - Dedicated HandlerThread for capture callbacks (off the main thread)
 *  - ImageReader with maxImages=2 (double-buffer) — always process the latest frame
 *  - RGBA_8888 pixel format for direct Bitmap wrapping (zero extra copies)
 *  - Callback delivers a Bitmap to SbsSurfaceView on the capture thread;
 *    SbsSurfaceView locks its Canvas from that same thread.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val resultCode: Int,
    private val projectionData: Intent,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onFrameAvailable: (Bitmap) -> Unit
) {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "SbsProjectorCapture"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    fun start() {
        captureThread = HandlerThread("SbsCaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            throw e
        }

        if (mediaProjection == null) {
            Log.e(TAG, "mediaProjection is null — token expired or already used?")
            throw IllegalStateException("getMediaProjection returned null")
        }

        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stop()
            }
        }, captureHandler)

        // ImageReader: 2-frame ring buffer, RGBA for zero-copy Bitmap wrapping
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                // Create bitmap — include row padding in width so strides match
                val bitmapWidth = screenWidth + rowPadding / pixelStride
                val bitmap = Bitmap.createBitmap(
                    bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop away row padding if present
                val finalBitmap = if (bitmapWidth != screenWidth) {
                    Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                        .also { bitmap.recycle() }
                } else bitmap

                onFrameAvailable(finalBitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                image?.close()
            }
        }, captureHandler)

        // Flag 0: no AUTO_MIRROR (that flag is for mirroring to external displays, unrelated here).
        // The feedback-loop protection comes from FLAG_SECURE on the overlay window, which
        // excludes it from VirtualDisplay capture at the compositor level.
        val densityDpi = context.resources.displayMetrics.densityDpi
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth, screenHeight, densityDpi,
            0,
            imageReader!!.surface,
            null, captureHandler
        )

        Log.d(TAG, "Screen capture started ${screenWidth}x${screenHeight}")
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        Log.d(TAG, "Screen capture stopped")
    }
}
