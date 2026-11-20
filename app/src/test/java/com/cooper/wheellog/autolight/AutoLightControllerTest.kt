package com.cooper.wheellog.autolight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLightControllerTest {
    private class Ride {
        val controller = AutoLightController()
        val commands = mutableListOf<Boolean>()
        var options = AutoLightSettings(enabled = true, autoOff = true)
        var manual: ManualHeadlightCommand? = null
        var acceptsCommands = true

        fun sample(time: Long, speed: Double, pitch: Double? = 0.0, minute: Int = 0) {
            controller.update(options, AutoLightSample(time, minute, speed, pitch), manual) {
                commands += it
                acceptsCommands
            }
        }

        fun start() {
            sample(0, 5.0)
            sample(500, 5.0)
        }
    }

    @Test fun `only sustained threshold crossings switch the light and noisy low speed does not stop it`() {
        val ride = Ride()
        ride.sample(0, 5.0)
        ride.sample(400, 4.9)
        ride.sample(500, -5.0)
        ride.sample(1000, -5.0)
        assertEquals(listOf(true), ride.commands)
        ride.sample(1500, 1.0)
        ride.sample(3000, 1.1)
        ride.sample(3500, 1.0)
        ride.sample(5000, 1.0)
        assertEquals(listOf(true), ride.commands)
        ride.sample(5500, 1.0)
        ride.sample(6500, 0.0)
        assertEquals(listOf(true, false), ride.commands)
    }

    @Test fun `both switches default off and disabling automation leaves the lamp as it is`() {
        val defaults = AutoLightSettings()
        assertFalse(defaults.enabled)
        assertFalse(defaults.autoOff)
        val ride = Ride()
        ride.options = defaults
        ride.start()
        assertTrue(ride.commands.isEmpty())
        ride.options = defaults.copy(enabled = true)
        ride.sample(1000, 5.0)
        ride.sample(1500, 5.0)
        ride.sample(2000, 0.0)
        ride.sample(4000, 0.0)
        ride.options = defaults
        ride.sample(4500, 0.0)
        assertEquals(listOf(true), ride.commands)
    }

    @Test fun `delay starts at a stop is reset by movement and cannot elapse during a telemetry gap`() {
        val ride = Ride()
        ride.options = ride.options.copy(offMode = AutoLightOffMode.DELAY, offSeconds = 5)
        ride.start()
        ride.sample(1000, 0.0)
        ride.sample(2000, 0.0)
        ride.sample(3000, 1.5)
        ride.sample(4000, 0.0)
        ride.sample(12000, 0.0)
        for (time in 13000L..16000L step 1000) ride.sample(time, 0.0)
        assertEquals(listOf(true), ride.commands)
        ride.sample(17000, 0.0)
        assertEquals(listOf(true, false), ride.commands)
    }

    @Test fun `pitch can turn off a parked wheel and never a wheel still moving`() {
        val ride = Ride()
        ride.options = ride.options.copy(offMode = AutoLightOffMode.PITCH)
        ride.start()
        ride.sample(1000, 10.0, 30.0)
        ride.sample(3000, 10.0, 30.0)
        ride.sample(4000, 0.0, 19.0)
        ride.sample(4500, 0.0, -20.0)
        ride.sample(6000, 0.0, -20.0)
        assertEquals(listOf(true), ride.commands)
        ride.sample(6500, 0.0, -20.0)
        assertEquals(listOf(true, false), ride.commands)
    }

    @Test fun `night schedule spans midnight and its end never switches off a moving wheel`() {
        val ride = Ride()
        ride.options = ride.options.copy(scheduled = true)
        assertFalse(ride.options.canTurnOnAt(12 * 60))
        assertTrue(ride.options.canTurnOnAt(18 * 60))
        assertTrue(ride.options.canTurnOnAt(0))
        assertFalse(ride.options.canTurnOnAt(8 * 60))
        assertTrue(ride.options.copy(endMinute = 18 * 60).canTurnOnAt(12 * 60))
        ride.sample(0, 6.0, minute = 17 * 60)
        ride.sample(1000, 6.0, minute = 17 * 60)
        assertTrue(ride.commands.isEmpty())
        ride.sample(2000, 6.0, minute = 18 * 60)
        ride.sample(2500, 6.0, minute = 18 * 60)
        ride.sample(3000, 6.0, minute = 8 * 60)
        assertEquals(listOf(true), ride.commands)
        ride.sample(4000, 0.0, minute = 8 * 60)
        ride.sample(6000, 0.0, minute = 8 * 60)
        assertEquals(listOf(true, false), ride.commands)
    }

    @Test fun `manual on retains the lamp and manual off inhibits automation until a stop and restart`() {
        val ride = Ride()
        ride.start()
        ride.manual = ManualHeadlightCommand(true)
        ride.sample(1000, 0.0)
        ride.sample(3000, 0.0)
        ride.manual = ManualHeadlightCommand(false)
        ride.sample(3500, 6.0)
        ride.sample(4500, 6.0)
        assertEquals(listOf(true), ride.commands)
        ride.sample(5000, 0.0)
        ride.sample(7000, 0.0)
        ride.sample(8000, 6.0)
        ride.sample(8500, 6.0)
        assertEquals(listOf(true, true), ride.commands)
    }

    @Test fun `failed writes stay retryable without flooding and resetting never switches the lamp off`() {
        val ride = Ride()
        ride.acceptsCommands = false
        ride.start()
        ride.sample(600, 5.0)
        assertEquals(listOf(true), ride.commands)
        ride.acceptsCommands = true
        ride.sample(1500, 5.0)
        assertEquals(listOf(true, true), ride.commands)
        ride.controller.reset()
        ride.sample(2500, 0.0)
        ride.sample(4500, 0.0)
        assertEquals(listOf(true, true), ride.commands)
    }

    @Test fun `missing pitch permits speed rules and cannot trigger the angle rule`() {
        val ride = Ride()
        val angleOptions = ride.options.copy(offMode = AutoLightOffMode.PITCH)
        ride.options = angleOptions
        ride.sample(0, 5.0, null)
        ride.sample(500, 5.0, null)
        ride.sample(1000, 0.0, null)
        ride.sample(3000, 0.0, null)
        assertEquals(listOf(true), ride.commands)
        ride.options = angleOptions.forWheel(supportsPitch = false)
        ride.sample(3500, 0.0, null)
        ride.sample(5500, 0.0, null)
        assertEquals(listOf(true, false), ride.commands)
    }

    @Test fun `low on threshold cannot overlap the off thresholds`() {
        val ride = Ride()
        ride.options = ride.options.copy(onKmh = 1, offKmh = 3).normalized()
        assertEquals(0, ride.options.offKmh)
        ride.start()
        ride.options = ride.options.copy(offMode = AutoLightOffMode.PITCH)
        ride.sample(1000, 1.0, 40.0)
        ride.sample(3000, 1.0, 40.0)
        assertEquals(listOf(true), ride.commands)
    }

    @Test fun `and requires a currently held angle even if it was reached earlier in the stop`() {
        val ride = Ride()
        ride.options = ride.options.copy(offMode = AutoLightOffMode.DELAY_AND_PITCH, offSeconds = 5)
        ride.start()
        ride.sample(1000, 0.0, 20.0)
        ride.sample(3000, 0.0, 20.0)
        ride.sample(4000, 0.0, 0.0)
        ride.sample(6000, 0.0, 0.0)
        assertEquals(listOf(true), ride.commands)
        ride.sample(6500, 0.0, 20.0)
        ride.sample(8500, 0.0, 20.0)
        assertEquals(listOf(true, false), ride.commands)
    }

    @Test fun `and switches off when the timer ends if the sustained angle came first`() {
        val ride = Ride()
        ride.options = ride.options.copy(offMode = AutoLightOffMode.DELAY_AND_PITCH, offSeconds = 5)
        ride.start()
        for (time in 1000L..5000L step 1000) ride.sample(time, 0.0, -20.0)
        assertEquals(listOf(true), ride.commands)
        ride.sample(6000, 0.0, -20.0)
        assertEquals(listOf(true, false), ride.commands)
    }

    @Test fun `or uses the first condition and moving prevents either condition from accumulating`() {
        val settings = AutoLightSettings(enabled = true, autoOff = true, offSeconds = 5,
            offMode = AutoLightOffMode.DELAY_OR_PITCH)
        val angleFirst = Ride().apply { options = settings; start() }
        angleFirst.sample(1000, 0.0, 20.0)
        angleFirst.sample(2000, 2.0, 20.0)
        angleFirst.sample(3000, 0.0, 20.0)
        angleFirst.sample(4000, 0.0, 20.0)
        assertEquals(listOf(true), angleFirst.commands)
        angleFirst.sample(5000, 0.0, 20.0)
        assertEquals(listOf(true, false), angleFirst.commands)

        val delayFirst = Ride().apply { options = settings; start() }
        for (time in 1000L..5000L step 1000) delayFirst.sample(time, 0.0, null)
        assertEquals(listOf(true), delayFirst.commands)
        delayFirst.sample(6000, 0.0, null)
        assertEquals(listOf(true, false), delayFirst.commands)
    }

    @Test fun `unsupported angle preserves the requirement for and and only uses delay for or`() {
        val settings = AutoLightSettings(enabled = true, autoOff = true, offSeconds = 5,
            offMode = AutoLightOffMode.DELAY_AND_PITCH)
        val adapted = settings.forWheel(supportsPitch = false)
        assertFalse(adapted.autoOff)
        assertEquals(AutoLightOffMode.DELAY_AND_PITCH, adapted.offMode)
        val ride = Ride().apply { options = adapted; start() }
        for (time in 1000L..7000L step 1000) ride.sample(time, 0.0, null)
        assertEquals(listOf(true), ride.commands)
        val either = settings.copy(offMode = AutoLightOffMode.DELAY_OR_PITCH).forWheel(supportsPitch = false)
        assertEquals(AutoLightOffMode.DELAY, either.offMode)
        assertTrue(either.autoOff)
    }
}
