package com.cooper.wheellog.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cooper.wheellog.AppConfig
import com.cooper.wheellog.InfoBlocks
import com.cooper.wheellog.R
import com.cooper.wheellog.utils.ThemeIconEnum

@Composable
fun infoBlocksPreference(config: AppConfig) {
    val context = LocalContext.current
    val blocks = remember(context) { InfoBlocks(context) }
    var open by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(config.viewBlockOrder) }
    var selected by remember { mutableStateOf(config.viewBlockIds.toSet()) }
    clickablePref(name = stringResource(R.string.view_blocks_title),
        desc = stringResource(R.string.info_blocks_order_description),
        themeIcon = ThemeIconEnum.SettingsBlocks) {
        order = config.viewBlockOrder
        selected = config.viewBlockIds.toSet()
        open = true
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.view_blocks_title)) },
        text = {
            Column {
                Text(stringResource(R.string.info_blocks_order_description), style = MaterialTheme.typography.bodySmall)
                InfoBlockPresetsControl(config.infoBlockPresets, order, selected) { preset ->
                    order = preset.order
                    selected = preset.enabled
                }
                LazyColumn {
                    itemsIndexed(order, key = { _, key -> key }) { index, key ->
                        val name = stringResource(blocks.titles.getValue(key))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + key else selected - key
                            })
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            fun move(offset: Int) {
                                order = order.toMutableList().apply { java.util.Collections.swap(this, index, index + offset) }
                            }
                            IconButton(onClick = { move(-1) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.dashboard_move_up, name))
                            }
                            IconButton(onClick = { move(1) }, enabled = index < order.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.dashboard_move_down, name))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            config.viewBlockIds = order.filter { it in selected }
            config.viewBlockOrder = order
            open = false
        }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } })
}
