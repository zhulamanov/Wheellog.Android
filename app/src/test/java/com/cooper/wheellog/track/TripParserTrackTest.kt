package com.cooper.wheellog.track

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cooper.wheellog.data.TripDao
import com.cooper.wheellog.data.TripParser
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TripParserTrackTest {
    @Test fun `trip tiles get maximum moving average and real times across midnight even without GPS`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = mockk<TripDao>(relaxed = true)
        every { dao.getTripByFileName(any()) } returns null
        startKoin { modules(module { single { dao } }) }
        try {
            val header = "date,time,speed,voltage,phase_current,current,power,torque,pwm,battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"
            fun row(date: String, time: String, speed: Int, total: Int) =
                "$date,$time,$speed,140,0,1,140,0,50,80,0,$total,30,30,0,0,,"
            val csv = listOf(header, row("2026-10-23", "23:59:58.000", 20, 10_000),
                row("2026-10-23", "23:59:59.000", 20, 10_006), row("2026-10-24", "00:00:00.000", 40, 10_017),
                row("2026-10-24", "00:05:00.000", 0, 10_017), row("2026-10-24", "00:05:01.000", 0, 10_017)).joinToString("\n")
            val parsed = TripParser.parseFile(context, "test.csv", csv.byteInputStream())
            assertEquals("", TripParser.lastError)
            assertEquals(5, parsed.first.size)
            val trip = parsed.second!!
            assertEquals(40f, trip.maxSpeed, 0.001f)
            val data = JSONObject(trip.additionalJson!!)
            assertEquals(25.0, data.getDouble("movingAverageSpeed"), 0.001)
            assertEquals(303_000L, data.getLong("endedAt") - data.getLong("startedAt"))
            assertEquals(5, trip.duration)
            assertEquals(3, data.getInt("tripStatsVersion"))
        } finally { stopKoin() }
    }
}
