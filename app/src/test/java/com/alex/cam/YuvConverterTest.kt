package com.alex.cam

import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageProxy.PlaneProxy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Checks the conversion of the camera's YUV frames into the flat layout the
 * H.264 encoder wants.
 */
class YuvConverterTest {

    /** A plane of a fake camera frame. */
    private class FakePlane(
        private val data: ByteArray,
        private val rowStride: Int,
        private val pixelStride: Int
    ) : PlaneProxy {
        private val buffer = ByteBuffer.wrap(data)

        override fun getRowStride(): Int = rowStride
        override fun getPixelStride(): Int = pixelStride
        override fun getBuffer(): ByteBuffer = buffer
    }

    /** A camera frame with a known pattern. */
    private class FakeFrame(
        y: ByteArray, u: ByteArray, v: ByteArray,
        yRow: Int, uRow: Int, vRow: Int, yPixel: Int = 1, chromaPixel: Int = 1,
        private val width: Int = 4, private val height: Int = 2
    ) : ImageProxy {
        private val planeArray: Array<PlaneProxy> = arrayOf(
            FakePlane(y, yRow, yPixel),
            FakePlane(u, uRow, chromaPixel),
            FakePlane(v, vRow, chromaPixel)
        )

        override fun getPlanes(): Array<PlaneProxy> = planeArray
        override fun getFormat(): Int = android.graphics.ImageFormat.YUV_420_888
        override fun getHeight(): Int = height
        override fun getWidth(): Int = width
        override fun getCropRect(): android.graphics.Rect = android.graphics.Rect(0, 0, width, height)
        override fun getImageInfo(): androidx.camera.core.ImageInfo = throw UnsupportedOperationException()
        override fun getImage(): android.media.Image = throw UnsupportedOperationException()
        override fun close() = Unit
        override fun setCropRect(cropRect: android.graphics.Rect?) = Unit
    }

    @Test
    fun aSimpleFrameIsCopiedRowByRow() {
        val y = ByteArray(8) { it.toByte() }
        val u = ByteArray(2) { (100 + it).toByte() }
        val v = ByteArray(2) { (200 + it).toByte() }
        val frame = FakeFrame(y, u, v, yRow = 4, uRow = 2, vRow = 2)

        val target = ByteArray(4 * 2 * 3 / 2)
        YuvConverter.convert(frame, target, 4, 2, 4, 2)

        for (i in 0 until 8) assertEquals("the luma byte $i", i.toByte(), target[i])
        assertEquals(100.toByte(), target[8])
        assertEquals(101.toByte(), target[9])
        assertEquals(200.toByte(), target[10])
        assertEquals(201.toByte(), target[11])
    }

    @Test
    fun theRowStrideIsRespected() {
        val y = ByteArray(12) { (it + 1).toByte() }
        val u = ByteArray(4) { 100.toByte() }
        val v = ByteArray(4) { 200.toByte() }
        val frame = FakeFrame(y, u, v, yRow = 6, uRow = 3, vRow = 3)

        val target = ByteArray(6 * 2 * 3 / 2)
        YuvConverter.convert(frame, target, 4, 2, 6, 2)

        assertEquals(1.toByte(), target[0])
        assertEquals(4.toByte(), target[3])
        // The second row starts after the whole first row, padding included.
        assertEquals(7.toByte(), target[6])
    }

    @Test
    fun thePixelStrideIsRespected() {
        val y = ByteArray(8) { (it * 2).toByte() }
        val u = ByteArray(2) { 100.toByte() }
        val v = ByteArray(2) { 200.toByte() }
        val frame = FakeFrame(y, u, v, yRow = 8, uRow = 2, vRow = 2, yPixel = 2, width = 4, height = 1)

        val target = ByteArray(4 * 1 * 3 / 2)
        YuvConverter.convert(frame, target, 4, 1, 4, 1)

        assertEquals(0.toByte(), target[0])
        assertEquals(4.toByte(), target[1])
        assertEquals(8.toByte(), target[2])
        assertEquals(12.toByte(), target[3])
    }

    @Test
    fun theWholeFrameIsFilled() {
        val y = ByteArray(32) { (it + 1).toByte() }
        val u = ByteArray(8) { 100.toByte() }
        val v = ByteArray(8) { 200.toByte() }
        val frame = FakeFrame(y, u, v, yRow = 8, uRow = 4, vRow = 4, width = 8, height = 4)
        val target = ByteArray(8 * 4 * 3 / 2)
        YuvConverter.convert(frame, target, 8, 4, 8, 4)

        for (i in 0 until 8 * 4) assertEquals("the luma byte $i", (i + 1).toByte(), target[i])
        for (i in 0 until 8) assertEquals("the chroma byte $i", 100.toByte(), target[8 * 4 + i])
    }
}
