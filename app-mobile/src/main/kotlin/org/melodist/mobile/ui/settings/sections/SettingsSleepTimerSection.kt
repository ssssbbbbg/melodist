package org.melodist.mobile.ui.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.melodist.data.AppSettings
import org.melodist.data.AppSettingsManager
import org.melodist.data.SleepTimerAction
import org.melodist.data.SleepTimerDuration
import org.melodist.data.SleepTimerManager
import org.melodist.mobile.ui.components.SettingsClickableRow
import org.melodist.mobile.ui.components.SettingsDivider
import org.melodist.mobile.ui.components.SettingsGroupCard
import org.melodist.mobile.ui.components.SettingsGroupTitle
import org.melodist.mobile.ui.components.SettingsSwitchRow

/**
 * 定时关闭设置分区：选择倒计时时长、触发行为，并展示实时剩余时间。
 */
@Composable
fun SettingsSleepTimerSection(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val timerState by SleepTimerManager.state.collectAsState()
    var showDurationDialog by remember { mutableStateOf(false) }
    var showActionDialog by remember { mutableStateOf(false) }

    val statusSubtitle =
        if (timerState.enabled) {
            "剩余 ${SleepTimerManager.formatRemaining(timerState.remainingMillis)}，到点后${timerState.action.label}"
        } else {
            "已设为 ${settings.sleepTimerDuration.label}，点击开始倒计时"
        }

    Column(modifier = modifier) {
        SettingsGroupTitle(title = "定时关闭")
        SettingsGroupCard {
            SettingsClickableRow(
                icon = if (timerState.enabled) Icons.Rounded.Timer else Icons.Rounded.Bedtime,
                title = if (timerState.enabled) "重新计时" else "开始倒计时",
                subtitle = statusSubtitle,
                onClick = {
                    if (timerState.enabled) showDurationDialog = true else SleepTimerManager.start(settings.sleepTimerDuration)
                },
            )

            if (timerState.enabled) {
                SettingsDivider()
                SettingsClickableRow(
                    icon = Icons.Rounded.Cancel,
                    title = "取消定时关闭",
                    subtitle = "停止倒计时，音乐会继续播放",
                    onClick = { SleepTimerManager.cancel() },
                )
            }

            SettingsDivider()

            SettingsClickableRow(
                icon = Icons.Rounded.Timer,
                title = "倒计时时长",
                subtitle = settings.sleepTimerDuration.label,
                onClick = { showDurationDialog = true },
            )

            SettingsDivider()

            SettingsClickableRow(
                icon = Icons.Rounded.PowerSettingsNew,
                title = "到点后",
                subtitle = settings.sleepTimerAction.label,
                onClick = { showActionDialog = true },
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Rounded.PauseCircle,
                title = "暂停即取消",
                subtitle = "手动暂停播放时结束倒计时，避免之后继续播放被误关",
                checked = settings.sleepTimerStopOnPause,
                onCheckedChange = { AppSettingsManager.setSleepTimerStopOnPause(it) },
            )
        }
    }

    if (showDurationDialog) {
        SleepTimerOptionDialog(
            title = "倒计时时长",
            options = SleepTimerDuration.entries.filter { it != SleepTimerDuration.Off },
            selected = settings.sleepTimerDuration,
            labelOf = { it.label },
            onDismiss = { showDurationDialog = false },
            onSelect = { duration ->
                AppSettingsManager.setSleepTimerDuration(duration)
                // 选中即刻开始倒计时，等同于“设置并启动”
                SleepTimerManager.start(duration)
                showDurationDialog = false
            },
        )
    }

    if (showActionDialog) {
        SleepTimerOptionDialog(
            title = "到点后",
            options = SleepTimerAction.entries,
            selected = settings.sleepTimerAction,
            labelOf = { it.label },
            onDismiss = { showActionDialog = false },
            onSelect = { action ->
                AppSettingsManager.setSleepTimerAction(action)
                showActionDialog = false
            },
        )
    }
}

@Composable
private fun <T> SleepTimerOptionDialog(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEach { option ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = labelOf(option),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (options.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
