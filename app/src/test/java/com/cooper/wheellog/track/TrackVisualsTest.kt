package com.cooper.wheellog.track

import org.junit.Assert.*
import org.junit.Test

class TrackVisualsTest {
    private fun point(time: Long, segment: Boolean = false, pwm: Double? = null) =
        TrackPoint(time, 56.8, 60.6, null, 5f, 10f, segment,
            pwm?.let { TrackTelemetry(36.0, 80, 140.0, 30, it) })

    @Test fun `unknown or absent color setting is always no gradient`() {
        assertEquals(TrackColorMode.NONE, TrackColorMode.fromKey(null))
        assertEquals(TrackColorMode.NONE, TrackColorMode.fromKey("invalid"))
        assertEquals(TrackColorMode.SPEED, TrackColorMode.fromKey("speed"))
        assertEquals(TrackColorMode.NONE, TrackColorMode.fromKey("pwm"))
    }
    @Test fun `solid color remains unchanged and speed works without wheel telemetry`() {
        val solid = 0xff123d
        for (speed in listOf(0f, 10f, 30f)) {
            assertEquals(solid, TrackVisuals.color(point(1_000).copy(speedMps = speed), TrackColorMode.NONE, solid, 100.0))
        }
        assertEquals(0xffff0000.toInt(), TrackVisuals.color(point(1_000).copy(speedMps = 10f), TrackColorMode.SPEED, solid, 36.0))
    }
    @Test fun `playback uses real elapsed time at fixed sixty times speed`() {
        val playback = TrackPlayback()
        assertEquals(60, TrackPlayback.SPEED)
        playback.start(1_000, 500)
        assertEquals(8_380L, playback.advance(623, 1_000_000))
        assertEquals(67_000L, playback.advance(1_600, 1_000_000))
    }
    @Test fun `sixty times playback resumes without counting paused time and stops at the end`() {
        val playback = TrackPlayback()
        playback.start(1_000, 0)
        assertEquals(61_000L, playback.advance(1_000, 100_000))
        playback.start(61_000, 30_000) // pause time must not be replayed
        assertEquals(91_000L, playback.advance(30_500, 100_000))
        assertEquals(100_000L, playback.advance(31_000, 100_000))
        assertEquals(100_000L, playback.advance(40_000, 100_000))
    }
    @Test fun `timeline follows elapsed time and does not invent positions across pauses`() {
        val points = listOf(point(1_000, true), point(11_000), point(601_000, true), point(611_000))
        val middle = TrackTimeline.select(points, 5_000, 1_000, 611_000)!!
        assertEquals(306_000L, middle.requestedTime)
        assertEquals(1, middle.index)
        assertTrue(middle.inGap)
        assertEquals(0, TrackTimeline.select(points, 0, 1_000, 611_000)!!.index)
        val end = TrackTimeline.select(points, 10_000, 1_000, 611_000)!!
        assertEquals(3, end.index); assertFalse(end.inGap)
        assertNull(TrackTimeline.select(emptyList(), 50, 0, 100))
    }
    @Test fun `timeline exposes the initial GPS acquisition gap`() {
        val selected = TrackTimeline.select(listOf(point(60_000)), 0, 0, 120_000)!!
        assertTrue(selected.inGap)
        assertEquals(0L, selected.requestedTime)
    }
    @Test fun `render reduction keeps both sides of every segment boundary`() {
        val points = (0..10_000).map { point(it * 1_000L, it == 0 || it == 5_001) }
        val reduced = TrackVisuals.displayPoints(points, 100)
        assertTrue(reduced.any { it.time == 5_000_000L })
        assertTrue(reduced.any { it.time == 5_001_000L && it.segmentStart })
        assertEquals(points.first(), reduced.first())
        assertEquals(points.last(), reduced.last())
        assertTrue(reduced.size < 110)
    }
    @Test fun `thumbnail projection takes the short route across the date line`() {
        val projected = TrackProjection.project(listOf(point(1_000).copy(latitude = 0.0, longitude = 179.9),
            point(2_000).copy(latitude = 0.0, longitude = -179.9)))
        assertTrue(kotlin.math.abs(projected[1].x - projected[0].x) < .001)
        assertEquals(.5, projected.first().y, .00001)
        assertTrue(TrackProjection.project(listOf(point(1_000).copy(latitude = 90.0))).single().y.isFinite())
    }
}
