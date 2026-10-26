package com.cooper.wheellog.track

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.*
import android.location.*
import android.os.*
import androidx.test.core.app.ApplicationProvider
import com.cooper.wheellog.*
import com.cooper.wheellog.data.NativeCsv
import com.cooper.wheellog.utils.Constants
import com.welie.blessed.ConnectionState
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeTrackRecordingTest {
    private lateinit var context: Context
    private lateinit var config: AppConfig
    private lateinit var provider: NativeMediaProvider
    private lateinit var wheel: WheelData
    private var controller: ServiceController<LoggingService>? = null
    private var lastLocationTime = 0L
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(LoggingService.SESSION, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("native_logging_day", Context.MODE_PRIVATE).edit().clear().commit()
        ReflectionHelpers.setStaticField(LoggingService::class.java, "automaticSuppressed", false)
        ReflectionHelpers.setStaticField(LoggingService::class.java, "wasConnected", false)
        File(context.filesDir, "tracks").deleteRecursively()
        config = AppConfig(context)
        config.logLocationData = true; config.useGps = true; config.autoLog = false
        config.continueThisDayLog = false; config.continueThisDayLogMacException = ""
        config.startAutoLoggingWhenIsMovingMore = 20f
        startKoin { modules(module { single { config } }) }
        val directory = File(context.cacheDir, "native-csv-test").apply { deleteRecursively(); mkdirs() }
        provider = NativeMediaProvider(directory)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(context.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        wheel = mockk(relaxed = true)
        every { wheel.isConnected } returns false
        every { wheel.bluetoothService } returns null
        every { wheel.mac } returns "AA:BB:CC:DD:EE:FF"
        mockkStatic(WheelData::class)
        every { WheelData.getInstance() } returns wheel
    }
    private fun start(action: String = LoggingService.START): LoggingService {
        controller = Robolectric.buildService(LoggingService::class.java).create()
        return controller!!.get().also { command(it, action) }
    }
    private fun drain(service: LoggingService) {
        shadowOf(Looper.getMainLooper()).idle()
        val handler = ReflectionHelpers.getField<Handler>(service, "worker")
        shadowOf(handler.looper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun command(service: LoggingService, action: String) {
        service.onStartCommand(Intent(context, LoggingService::class.java).setAction(action), 0, 1); drain(service)
    }
    private fun fix(service: LoggingService, offset: Double = 0.0, speed: Float = 10f, timestamp: Long = System.currentTimeMillis()) {
        service.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
            time = maxOf(timestamp, lastLocationTime + 1); lastLocationTime = time
            latitude = 56.8 + offset; longitude = 60.6; accuracy = 5f; this.speed = speed
        }); drain(service)
    }
    private fun ticks() = NativeCsv.read(provider.files.values.single().first.inputStream())
    @After fun cleanup() {
        controller?.let { command(it.get(), LoggingService.EXIT); it.destroy() }
        controller = null; unmockkAll(); stopKoin()
    }
    @Test fun `manual start ignores threshold and creates only one native CSV`() {
        val service = start()
        fix(service)
        assertTrue(LoggingService.status.value.recording)
        assertEquals(1, provider.files.size)
        assertEquals(1, ticks().size)
        assertFalse(File(context.filesDir, "tracks").exists())
        assertFalse(ticks().single().wheelDataAvailable)
        assertEquals(36.0, ticks().single().speed, .001)
    }
    @Test fun `fresh wheel PWM enters native CSV and GPS continues without stale telemetry after disconnect`() {
        val service = start()
        every { wheel.isConnected } returns true
        every { wheel.speedDouble } returns 42.0
        every { wheel.calculatedPwm } returns 73.5
        every { wheel.batteryLevel } returns 82
        LoggingService.onWheelDataDecoded(wheel); drain(service)
        fix(service)
        assertEquals(73.5, NativeCsv.points(ticks()).last().telemetry!!.pwm, .001)
        context.sendBroadcast(Intent(Constants.ACTION_BLUETOOTH_CONNECTION_STATE).setPackage(context.packageName)
            .putExtra(Constants.INTENT_EXTRA_CONNECTION_STATE, ConnectionState.DISCONNECTED.value)); drain(service)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        fix(service, .0001, timestamp = ticks().last().gpsTimestampMillis + 1_000)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        // JVM wall time is independent of Robolectric's monotonic clock. Model a real two-second GPS interval.
        fix(service, .0002, timestamp = ticks().last().gpsTimestampMillis + 2_000)
        val last = ticks().last()
        assertTrue(last.hasLocation)
        assertFalse("state=${LoggingService.status.value}; now=${System.currentTimeMillis()}; csv=${provider.files.values.single().first.readText()}", last.wheelDataAvailable)
        assertNull(NativeCsv.points(ticks()).last().telemetry)
        assertTrue(LoggingService.status.value.recording)
    }

    @Test fun `decoded packets are recorded even when a cached Bluetooth service reports disconnected`() {
        val service = start()
        val bluetooth = mockk<BluetoothService>(relaxed = true)
        every { bluetooth.connectionState } returns ConnectionState.DISCONNECTED
        every { wheel.bluetoothService } returns bluetooth
        every { wheel.isConnected } returns true
        every { wheel.lastLifeData } answers { System.currentTimeMillis() }
        every { wheel.speedDouble } returns 94.0
        every { wheel.rawSpeedDouble } returns 100.0
        every { wheel.appliedSpeedCorrectionPercent } returns -6
        every { wheel.calculatedPwm } returns 73.5
        every { wheel.voltageDouble } returns 140.6
        every { wheel.powerDouble } returns 2000.0
        // Invoke the same decoder callback as WheelData.decodeResponse.
        LoggingService.onWheelDataDecoded(wheel); drain(service)
        assertTrue(ticks().single().wheelDataAvailable)
        assertEquals(94.0, ticks().single().speed, .001)
        assertEquals(73.5, ticks().single().pwm, .001)
        fix(service)
        assertEquals(140.6, NativeCsv.points(ticks()).last().telemetry!!.voltage, .001)
        val oldPacket = System.currentTimeMillis() - 100
        context.sendBroadcast(Intent(Constants.ACTION_BLUETOOTH_CONNECTION_STATE).setPackage(context.packageName)
            .putExtra(Constants.INTENT_EXTRA_CONNECTION_STATE, ConnectionState.DISCONNECTED.value)); drain(service)
        val count = ticks().size
        every { wheel.lastLifeData } returns oldPacket
        LoggingService.onWheelDataDecoded(wheel); drain(service)
        assertEquals(count, ticks().size)
    }

    @Test fun `real WheelData decode path writes telemetry with minus six percent correction without UI receivers`() {
        config.lastMac = "AA:BB:CC:DD:EE:FF"
        config.useSpeedCorrection = true
        config.speedCorrectionPercent = -6
        val service = start()
        val live = spyk(WheelData())
        val adapter = mockk<com.cooper.wheellog.utils.BaseAdapter>(relaxed = true)
        every { adapter.decode(any()) } returns true
        every { live.adapter } returns adapter
        every { WheelData.getInstance() } returns live
        live.speed = 10_000
        live.voltage = 14_060
        live.current = 1_500
        live.setPower(200_000)
        live.temperature = 3_700
        live.output = 6_800
        live.updatePwm()
        ReflectionHelpers.callInstanceMethod<Unit>(live, "decodeResponse",
            ReflectionHelpers.ClassParameter.from(ByteArray::class.java, byteArrayOf(1)),
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        drain(service)
        val sample = ticks().single()
        assertTrue(sample.wheelDataAvailable)
        assertEquals(94.0, sample.speed, .001)
        assertEquals(140.6, sample.voltage, .001)
        assertEquals(2000.0, sample.power, .001)
        assertEquals(68.0, sample.pwm, .001)
        assertEquals(37, sample.temperature)
        fix(service)
        assertEquals(36.0, ticks().last().speedGps, .001)
        assertEquals(68.0, NativeCsv.points(ticks()).last().telemetry!!.pwm, .001)
    }

    @Test fun `recording clock retains a short stop but excludes a manual pause`() {
        val service = start()
        val time = System.currentTimeMillis()
        fix(service, timestamp = time)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); fix(service, .0001, timestamp = time + 1_000)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10)); command(service, LoggingService.PAUSE)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60)); command(service, LoggingService.RESUME)
        fix(service, .0002, timestamp = time + 2_000)
        assertEquals(11_000L, ticks().last().recordingElapsedMillis)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); fix(service, .0003, timestamp = time + 3_000)
        command(service, LoggingService.EXIT)
        assertEquals(12_000L, ticks().last().recordingElapsedMillis)
        assertFalse(ticks().last().hasLocation)
    }
    @Test fun `manual pause blocks rows and explicit resume appends to the same CSV with a segment boundary`() {
        val service = start(); fix(service)
        val id = LoggingService.currentLogFileName
        command(service, LoggingService.PAUSE)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10)); fix(service, .001)
        assertEquals(1, ticks().size)
        assertEquals(TrackPause.MANUAL, LoggingService.status.value.pauseReason)
        command(service, LoggingService.RESUME)
        fix(service, .002)
        assertEquals(id, LoggingService.currentLogFileName)
        assertEquals(2, ticks().size)
        assertTrue(NativeCsv.points(ticks()).last().segmentStart)
    }
    @Test fun `five minutes of standing adds no GPS drift and only wheel motion resumes the existing CSV`() {
        val service = start()
        for (index in 0..30) {
            if (index > 0) ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            fix(service, speed = 0f)
        }
        assertEquals(TrackPause.AUTOMATIC, LoggingService.status.value.pauseReason)
        assertEquals(0, ticks().size)
        val id = LoggingService.currentLogFileName
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); fix(service, .001)
        assertEquals(TrackPause.AUTOMATIC, LoggingService.status.value.pauseReason)
        assertEquals(0, ticks().size)
        every { wheel.isConnected } returns true
        every { wheel.speedDouble } returns 12.0
        LoggingService.onWheelDataDecoded(wheel); drain(service)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); fix(service, .0012)
        assertEquals(TrackPause.NONE, LoggingService.status.value.pauseReason)
        assertEquals(id, LoggingService.currentLogFileName)
        assertTrue(NativeCsv.points(ticks()).last().segmentStart)
    }

    @Test fun `GPS without speed auto pauses during a noisy long stop without adding route points`() {
        val service = start()
        val time = System.currentTimeMillis()
        for (index in 0..65) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            service.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
                this.time = time + index; latitude = 56.8 + (index % 4) * .00001; longitude = 60.6; accuracy = 5f
            }); drain(service)
        }
        assertEquals(TrackPause.AUTOMATIC, LoggingService.status.value.pauseReason)
        assertEquals(0, ticks().size)
        assertEquals(0.0, LoggingService.status.value.distance, .001)
    }

    @Test fun `manual resume bypasses the wheel reconnection gate after an automatic pause`() {
        val service = start()
        for (index in 0..30) { ShadowSystemClock.advanceBy(Duration.ofSeconds(10)); fix(service, speed = 0f) }
        assertEquals(TrackPause.AUTOMATIC, LoggingService.status.value.pauseReason)
        command(service, LoggingService.RESUME)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); fix(service, .001)
        assertEquals(TrackPause.NONE, LoggingService.status.value.pauseReason)
        assertEquals(1, NativeCsv.points(ticks()).size)
    }

    @Test fun `automatic pause survives process death and disconnected phone movement`() {
        val service = start()
        for (index in 0..30) { ShadowSystemClock.advanceBy(Duration.ofSeconds(10)); fix(service, speed = 0f) }
        val id = LoggingService.currentLogFileName
        controller!!.destroy(); controller = null
        val restored = start("csv.restore")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); fix(restored, .005)
        assertEquals(id, LoggingService.currentLogFileName)
        assertEquals(TrackPause.AUTOMATIC, LoggingService.status.value.pauseReason)
        assertEquals(0, ticks().size)
    }

    @Test fun `day continuation reopens the same CSV after exit without bridging the break`() {
        config.continueThisDayLog = true
        val service = start(); fix(service)
        val id = LoggingService.currentLogFileName
        val original = provider.files.values.single().first.readBytes()
        command(service, LoggingService.EXIT)
        command(service, LoggingService.EXIT) // Repeated lifecycle callbacks must be harmless.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        command(service, LoggingService.START); fix(service, .01)
        assertEquals(id, LoggingService.currentLogFileName)
        assertEquals(1, provider.files.size)
        val points = NativeCsv.points(ticks())
        assertEquals(2, points.size)
        assertTrue(points.last().segmentStart)
        assertEquals(0.0, summarizeTrack(points).distanceMeters, .001)
        assertTrue(provider.files.values.single().first.readBytes().take(original.size).toByteArray().contentEquals(original))
    }

    @Test fun `morning Stop creates a lunch trip and evening continues lunch until the next Stop`() {
        config.continueThisDayLog = true
        val service = start(); fix(service)
        val morning = LoggingService.currentLogFileName
        val originalMorning = provider.files.values.first().first.readBytes()
        command(service, LoggingService.STOP)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); command(service, LoggingService.START)
        val lunch = LoggingService.currentLogFileName
        assertNotEquals(morning, lunch)
        fix(service, .0001)
        command(service, LoggingService.EXIT)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60)); command(service, LoggingService.START)
        fix(service, .0003)
        assertEquals(lunch, LoggingService.currentLogFileName)
        assertEquals(2, provider.files.size)
        assertArrayEquals(originalMorning, provider.files.values.first().first.readBytes())
        command(service, LoggingService.STOP)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); command(service, LoggingService.START)
        assertNotEquals(lunch, LoggingService.currentLogFileName)
        assertEquals(3, provider.files.size)
    }

    @Test fun `yesterday another wheel disabled option or deleted file never append to the prior day trip`() {
        config.continueThisDayLog = true
        val service = start(); fix(service)
        command(service, LoggingService.EXIT)
        val day = context.getSharedPreferences("native_logging_day", Context.MODE_PRIVATE)
        day.edit().putString("day", "2000-01-01").commit()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); command(service, LoggingService.START)
        assertEquals(2, provider.files.size)
        command(service, LoggingService.EXIT)
        every { wheel.mac } returns "11:22:33:44:55:66"
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); command(service, LoggingService.START)
        assertEquals(3, provider.files.size)
        command(service, LoggingService.EXIT)
        config.continueThisDayLog = false
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); command(service, LoggingService.START)
        assertEquals(4, provider.files.size)
        command(service, LoggingService.EXIT)
        config.continueThisDayLog = true
        provider.files.values.last().first.delete()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); command(service, LoggingService.START)
        assertEquals(5, provider.files.size)
    }
    @Test fun `process restart repairs partial CSV tail and restores manual pause instead of opening a new trip`() {
        val first = start(); fix(first); command(first, LoggingService.PAUSE)
        val id = LoggingService.currentLogFileName
        controller!!.destroy(); controller = null
        provider.files.values.single().first.appendText("2026-10-partial")
        val restored = start("csv.restore")
        assertEquals(id, LoggingService.currentLogFileName)
        assertEquals(TrackPause.MANUAL, LoggingService.status.value.pauseReason)
        assertEquals(1, ticks().size)
        command(restored, LoggingService.RESUME)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2)); fix(restored, .002)
        assertEquals("state=${LoggingService.status.value}; now=${System.currentTimeMillis()}; csv=${provider.files.values.single().first.readText()}", 2, ticks().size)
        assertEquals(1, provider.files.size)
    }
    @Test fun `location master off finalizes active recording and unsubscribes GPS`() {
        val service = start(); fix(service)
        config.logLocationData = false; drain(service)
        assertFalse(LoggingService.status.value.recording)
        assertFalse(context.getSharedPreferences(LoggingService.SESSION, Context.MODE_PRIVATE).contains("file"))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); fix(service, .001)
        assertEquals(1, ticks().size)
    }
    @Test fun `explicit stop finalizes the session and a later manual start creates another CSV`() {
        val service = start(); fix(service)
        val first = LoggingService.currentLogFileName
        command(service, LoggingService.STOP)
        assertFalse(context.getSharedPreferences(LoggingService.SESSION, Context.MODE_PRIVATE).contains("file"))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); command(service, LoggingService.START)
        assertNotEquals(first, LoggingService.currentLogFileName)
        assertEquals(2, provider.files.size)
    }
    @Test fun `automatic logging starts only above its threshold while manual starts remain independent`() {
        config.autoLog = true
        every { wheel.isConnected } returns true
        every { wheel.speedDouble } returns 20.0
        LoggingService.maybeAutoStart(context, config)
        assertNull(shadowOf(context as Application).nextStartedService)
        every { wheel.speedDouble } returns 20.1
        LoggingService.maybeAutoStart(context, config)
        assertEquals(LoggingService.START, shadowOf(context as Application).nextStartedService.action)
    }
    @Test @Config(sdk = [35]) fun `native CSV recorder starts on Android fifteen and has no notification actions`() {
        val service = start(); fix(service)
        assertTrue(LoggingService.status.value.recording)
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(Constants.MAIN_NOTIFICATION_ID)
        assertTrue(notification.actions.isNullOrEmpty())
        assertEquals(1, ticks().size)
    }

    @Test fun `obsolete MAC exception does not block same day continuation after Exit`() {
        config.continueThisDayLog = true
        config.continueThisDayLogMacException = "AA:BB:CC:DD:EE:FF"
        val service = start(); fix(service)
        val id = LoggingService.currentLogFileName
        command(service, LoggingService.EXIT)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        command(service, LoggingService.START)
        assertEquals(id, LoggingService.currentLogFileName)
        assertEquals(1, provider.files.size)
    }
}
