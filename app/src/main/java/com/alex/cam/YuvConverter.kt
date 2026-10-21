package com.alex.cam

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Converts the camera's YUV_420_888 frames into the flat I420 layout the
 * H.264 encoder wants.
 *
 * The camera delivers its planes with a row stride and sometimes with a pixel
 * stride - both have to be taken into account, or the picture comes out
 * black.
 */
object YuvConverter {

    /**
     * Copies the luma and the chroma planes of a frame into a flat buffer.
     *
     * @param frame the camera frame
     * @param target the buffer to write into
     * @param width the width of the frame
     * @param height the height of the frame
     * @param rowStride the distance between two rows in the target
     * @param sliceHeight the number of luma rows in the target
     */
    fun convert(
        frame: ImageProxy,
        target: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        sliceHeight: Int
    ) {
        val planes = frame.planes
        if (planes.size < 3) return
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val y = yPlane.buffer
        val u = uPlane.buffer
        val v = vPlane.buffer

        // The luma.
        if (yPlane.pixelStride == 1) {
            for (row in 0 until height) {
                val from = row * yPlane.rowStride
                if (from + width > y.limit()) break
                y.position(from)
                y.get(target, row * rowStride, width)
            }
        } else {
            for (row in 0 until height) {
                val base = row * yPlane.rowStride
                val out = row * rowStride
                for (col in 0 until width) {
                    val index = base + col * yPlane.pixelStride
                    if (index >= y.limit()) break
                    target[out + col] = y.get(index)
                }
            }
        }

        // The chroma: the U plane, then the V plane.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaStride = rowStride / 2
        val lumaSize = rowStride * sliceHeight

        for (row in 0 until chromaHeight) {
            val base = row * uPlane.rowStride
            val out = lumaSize + row * chromaStride
            for (col in 0 until chromaWidth) {
                val index = base + col * uPlane.pixelStride
                if (index >= u.limit()) break
                target[out + col] = u.get(index)
            }
        }
        for (row in 0 until chromaHeight) {
            val base = row * vPlane.rowStride
            val out = lumaSize + chromaStride * chromaHeight + row * chromaStride
            for (col in 0 until chromaWidth) {
                val index = base + col * vPlane.pixelStride
                if (index >= v.limit()) break
                target[out + col] = v.get(index)
            }
        }
    }
}
