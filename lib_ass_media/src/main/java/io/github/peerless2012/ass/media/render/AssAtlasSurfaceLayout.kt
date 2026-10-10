package io.github.peerless2012.ass.media.render

import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.AssAtlasFrame

@OptIn(UnstableApi::class)
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
        frame: AssAtlasFrame,
        renderSize: Size,
        videoSize: Size,
        previousCapacity: Size,
    ): SurfaceLayout = if (frame.hasActiveBounds) {
        resolve(
            left = frame.activeBound(0),
            top = frame.activeBound(1),
            width = frame.activeBound(2),
            height = frame.activeBound(3),
            renderSize = renderSize,
            videoSize = videoSize,
            previousCapacity = previousCapacity,
        )
    } else {
        emptyLayout(renderSize, videoSize)
    }

    fun resolve(
        activeBounds: IntArray,
        renderSize: Size,
        videoSize: Size,
        previousCapacity: Size,
    ): SurfaceLayout {
        if (activeBounds.size != 4) return emptyLayout(renderSize, videoSize)
        return resolve(
            left = activeBounds[0],
            top = activeBounds[1],
            width = activeBounds[2],
            height = activeBounds[3],
            renderSize = renderSize,
            videoSize = videoSize,
            previousCapacity = previousCapacity,
        )
    }

    private fun resolve(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        renderSize: Size,
        videoSize: Size,
        previousCapacity: Size,
    ): SurfaceLayout {
        if (renderSize.width <= 0 || renderSize.height <= 0 || width <= 0 || height <= 0
        ) return emptyLayout(renderSize, videoSize)

        val boundedLeft = left.coerceIn(0, renderSize.width)
        val boundedTop = top.coerceIn(0, renderSize.height)
        val right = (left.toLong() + width).coerceIn(boundedLeft.toLong(), renderSize.width.toLong()).toInt()
        val bottom = (top.toLong() + height).coerceIn(boundedTop.toLong(), renderSize.height.toLong()).toInt()
        val boundedWidth = right - boundedLeft
        val boundedHeight = bottom - boundedTop
        if (boundedWidth <= 0 || boundedHeight <= 0) return emptyLayout(renderSize, videoSize)

        val maxWidth = renderSize.width - boundedLeft
        val maxHeight = renderSize.height - boundedTop
        val reuse = previousCapacity.width >= boundedWidth && previousCapacity.height >= boundedHeight &&
            previousCapacity.width <= maxWidth && previousCapacity.height <= maxHeight
        val capacity = if (reuse) previousCapacity else Size(
            roundUp(boundedWidth, BUCKET_SIZE).coerceAtMost(maxWidth),
            roundUp(boundedHeight, BUCKET_SIZE).coerceAtMost(maxHeight),
        )
        return SurfaceLayout(
            capacity = capacity,
            originX = boundedLeft,
            originY = boundedTop,
            activeWidth = boundedWidth,
            activeHeight = boundedHeight,
            vertexTransform = placementTransform(boundedLeft, boundedTop, capacity, renderSize, videoSize),
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
