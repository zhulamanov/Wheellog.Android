package com.cooper.wheellog.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cooper.wheellog.AppConfig
import com.cooper.wheellog.R
import com.cooper.wheellog.WheelData
import com.cooper.wheellog.autolight.AutoLightOffMode
import com.cooper.wheellog.autolight.AutoLightPreferences
import com.cooper.wheellog.autolight.AutoLightSettings
import com.cooper.wheellog.utils.Constants
import com.welie.blessed.ConnectionState
import org.koin.compose.koinInject
import java.util.Locale
import kotlin.math.roundToInt

private data class AutoLightProfile(
    val address: String,
    val awaitingWheel: Boolean,
    val unsupported: Boolean,
    val supportsPitch: Boolean
)

@Composable
fun autoLightScreen(appConfig: AppConfig = koinInject()) {
    val context = LocalContext.current
    val preferences = remember(context) { AutoLightPreferences(context) }
    val currentProfile = {
        val wheel = WheelData.getInstance()
        val connected = wheel?.bluetoothService?.connectionState == ConnectionState.CONNECTED
        val ready = connected && wheel?.isWheelIsReady == true
        val adapter = if (ready) wheel.adapter else null
        AutoLightProfile(
            address = (if (connected) wheel?.mac.orEmpty() else appConfig.lastMac)
                .takeUnless { it.equals("default", ignoreCase = true) }.orEmpty(),
            awaitingWheel = connected && !ready,
            unsupported = ready && adapter?.supportsAutomaticHeadlight != true,
            supportsPitch = adapter?.supportsHeadlightPitch ?: true
        )
    }
    var profile by remember { mutableStateOf(currentProfile()) }
    systemBroadcastReceiver(Constants.ACTION_BLUETOOTH_CONNECTION_STATE) { profile = currentProfile() }
    systemBroadcastReceiver(Constants.ACTION_WHEEL_TYPE_RECOGNIZED) { profile = currentProfile() }
    systemBroadcastReceiver(Constants.ACTION_WHEEL_IS_READY) { profile = currentProfile() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        val address = profile.address
        if (profile.unsupported || profile.awaitingWheel || address.isBlank()) {
            val message = when {
                profile.unsupported -> R.string.auto_light_unsupported
                profile.awaitingWheel -> R.string.auto_light_waiting
                else -> R.string.auto_light_connect
            }
            Text(stringResource(message), Modifier.padding(20.dp))
        } else {
            key(address) {
                var options by remember { mutableStateOf(preferences.read(address)) }
                val update: (AutoLightSettings) -> Unit = { edited ->
                    options = edited.normalized()
                    preferences.save(address, options)
                }
                switchPref(
                    name = stringResource(R.string.auto_light_title),
                    desc = stringResource(R.string.auto_light_description),
                    default = options.enabled
                ) { update(options.copy(enabled = it)) }

                if (options.enabled) {
                    sliderPref(
                        name = stringResource(R.string.auto_light_on_speed),
                        position = options.onKmh.toFloat(), min = 1f, max = 30f, unit = R.string.kmh
                    ) { update(options.copy(onKmh = it.roundToInt())) }

                    switchPref(
                        name = stringResource(R.string.auto_light_auto_off),
                        desc = stringResource(R.string.auto_light_off_description),
                        default = options.autoOff
                    ) { update(options.copy(autoOff = it)) }

                    if (options.autoOff) {
                        val effectiveOptions = options.forWheel(profile.supportsPitch)
                        val effectiveMode = effectiveOptions.offMode
                        val savedMode = options.offMode
                        val combined = savedMode.usesDelay && savedMode.usesPitch
                        val speedKey = AutoLightOffMode.SPEED.name
                        val combinedKey = AutoLightOffMode.DELAY_AND_PITCH.name
                        val selectedKey = when {
                            combined -> combinedKey
                            savedMode == AutoLightOffMode.SPEED -> speedKey
                            else -> ""
                        }
                        key(selectedKey) {
                            list(
                                name = stringResource(R.string.auto_light_off_mode),
                                desc = when {
                                    !effectiveOptions.autoOff -> stringResource(R.string.auto_light_both_need_pitch)
                                    combined && !profile.supportsPitch ->
                                        stringResource(R.string.auto_light_pitch_unavailable)
                                    else -> ""
                                },
                                entries = mapOf(
                                    speedKey to stringResource(R.string.auto_light_by_speed),
                                    combinedKey to stringResource(R.string.auto_light_by_delay_and_pitch)
                                ),
                                defaultKey = selectedKey
                            ) { (mode, _) ->
                                if (mode != selectedKey) {
                                    val offMode = when {
                                        mode == speedKey -> AutoLightOffMode.SPEED
                                        profile.supportsPitch -> AutoLightOffMode.DELAY_AND_PITCH
                                        else -> AutoLightOffMode.DELAY_OR_PITCH
                                    }
                                    update(options.copy(offMode = offMode))
                                }
                            }
                        }
                        if (combined) {
                            autoLightCondition(either = savedMode == AutoLightOffMode.DELAY_OR_PITCH) { either ->
                                val mode = if (either) AutoLightOffMode.DELAY_OR_PITCH else AutoLightOffMode.DELAY_AND_PITCH
                                update(options.copy(offMode = mode))
                            }
                        }
                        if (effectiveOptions.autoOff && effectiveMode == AutoLightOffMode.SPEED) {
                            val name = stringResource(R.string.auto_light_off_speed)
                            if (options.maxOffKmh == 0) {
                                baseSettings(name = name, desc = stringResource(R.string.auto_light_zero_speed))
                            } else {
                                sliderPref(
                                    name = name, position = options.offKmh.toFloat(),
                                    min = 0f, max = options.maxOffKmh.toFloat(), unit = R.string.kmh
                                ) { update(options.copy(offKmh = it.roundToInt())) }
                            }
                        }
                        if (effectiveOptions.autoOff && effectiveMode.usesDelay) {
                            sliderPref(
                                name = stringResource(R.string.auto_light_off_delay),
                                position = options.offSeconds.toFloat(), min = 5f, max = 120f,
                                unit = R.string.auto_light_seconds
                            ) { update(options.copy(offSeconds = it.roundToInt())) }
                        }
                        if (effectiveOptions.autoOff && effectiveMode.usesPitch) {
                            sliderPref(
                                name = stringResource(R.string.auto_light_off_pitch),
                                position = options.offPitchDegrees.toFloat(), min = 5f, max = 45f,
                                unit = R.string.auto_light_degrees
                            ) { update(options.copy(offPitchDegrees = it.roundToInt())) }
                        }
                    }

                    switchPref(
                        name = stringResource(R.string.auto_light_schedule),
                        desc = stringResource(R.string.auto_light_schedule_description),
                        default = options.scheduled
                    ) { update(options.copy(scheduled = it)) }

                    if (options.scheduled) {
                        autoLightTimePreference(R.string.auto_light_start, options.startMinute) {
                            update(options.copy(startMinute = it))
                        }
                        autoLightTimePreference(R.string.auto_light_end, options.endMinute) {
                            update(options.copy(endMinute = it))
                        }
                    }
                    Text(
                        stringResource(R.string.auto_light_manual_description),
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun autoLightCondition(either: Boolean, onSelect: (Boolean) -> Unit) {
    baseSettings(
        name = stringResource(R.string.auto_light_condition),
        rightContent = {
            SingleChoiceSegmentedButtonRow {
                val colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary
                )
                SegmentedButton(
                    selected = !either, onClick = { onSelect(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = colors, icon = {}
                ) { Text(stringResource(R.string.auto_light_condition_and)) }
                SegmentedButton(
                    selected = either, onClick = { onSelect(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = colors, icon = {}
                ) { Text(stringResource(R.string.auto_light_condition_or)) }
            }
        }
    )
}

@Composable
private fun autoLightTimePreference(title: Int, minute: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val hourOfDay = minute / 60
    val minuteOfHour = minute % 60
    clickablePref(name = stringResource(title), desc = String.format(Locale.ROOT, "%02d:%02d", hourOfDay, minuteOfHour)) {
        TimePickerDialog(context, { _, hour, minutes -> onChange(hour * 60 + minutes) },
            hourOfDay, minuteOfHour, true).show()
    }
}
