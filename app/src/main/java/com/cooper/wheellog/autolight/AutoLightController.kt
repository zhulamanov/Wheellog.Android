package com.cooper.wheellog.autolight

import kotlin.math.abs

internal class ManualHeadlightCommand(val on: Boolean)

internal data class AutoLightSample(
    val elapsedMs: Long,
    val minuteOfDay: Int,
    val speedKmh: Double,
    val pitchDegrees: Double?
)

/** The Bluetooth service calls this only after decoding fresh riding telemetry. */
internal class AutoLightController {
    private var settings: AutoLightSettings? = null
    private var lastSampleAt: Long? = null
    private val motionHold = Hold()
    private val pitchHold = Hold()
    private var lastAttemptAt: Long? = null
    private var manualCommand: ManualHeadlightCommand? = null
    private var manuallyOn = false
    private var waitForStop = false
    private var turnedOnByAutomation = false

    fun reset() {
        settings = null
        lastSampleAt = null
        resetHolds()
        lastAttemptAt = null
        manualCommand = null
        manuallyOn = false
        waitForStop = false
        turnedOnByAutomation = false
    }

    fun update(
        options: AutoLightSettings,
        sample: AutoLightSample,
        manual: ManualHeadlightCommand?,
        send: (on: Boolean) -> Boolean
    ) {
        if (manual !== manualCommand) {
            manualCommand = manual
            manuallyOn = manual?.on == true
            waitForStop = manual?.on == false
            turnedOnByAutomation = false
            resetHolds()
        }

        val now = sample.elapsedMs
        val previous = lastSampleAt
        lastSampleAt = now
        if (previous == null || now - previous !in 0..MAX_SAMPLE_GAP_MS || settings != options) {
            resetHolds()
            settings = options
        }
        if (!options.enabled) {
            turnedOnByAutomation = false
            resetHolds()
            return
        }
        if (manuallyOn || !sample.speedKmh.isFinite()) {
            resetHolds()
            return
        }

        val speed = abs(sample.speedKmh)
        val stopped = speed <= minOf(1.0, options.onKmh - 0.5)
        if (waitForStop) {
            if (motionHold.isHeld(stopped, now, STOP_CONFIRM_MS)) {
                waitForStop = false
                resetHolds()
            }
            return
        }

        val turnOn = !turnedOnByAutomation
        val due = if (turnOn) {
            motionHold.isHeld(options.canTurnOnAt(sample.minuteOfDay) && speed >= options.onKmh, now, START_CONFIRM_MS)
        } else if (!options.autoOff) {
            false
        } else {
            isOffDue(options, sample, speed, stopped)
        }
        if (!due || lastAttemptAt?.let { now - it < RETRY_MS } == true) return
        lastAttemptAt = now
        if (send(turnOn)) {
            turnedOnByAutomation = turnOn
            resetHolds()
        }
    }

    private fun isOffDue(options: AutoLightSettings, sample: AutoLightSample, speed: Double, stopped: Boolean): Boolean {
        val mode = options.offMode
        val now = sample.elapsedMs
        if (mode == AutoLightOffMode.SPEED) return motionHold.isHeld(speed <= options.offKmh, now, STOP_CONFIRM_MS)

        // Keep the stop timer independent of angle. Briefly straightening the pedals
        // resets the angle check, while moving resets both checks.
        val delayDue = mode.usesDelay && motionHold.isHeld(stopped, now, options.offSeconds * 1000L)
        val angle = sample.pitchDegrees
        val tilted = angle != null && angle.isFinite() && abs(angle) >= options.offPitchDegrees
        val pitchDue = mode.usesPitch && pitchHold.isHeld(stopped && tilted, now, STOP_CONFIRM_MS)
        return if (mode == AutoLightOffMode.DELAY_AND_PITCH) delayDue && pitchDue else delayDue || pitchDue
    }

    private fun resetHolds() {
        motionHold.reset()
        pitchHold.reset()
    }

    private class Hold {
        private var since: Long? = null

        fun reset() { since = null }

        fun isHeld(condition: Boolean, now: Long, durationMs: Long): Boolean {
            if (!condition) {
                reset()
                return false
            }
            val started = since ?: now.also { since = it }
            return now - started >= durationMs
        }
    }

    private companion object {
        const val START_CONFIRM_MS = 500L
        const val STOP_CONFIRM_MS = 2000L
        const val MAX_SAMPLE_GAP_MS = 2000L
        const val RETRY_MS = 1000L
    }
}
