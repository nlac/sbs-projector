package com.nlacsoft.sbsprojector

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for SbsSurfaceView.computeDestRectCoords.
 *
 * Fixture: 
 * 100×100 source, 
 * 400x200 canvas, 200×200 half-canvas (fills the half exactly at scale=2).
 * Base letterbox result before transforms: left=0, top=0, right=200, bottom=200.
 * quarterW = availW/2 = 100.
 */
class ComputeDestRectTest {

    private fun project(
        shrink: Float,
        closeness: Float,
        closenessSign: Float = +1f,
        srcWidth: Int = 100,
        srcHeight: Int = 100,
        dstLeft: Int = 0,
        dstRight: Int = 200,
        dstHeight: Int = 200
    ) = SbsSurfaceView.computeDestRectCoords(
        srcWidth, 
        srcHeight, 
        dstLeft, 
        dstRight, 
        dstHeight, 
        shrink, 
        closeness, 
        closenessSign
    )

    private fun assertRect(expected: FloatArray, actual: FloatArray, delta: Float = 0.001f) {
        val labels = listOf("left", "top", "right", "bottom")
        expected.zip(actual).forEachIndexed { i, (exp, act) ->
            assertEquals("${labels[i]} mismatch", exp, act, delta)
        }
    }

    // -------------------------------------------------------------------------
    // Left side (closenessSign = +1)
    // -------------------------------------------------------------------------

    @Test fun `shrink=1 closeness=1 left`() {
        // No shrink, no shift → fills the half exactly
        assertRect(floatArrayOf(0f, 0f, 200f, 200f), project(1f, 1f))
    }

    @Test fun `shrink=1 closeness=0_5 left`() {
        // dx = +1 * 100 * 0.5 = +50
        assertRect(floatArrayOf(50f, 0f, 250f, 200f), project(1f, 0.5f))
    }

    @Test fun `shrink=1 closeness=0 left`() {
        // dx = +1 * 100 * 1.0 = +100
        assertRect(floatArrayOf(100f, 0f, 300f, 200f), project(1f, 0f))
    }

    @Test fun `shrink=0_5 closeness=1 left`() {
        // inset 50px each side → (50,50,150,150), no shift
        assertRect(floatArrayOf(50f, 50f, 150f, 150f), project(0.5f, 1f))
    }

    @Test fun `shrink=0_5 closeness=0_5 left`() {
        // inset → (50,50,150,150), dx = +50 → (100,50,200,150)
        assertRect(floatArrayOf(100f, 50f, 200f, 150f), project(0.5f, 0.5f))
    }

    @Test fun `shrink=0_5 closeness=0 left`() {
        // inset → (50,50,150,150), dx = +100 → (150,50,250,150)
        assertRect(floatArrayOf(150f, 50f, 250f, 150f), project(0.5f, 0f))
    }

    // -------------------------------------------------------------------------
    // Right side (closenessSign = -1) — symmetric counterpart
    // -------------------------------------------------------------------------

    @Test fun `shrink=1 closeness=1 right`() {
        assertRect(floatArrayOf(200f, 0f, 400f, 200f), project(1f, 1f, -1f, 100, 100, 200, 400, 200))
    }

    @Test fun `shrink=1 closeness=0_5 right`() {
        // dx = -1 * 100 * 0.5 = -50
        assertRect(floatArrayOf(150f, 0f, 350f, 200f), project(1f, 0.5f, -1f, 100, 100, 200, 400, 200))
    }

    @Test fun `shrink=0_5 closeness=0_5 right`() {
        // inset → (50,50,150,150), dx = -50 → (0,50,100,150)
        assertRect(floatArrayOf(200f, 50f, 300f, 150f), project(0.5f, 0.5f, -1f, 100, 100, 200, 400, 200))
    }

    // -------------------------------------------------------------------------
    // Letterbox — non-square source should be pillarboxed / letterboxed
    // -------------------------------------------------------------------------

    @Test fun `letterbox landscape source`() {
        // srcW=200, srcH=100 (2:1) in 200x200 half → scale=1, drawH=100 → top/bottom padding 50
        val r = project(1f, 1f, +1f, srcWidth = 200, srcHeight = 100)
        assertRect(floatArrayOf(0f, 50f, 200f, 150f), r)
    }

    @Test fun `letterbox portrait source`() {
        // srcW=100, srcH=200 (1:2) in 200x200 half → scale=1, drawW=100 → left/right padding 50
        val r = project(1f, 1f, +1f, srcWidth = 100, srcHeight = 200)
        assertRect(floatArrayOf(50f, 0f, 150f, 200f), r)
    }
}
