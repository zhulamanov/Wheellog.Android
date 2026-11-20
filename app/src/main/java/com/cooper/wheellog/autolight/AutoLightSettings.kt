package com.cooper.wheellog.autolight

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

internal enum class AutoLightOffMode(val usesDelay: Boolean = false, val usesPitch: Boolean = false) {
    SPEED,
    DELAY(usesDelay = true),
    PITCH(usesPitch = true),
    DELAY_AND_PITCH(usesDelay = true, usesPitch = true),
    DELAY_OR_PITCH(usesDelay = true, usesPitch = true)
}

internal data class AutoLightSettings(
    val enabled: Boolean = false,
    val onKmh: Int = 5,
    val autoOff: Boolean = false,
    val offMode: AutoLightOffMode = AutoLightOffMode.SPEED,
    val offKmh: Int = 1,
    val offSeconds: Int = 30,
    val offPitchDegrees: Int = 20,
    val scheduled: Boolean = false,
    val startMinute: Int = 18 * 60,
    val endMinute: Int = 8 * 60
) {
    val maxOffKmh: Int get() = minOf(3, onKmh - 1)

    fun forWheel(supportsPitch: Boolean): AutoLightSettings = when {
        supportsPitch -> this
        offMode == AutoLightOffMode.PITCH -> copy(offMode = AutoLightOffMode.SPEED)
        offMode == AutoLightOffMode.DELAY_OR_PITCH -> copy(offMode = AutoLightOffMode.DELAY)
        offMode == AutoLightOffMode.DELAY_AND_PITCH -> copy(autoOff = false)
        else -> this
    }

    fun normalized(): AutoLightSettings {
        val on = onKmh.coerceIn(1, 30)
        return copy(
            onKmh = on,
            offKmh = offKmh.coerceIn(0, minOf(3, on - 1)),
            offSeconds = offSeconds.coerceIn(5, 120),
            offPitchDegrees = offPitchDegrees.coerceIn(5, 45),
            startMinute = startMinute.coerceIn(0, 1439),
            endMinute = endMinute.coerceIn(0, 1439)
        )
    }

    fun canTurnOnAt(minute: Int): Boolean = when {
        !scheduled || startMinute == endMinute -> true
        startMinute < endMinute -> minute in startMinute until endMinute
        else -> minute >= startMinute || minute < endMinute
    }
}

internal class AutoLightPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("auto_light", Context.MODE_PRIVATE)

    fun read(address: String): AutoLightSettings {
        val key = prefix(address)
        val savedMode = preferences.getString(key + "off_mode", null)
        return AutoLightSettings(
            enabled = preferences.getBoolean(key + "enabled", false),
            onKmh = preferences.getInt(key + "on_kmh", 5),
            autoOff = preferences.getBoolean(key + "auto_off", false),
            offMode = AutoLightOffMode.entries.find { it.name == savedMode } ?: AutoLightOffMode.SPEED,
            offKmh = preferences.getInt(key + "off_kmh", 1),
            offSeconds = preferences.getInt(key + "off_seconds", 30),
            offPitchDegrees = preferences.getInt(key + "off_pitch", 20),
            scheduled = preferences.getBoolean(key + "schedule", false),
            startMinute = preferences.getInt(key + "start", 18 * 60),
            endMinute = preferences.getInt(key + "end", 8 * 60)
        ).normalized()
    }

    fun save(address: String, settings: AutoLightSettings) {
        val key = prefix(address)
        val value = settings.normalized()
        preferences.edit {
            putBoolean(key + "enabled", value.enabled)
            putInt(key + "on_kmh", value.onKmh)
            putBoolean(key + "auto_off", value.autoOff)
            putString(key + "off_mode", value.offMode.name)
            putInt(key + "off_kmh", value.offKmh)
            putInt(key + "off_seconds", value.offSeconds)
            putInt(key + "off_pitch", value.offPitchDegrees)
            putBoolean(key + "schedule", value.scheduled)
            putInt(key + "start", value.startMinute)
            putInt(key + "end", value.endMinute)
        }
    }

    private fun prefix(address: String) = "${address.uppercase(Locale.ROOT)}_"
}
