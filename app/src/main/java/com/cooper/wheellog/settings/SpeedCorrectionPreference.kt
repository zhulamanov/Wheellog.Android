package com.cooper.wheellog.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cooper.wheellog.AppConfig
import com.cooper.wheellog.R
import kotlin.math.roundToInt

@Composable
fun speedCorrectionPreference(config: AppConfig) {
    var enabled by remember { mutableStateOf(config.useSpeedCorrection) }
    var percent by remember { mutableIntStateOf(config.speedCorrectionPercent) }
    switchPref(name = stringResource(R.string.speed_correction_title),
        desc = stringResource(R.string.speed_correction_description), default = enabled) {
        enabled = it; config.useSpeedCorrection = it
    }
    AnimatedVisibility(enabled) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = percent > -20, onClick = { percent--; config.speedCorrectionPercent = percent }) {
                    Icon(Icons.Default.Remove, stringResource(R.string.speed_correction_decrease))
                }
                Slider(value = percent.toFloat(), onValueChange = {
                    percent = it.roundToInt(); config.speedCorrectionPercent = percent
                }, steps = 39, valueRange = -20f..20f, modifier = Modifier.weight(1f))
                IconButton(enabled = percent < 20, onClick = { percent++; config.speedCorrectionPercent = percent }) {
                    Icon(Icons.Default.Add, stringResource(R.string.speed_correction_increase))
                }
                Text(if (percent > 0) "+$percent%" else "$percent%")
            }
        }
    }
}
