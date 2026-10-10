package io.github.peerless2012.ass.media.render

import androidx.media3.common.util.Size

internal object AssAtlasSurfaceLayout {
    data class SurfaceLayout(
        val capacity: Size,
        val originX: Int,
        val originY: Int,
        val activeWidth: Int,
        val activeHeight: Int,
        val vertexTransform: FloatArray,
    )

    fun resolve(
        activeBounds: IntArray,
        renderSize: Size,
        videoSize: Size,
        previousCapacity: Size,
    ): SurfaceLayout {
        if (activeBounds.size != 4 || renderSize.width <= 0 || renderSize.height <= 0 ||
            activeBounds[2] <= 0 || activeBounds[3] <= 0
        ) return emptyLayout(renderSize, videoSize)

        val left = activeBounds[0].coerceIn(0, renderSize.width)
        val top = activeBounds[1].coerceIn(0, renderSize.height)
        val right = (activeBounds[0].toLong() + activeBounds[2])
            .coerceIn(left.toLong(), renderSize.width.toLong()).toInt()
        val bottom = (activeBounds[1].toLong() + activeBounds[3])
            .coerceIn(top.toLong(), renderSize.height.toLong()).toInt()
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return emptyLayout(renderSize, videoSize)

        val maxWidth = renderSize.width - left
        val maxHeight = renderSize.height - top
        val reuse = previousCapacity.width >= width && previousCapacity.height >= height &&
            previousCapacity.width <= maxWidth && previousCapacity.height <= maxHeight
        val capacity = if (reuse) previousCapacity else Size(
            roundUp(width, BUCKET_SIZE).coerceAtMost(maxWidth),
            roundUp(height, BUCKET_SIZE).coerceAtMost(maxHeight),
        )
        return SurfaceLayout(
            capacity = capacity,
            originX = left,
            originY = top,
            activeWidth = width,
            activeHeight = height,
            vertexTransform = placementTransform(left, top, capacity, renderSize, videoSize),
        )
    }

    private fun emptyLayout(renderSize: Size, videoSize: Size): SurfaceLayout {
        val capacity = Size(2, 2)
        return SurfaceLayout(
            capacity = capacity,
            originX = 0,
            originY = 0,
            activeWidth = 0,
            activeHeight = 0,
            vertexTransform = placementTransform(0, 0, capacity, renderSize, videoSize),
        )
    }

    private fun placementTransform(
        originX: Int,
        originY: Int,
        capacity: Size,
        renderSize: Size,
        videoSize: Size,
    ): FloatArray {
        val safeVideoWidth = videoSize.width.coerceAtLeast(1)
        val safeVideoHeight = videoSize.height.coerceAtLeast(1)
        return floatArrayOf(
            renderSize.width.toFloat() / safeVideoWidth, 0f, 0f, 0f,
            0f, renderSize.height.toFloat() / safeVideoHeight, 0f, 0f,
            0f, 0f, 1f, 0f,
            (renderSize.width - 2f * originX) / capacity.width - 1f,
            (2f * originY + capacity.height - renderSize.height) / capacity.height,
            0f,
            1f,
        )
    }

    private fun roundUp(value: Int, bucket: Int): Int =
        ((value.toLong() + bucket - 1L) / bucket * bucket).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private const val BUCKET_SIZE = 64
}
