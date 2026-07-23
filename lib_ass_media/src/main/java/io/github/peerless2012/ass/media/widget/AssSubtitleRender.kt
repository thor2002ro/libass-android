package io.github.peerless2012.ass.media.widget

interface AssSubtitleRender {
    /** Requests a subtitle frame for a Media3 presentation timestamp in microseconds. */
    fun requestRender(presentationTimeUs: Long)
}
