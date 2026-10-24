package com.cooper.wheellog.track

import com.cooper.wheellog.data.NativeCsv
import com.cooper.wheellog.data.NativeWheelSample
import com.cooper.wheellog.data.TripValues
import org.junit.Assert.*
import org.junit.Test

class TripValuesTest {
    private val start = 1_700_000_000_000L
    private fun wheel(speed: Double = 100.0, voltage: String = "149", phase: String = "25", motor: String = "60") =
        NativeWheelSample(listOf("$speed", voltage, phase, "15", "2000", "0", "58.92", "97", "100", "10000", "33", motor, "0", "0", "", ""),
            TrackTelemetry(speed, 97, 149.0, 33, 58.92))
    private fun row(offset: Long, speedMps: Float, sample: NativeWheelSample? = wheel(), segment: Boolean = false) =
        NativeCsv.row(start + offset, TrackPoint(start + offset, 56.8 + offset / 1e9, 60.6, 200.0, 5f, speedMps, segment), sample, 0.0, segment)
    private fun read(vararg rows: String) = NativeCsv.read((NativeCsv.HEADER + "\n" + rows.joinToString("\n")).byteInputStream())

    @Test fun `GPS averages are time weighted exclude standing only for moving average and never use wheel speed`() {
        val values = TripValues.calculate(read(row(0, 0f), row(1_000, 0f), row(2_000, 10f), row(3_000, 10f)))
        assertEquals(36.0, values.gpsMaximum!!, .001)
        assertEquals(18.0, values.gpsAverage!!, .001)
        assertEquals(27.0, values.gpsMovingAverage!!, .001)
        assertEquals(2.0, values.movingSeconds!!, .001)
        assertEquals(100.0, values.wheelMaximum!!, .001)
    }

    @Test fun `GPS gaps pauses and duplicate fixes cannot inflate averaging time`() {
        val csv = read(row(0, 10f), row(1_000, 10f), row(1_000, 10f), row(600_000, 20f, segment = true), row(601_000, 20f))
        val values = TripValues.calculate(csv)
        assertEquals(54.0, values.gpsAverage!!, .001)
        assertEquals(54.0, values.gpsMovingAverage!!, .001)
        assertEquals(2.0, values.movingSeconds!!, .001)
    }

    @Test fun `phase motor and voltage summaries come from recorded telemetry`() {
        val values = TripValues.calculate(read(row(0, 10f, wheel()), row(1_000, 10f, wheel(voltage = "144", phase = "30", motor = "65"))))
        assertEquals(30.0, values.maxPhaseCurrent!!, .001)
        assertEquals(65.0, values.maxMotorTemperature!!, .001)
        assertEquals(144.0, values.minVoltage!!, .001)
        assertEquals(58.92, values.maxPwm!!, .001)
    }

    @Test fun `missing optional columns stay unavailable rather than fabricated zeros`() {
        val ticks = NativeCsv.read("date,time,speed\n2026-10-24,12:00:00.000,20\n2026-10-24,12:00:01.000,30".byteInputStream())
        val values = TripValues.calculate(ticks)
        assertNull(values.gpsMaximum); assertNull(values.gpsAverage); assertNull(values.gpsMovingAverage)
        assertNull(values.maxPhaseCurrent); assertNull(values.maxMotorTemperature)
        assertNull(values.maxPower); assertNull(values.maxCurrent); assertNull(values.maxPwm)
        assertNull(values.minVoltage); assertNull(values.maxTemperature)
        assertEquals(30.0, values.wheelMaximum!!, .001)
    }

    @Test fun `GPS only rows never create wheel maxima and real zero values remain valid`() {
        val onlyGps = TripValues.calculate(read(row(0, 0f, null), row(1_000, 0f, null)))
        assertNull(onlyGps.wheelMaximum); assertNull(onlyGps.maxPwm)
        assertEquals(0.0, onlyGps.gpsAverage!!, .001)
        val zero = wheel().copy(columns = wheel().columns.toMutableList().apply { this[6] = "0"; this[10] = "0" })
        val values = TripValues.calculate(read(row(0, 0f, zero)))
        assertEquals(0.0, values.maxPwm!!, .001)
        assertEquals(0.0, values.maxTemperature!!, .001)
    }

    @Test fun `corrected CSV stores raw wheel speed and correction without altering GPS`() {
        val sample = wheel(90.0).copy(rawSpeedKmh = 100.0, correctionPercent = -10)
        val line = row(0, 25f, sample)
        val cells = NativeCsv.HEADER.split(',').zip(line.split(',')).toMap()
        assertEquals("100.0", cells["wheel_speed_raw"])
        assertEquals("-10", cells["speed_correction_percent"])
        assertEquals(90.0, cells.getValue("speed").toDouble(), .001)
        assertEquals(90.0, cells.getValue("gps_speed").toDouble(), .001)
        assertEquals(NativeCsv.HEADER.split(',').size, line.split(',').size)
    }
}
