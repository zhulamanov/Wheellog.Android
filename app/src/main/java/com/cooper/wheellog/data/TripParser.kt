package com.cooper.wheellog.data

import android.content.Context
import android.net.Uri
import android.os.Build
import com.cooper.wheellog.R
import com.cooper.wheellog.utils.LogHeaderEnum
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale
import org.json.JSONObject

object TripParser: KoinComponent {
    private val dao: TripDao by inject()
    private val errors = ThreadLocal<String?>()
    private var lastErrorValue: String?
        get() = errors.get()
        set(value) { errors.set(value) }

    val lastError: String
        get() {
            val res = lastErrorValue ?: ""
            lastErrorValue = null
            return res
        }

    @Synchronized fun parseFile(context: Context, fileName: String, path: String, uri: Uri): Pair<List<LogTick>, TripDataDbEntry?> {
        lastErrorValue = null
        val inputStream: InputStream?
        try {
            inputStream = if (uri.scheme == "file") {
                FileInputStream(File(requireNotNull(uri.path)))
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && path.isNotBlank() && path != "undefined") {
                // Android 9 or less
                FileInputStream(File(path))
            } else {
                // Android 10+
                context.contentResolver.openInputStream(uri)
            }
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
            return Pair(emptyList(), null)
        }
        return parseFile(context, fileName, inputStream)
    }

    @Synchronized fun parseFile(context: Context, fileName: String, inputStream: InputStream?): Pair<List<LogTick>, TripDataDbEntry?> {
        lastErrorValue = null
        if (inputStream == null) {
            lastErrorValue = context.getString(R.string.error_inputstream_null)
            Timber.wtf(lastErrorValue)
            return Pair(emptyList(), null)
        }

        val resultList = try { NativeCsv.read(inputStream) } catch (error: Exception) {
            lastErrorValue = error.localizedMessage
            return Pair(emptyList(), null)
        }
        if (resultList.isEmpty()) return Pair(emptyList(), dao.getTripByFileName(fileName))

        var trip = dao.getTripByFileName(fileName)
        if (trip == null) {
            trip = TripDataDbEntry(fileName = fileName)
            dao.insert(trip)
        }
        try {
            val first = resultList.first()
            val last = resultList.last()
            trip.apply {
                // Preserve the stock summaries of historical CSVs. Extended logs
                // use absolute timestamps, including precise midnight crossings.
                duration = if (first.legacyStatistics) {
                    ((last.time - first.time) / 600.0).toInt().let { if (it < 0) it + 1440 else it }
                } else ((last.timestampMillis - first.timestampMillis).coerceAtLeast(0) / 60_000).toInt()
                var timeToExclude = 0f
                var beforeTime = first.time // 1/10 sec
                var firstTotalDistance = 0

                resultList.forEach {
                    // If time between ticks more than 1 sec, we need to exclude this time
                    // or if time between ticks more than -1 hour (360 sec) next day ticks will be excluded
                    val timeBetween = it.time - beforeTime
                    if (-3600_0 > timeBetween || timeBetween > 10) {
                        timeToExclude += timeBetween
                    }
                    beforeTime = it.time

                    if (firstTotalDistance == 0 && it.totalDistance > 0) {
                        firstTotalDistance = it.totalDistance
                    }
                    maxSpeedGps = maxSpeedGps.coerceAtLeast(it.speedGps.toFloat())
                    maxCurrent = maxCurrent.coerceAtLeast(it.current.toFloat())
                    maxPwm = maxPwm.coerceAtLeast(it.pwm.toFloat())
                }

                val maxTotalDistance = resultList.subList(0.coerceAtLeast(resultList.size - 10), resultList.size).maxOf { it.totalDistance }
                val logDuration = if (first.legacyStatistics) (last.time - first.time - timeToExclude) / 600
                else resultList.zipWithNext().sumOf { (before, after) ->
                    ((after.timestampMillis - before.timestampMillis) / 1000.0).takeIf { it > 0 && it <= 10 } ?: 0.0
                }.toFloat() / 60f
                distance = if (first.legacyStatistics) maxTotalDistance - firstTotalDistance else
                    maxOf(maxTotalDistance - firstTotalDistance, com.cooper.wheellog.track.summarizeTrack(NativeCsv.points(resultList)).distanceMeters.toInt()).coerceAtLeast(0)
                val powers = resultList.map { it.power }
                maxPower = powers.max().toFloat()
                val speeds = resultList.map { it.speed }
                maxSpeed = speeds.max().toFloat()
                avgSpeed = speeds.average().toFloat()
                val moving = TripSpeedStats.calculate(resultList.map { SpeedSample(it.timestampMillis, it.speed) })
                additionalJson = (runCatching { JSONObject(additionalJson ?: "{}") }.getOrDefault(JSONObject())).apply {
                    put("tripStatsVersion", 3)
                    put("movingAverageSpeed", moving.movingAverage)
                    put("movingSeconds", moving.movingSeconds)
                    put("startedAt", first.timestampMillis)
                    put("endedAt", last.timestampMillis)
                }.toString()
                consumptionTotal = powers.average().toFloat() * logDuration / 60F
                consumptionByKm = if (distance > 0) {
                    consumptionTotal * 1000F / distance
                } else {
                    0F
                }
            }
            dao.update(trip)
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
        }

        return Pair(resultList, trip)
    }
}
