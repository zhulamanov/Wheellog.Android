package com.cooper.wheellog.track

import android.content.Context
import kotlin.math.*

enum class TrackColorMode(val key: String) {
    NONE("none"), SPEED("speed");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NONE }
}

class TrackAppearance(context: Context) {
    private val preferences = context.getSharedPreferences("track_appearance", Context.MODE_PRIVATE)
    var mode: TrackColorMode
        get() = TrackColorMode.fromKey(preferences.getString("line_color", null))
        set(value) { preferences.edit().putString("line_color", value.key).apply() }
}

object TrackVisuals {
    const val MISSING_COLOR: Int = -7693927
    fun speed(point: TrackPoint) = point.telemetry?.speedKmh ?: point.speedMps * 3.6
    fun color(point: TrackPoint, mode: TrackColorMode, solid: Int, maximumSpeed: Double): Int {
        if (mode == TrackColorMode.NONE) return solid
        val fraction = when (mode) {
            TrackColorMode.SPEED -> speed(point) / maximumSpeed.coerceAtLeast(1.0)
            else -> 0.0
        }.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: return MISSING_COLOR
        val green = intArrayOf(30, 225, 115)
        val yellow = intArrayOf(255, 206, 0)
        val red = intArrayOf(255, 0, 0)
        val start = if (fraction < .5) green else yellow
        val end = if (fraction < .5) yellow else red
        val ratio = if (fraction < .5) fraction * 2 else (fraction - .5) * 2
        fun channel(index: Int) = (start[index] + (end[index] - start[index]) * ratio).roundToInt()
        return (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    /** Keep every segment boundary, even when reducing a day-long route for rendering. */
    fun displayPoints(points: List<TrackPoint>, limit: Int = 3_000): List<TrackPoint> {
        if (points.size <= limit) return points
        val stride = ceil(points.size.toDouble() / limit.coerceAtLeast(2)).toInt()
        return points.filterIndexed { index, point -> index % stride == 0 || point.segmentStart ||
            index == points.lastIndex || points.getOrNull(index + 1)?.segmentStart == true }
    }
}

/** Playback advances by elapsed real time, independently of the GPS sampling interval. */
class TrackPlayback {
    companion object { const val SPEED = 60 }
    var position = 0.0
        private set
    private var lastTick = 0L
    fun start(time: Long, now: Long) { position = time.toDouble(); lastTick = now }
    fun advance(now: Long, end: Long): Long {
        position = (position + (now - lastTick).coerceAtLeast(0) * SPEED).coerceAtMost(end.toDouble())
        lastTick = now
        return position.toLong()
    }
}

data class TrackTimeSelection(val index: Int, val requestedTime: Long, val inGap: Boolean)
object TrackTimeline {
    const val STEPS = 10_000
    fun select(points: List<TrackPoint>, progress: Int, start: Long, end: Long): TrackTimeSelection? {
        if (points.isEmpty()) return null
        val time = start + ((end - start).coerceAtLeast(0) * (progress.coerceIn(0, STEPS) / STEPS.toDouble())).toLong()
        var low = 0
        var high = points.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (points[middle].time <= time) low = middle else high = middle - 1
        }
        val next = points.getOrNull(low + 1)
        val gap = time < points.first().time || (time > points[low].time + 15_000 &&
            (next == null || next.segmentStart || next.time - points[low].time > 30_000))
        return TrackTimeSelection(low, time, gap)
    }
}

/** Web Mercator coordinates shared by tiles and route thumbnails, including the date line. */
data class TrackWorldPoint(val x: Double, val y: Double, val point: TrackPoint)
object TrackProjection {
    fun project(points: List<TrackPoint>): List<TrackWorldPoint> {
        var lastX: Double? = null
        return points.map { point ->
            var x = (point.longitude + 180) / 360
            lastX?.let { x += round(it - x) }
            lastX = x
            val lat = Math.toRadians(point.latitude.coerceIn(-85.05112878, 85.05112878))
            TrackWorldPoint(x, (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2, point)
        }
    }
}
