package com.cooper.wheellog.track

import android.app.Application
import android.content.*
import androidx.test.core.app.ApplicationProvider
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2
import com.cooper.wheellog.*
import com.cooper.wheellog.DashboardBluetoothBinding
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TrackDashboardTest {
    private lateinit var context: Context
    private lateinit var config: AppConfig
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        config = AppConfig(context)
        startKoin { modules(module { single { config } }) }
    }
    @After fun cleanup() { DashboardBluetoothBinding.release(context); unmockkAll(); stopKoin() }
    @Test fun `record visibility migration runs once and respects later hiding`() {
        config.mainMenuButtons = arrayOf("watch", "light")
        config.migrateRecordButtonVisibility()
        assertEquals(setOf("watch", "light", "record"), config.mainMenuButtons.toSet())
        config.mainMenuButtons = arrayOf("watch")
        AppConfig(context).migrateRecordButtonVisibility()
        assertEquals(listOf("watch"), config.mainMenuButtons.toList())
    }
    @Test fun `theme recreation reuses application BLE binding until a real exit`() {
        mockkStatic(WheelData::class)
        every { WheelData.getInstance() } returns mockk<WheelData>(relaxed = true)
        val application = mockk<Context>(relaxed = true)
        every { application.applicationContext } returns application
        every { application.bindService(any(), any(), any<Int>()) } returns true
        val first = mockk<Context>(); every { first.applicationContext } returns application
        val recreated = mockk<Context>(); every { recreated.applicationContext } returns application
        DashboardBluetoothBinding.ensure(first, "AA")
        DashboardBluetoothBinding.ensure(recreated, "AA")
        verify(exactly = 1) { application.bindService(any(), any(), Context.BIND_AUTO_CREATE) }
        DashboardBluetoothBinding.release(recreated)
        verify(exactly = 1) { application.unbindService(any()) }
    }
}
