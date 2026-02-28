package com.example.ble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ble.ble.model.BleUiState
import com.example.ble.ble.model.CommLogItem
import com.example.ble.ble.model.LogChannel
import com.example.ble.ble.model.LogDirection
import com.example.ble.ble.model.LogLevel
import com.example.ble.ble.model.ProtocolMirror
import com.example.ble.ble.model.RunState
import com.example.ble.ble.model.SensorComboOption
import com.example.ble.ble.model.SensorModeOption
import com.example.ble.ble.model.SettingField
import com.example.ble.ble.model.SessionPhase
import com.example.ble.presentation.BleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleApp(viewModel: BleViewModel, requestPermissions: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showRequirementCard = !uiState.bluetoothAvailable ||
        !uiState.bluetoothEnabled ||
        !uiState.permissionsGranted ||
        !uiState.locationServiceEnabled

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sofar BLE 控制台")
                        Text("前台会话 | 原生 BluetoothGatt", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            Color(0xFFE3EEF7),
                            Color(0xFFF8FCFF),
                        ),
                    ),
                )
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showRequirementCard) {
                RequirementCard(uiState, requestPermissions)
            }
            ScanCard(
                uiState = uiState,
                onStartScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onSelectDevice = viewModel::selectDevice,
                onConnectSelected = viewModel::connectSelected,
                onDisconnect = viewModel::disconnect,
            )
            SessionCard(uiState)
            SettingsCard(
                uiState = uiState,
                onTextChange = viewModel::updateTextField,
                onRunModeChange = viewModel::setRunMode,
                onAntifreezeChange = viewModel::setAntifreeze,
                onPowerChange = viewModel::setPowerEnabled,
                onApplyAll = viewModel::applyAllSettings,
            )
            LogSection(viewModel)
        }
    }
}

@Composable
private fun LogSection(viewModel: BleViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF11263F)), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("通信日志", style = MaterialTheme.typography.titleLarge, color = Color(0xFFF4FBFF))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (expanded) {
                        TextButton(onClick = viewModel::clearLogs) { Text("清空") }
                    }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
                }
            }
            if (expanded) {
                ExpandedLogContent(viewModel)
            } else {
                Text("已折叠，展开后开始刷新。", color = Color(0xFF9CB5CC))
            }
        }
    }
}

@Composable
private fun ExpandedLogContent(viewModel: BleViewModel) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    HorizontalDivider(color = Color(0x334D79A6))
    if (logs.isEmpty()) {
        Text("暂无日志", color = Color(0xFF9CB5CC))
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            logs.takeLast(24).reversed().forEach { item -> LogRow(item) }
        }
    }
}

@Composable
private fun RequirementCard(uiState: BleUiState, requestPermissions: () -> Unit) {
    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("权限与链路条件", style = MaterialTheme.typography.titleLarge)
            Text(if (uiState.permissionsGranted) "蓝牙权限已就绪。" else "需要先授予蓝牙扫描与连接权限。")
            Text("蓝牙适配器: " + when {
                !uiState.bluetoothAvailable -> "不可用"
                uiState.bluetoothEnabled -> "已开启"
                else -> "未开启"
            })
            Text("系统定位: ${if (uiState.locationServiceEnabled) "已开启" else "未开启"}")
            if (!uiState.permissionsGranted) Button(onClick = requestPermissions) { Text("授予权限") }
            if (!uiState.locationServiceEnabled) {
                StatusBanner("部分设备在系统定位关闭时无法返回 BLE 扫描结果。", false)
            }
        }
    }
}

@Composable
private fun ScanCard(
    uiState: BleUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onConnectSelected: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val canDisconnect = uiState.connectedDeviceAddress != null &&
        uiState.phase in setOf(SessionPhase.SessionReady, SessionPhase.OptionalWrite, SessionPhase.SessionClosing)

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("扫描与连接", style = MaterialTheme.typography.titleLarge)
                }
                Text(formatPhase(uiState.phase), color = MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStartScan, enabled = uiState.permissionsGranted && !uiState.isScanning) { Text("开始扫描") }
                TextButton(onClick = onStopScan, enabled = uiState.isScanning) { Text("停止扫描") }
                if (canDisconnect) {
                    Button(onClick = onDisconnect) { Text("断开连接") }
                } else {
                    Button(
                        onClick = onConnectSelected,
                        enabled = uiState.permissionsGranted &&
                            uiState.selectedDeviceAddress != null &&
                            uiState.phase !in setOf(SessionPhase.Connecting, SessionPhase.Initializing),
                    ) { Text("连接所选设备") }
                }
            }
            Text("当前选择: ${uiState.selectedDeviceAddress ?: "--"}", style = MaterialTheme.typography.labelLarge)
            uiState.lastError?.let { StatusBanner(it, true) }
            if (uiState.scannedDevices.isEmpty()) {
                Text(if (uiState.isScanning) "正在按关键字扫描设备..." else "暂未发现名称命中的设备。")
            } else {
                uiState.scannedDevices.forEach { device ->
                    val isSelected = uiState.selectedDeviceAddress == device.address
                    val isConnected = uiState.connectedDeviceAddress == device.address
                    ElevatedCard(
                        modifier = Modifier.clickable(enabled = uiState.permissionsGranted) { onSelectDevice(device.address) },
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = when {
                                isConnected -> Color(0xFFDFF4EE)
                                isSelected -> Color(0xFFE8F0FE)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${device.name} | RSSI ${device.rssi} dBm")
                                Text(device.address, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    when {
                                        isConnected -> "已连接"
                                        isSelected -> "已选中"
                                        uiState.selectedDeviceAddress == null -> "点击选择"
                                        else -> "可连接"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text(
                                if (isSelected) "再次点击取消" else "点击卡片选择",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(uiState: BleUiState) {
    val showValues = uiState.connectedDeviceAddress != null &&
        uiState.lastUpdatedAt != null &&
        uiState.phase in setOf(SessionPhase.SessionReady, SessionPhase.OptionalWrite)

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("实时状态", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("运行状态", if (showValues) formatRunState(uiState.status.runState) else "未连接", Modifier.weight(1f))
                MetricTile("电压", if (showValues) "${uiState.status.fc04VoltageV} V" else "--", Modifier.weight(1f))
                MetricTile("功率", if (showValues) "${uiState.status.fc04PowerW} W" else "--", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("转速", if (showValues) "${uiState.status.fc04Rpm} rpm" else "--", Modifier.weight(1f))
                MetricTile("压力", if (showValues) String.format(Locale.getDefault(), "%.2f bar", uiState.status.fc04PressureBar) else "--", Modifier.weight(1f))
                MetricTile("负载", if (showValues) "${uiState.status.displayLoadPercent} %" else "--", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("水温", if (showValues) "${uiState.status.fc04WaterTempC}℃" else "--", Modifier.weight(1f))
                MetricTile("镜像温度", if (showValues) "${uiState.status.fc04WaterTempMirrorC}℃" else "--", Modifier.weight(1f))
                MetricTile("display_w7", if (showValues) uiState.status.displayW7.toString() else "--", Modifier.weight(1f))
            }
            Text("最后更新: ${uiState.lastUpdatedAt?.let(::formatTime) ?: "--"}", style = MaterialTheme.typography.labelLarge)
            Text(if (showValues) formatMirrorSummary(uiState.mirror) else "当前设置镜像: 未连接", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SettingsCard(
    uiState: BleUiState,
    onTextChange: (SettingField, String) -> Unit,
    onRunModeChange: (Int) -> Unit,
    onAntifreezeChange: (Boolean) -> Unit,
    onPowerChange: (Boolean) -> Unit,
    onApplyAll: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val isSensorManual = SensorModeOption.fromCodeOrNull(uiState.settings.sensorMode.toIntOrNull()) == SensorModeOption.MANUAL
    val immediateWriteEnabled = uiState.phase == SessionPhase.SessionReady

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("设置写入", style = MaterialTheme.typography.titleLarge)
            if (!uiState.settingsLoaded) {
                Text(if (uiState.connectedDeviceAddress != null) "设备已连接，正在读取当前设置值。" else "未连接设备，设置值暂不显示。")
                return@Column
            }

            Text("仅切换显示内容，不会清空未显示项的值；只有你实际修改的字段才会变化。")

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("设备开关", style = MaterialTheme.typography.titleMedium)
                    Text(if (uiState.settings.powerEnabled) "实时写入: 0x00AA" else "实时写入: 0x0055", style = MaterialTheme.typography.labelLarge)
                }
                Switch(checked = uiState.settings.powerEnabled, onCheckedChange = onPowerChange, enabled = immediateWriteEnabled)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("运行模式", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("自动", 0, uiState.settings.runMode, immediateWriteEnabled, onRunModeChange)
                    ModeChip("手动", 1, uiState.settings.runMode, immediateWriteEnabled, onRunModeChange)
                    ModeChip("温控", 3, uiState.settings.runMode, immediateWriteEnabled, onRunModeChange)
                }
            }

            when (uiState.settings.runMode) {
                0 -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledField("设置压力 (bar)", uiState.settings.setPressureBar, { onTextChange(SettingField.SetPressureBar, it) }, Modifier.weight(1f))
                        LabeledField("缺水压力 (bar)", uiState.settings.lowWaterPressureBar, { onTextChange(SettingField.LowWaterPressureBar, it) }, Modifier.weight(1f))
                    }
                    LabeledField("启动压力 (bar)", uiState.settings.startPressureBar, { onTextChange(SettingField.StartPressureBar, it) }, Modifier.fillMaxWidth())
                }
                1 -> ManualGearSelector(
                    selectedValue = uiState.settings.manualGear,
                    onSelect = { onTextChange(SettingField.ManualGear, it) },
                )
                3 -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledField("设置温度 (℃)", uiState.settings.targetTemp, { onTextChange(SettingField.TargetTemp, it) }, Modifier.weight(1f))
                        LabeledField("启动温度 (℃)", uiState.settings.startTemp, { onTextChange(SettingField.StartTemp, it) }, Modifier.weight(1f))
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("其他设置", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
                    }
                    if (expanded) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("防冻设置", style = MaterialTheme.typography.titleMedium)
                            Switch(checked = uiState.settings.antifreezeEnabled, onCheckedChange = onAntifreezeChange)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabeledField("水温保护值 (℃)", uiState.settings.waterProtectTemp, { onTextChange(SettingField.WaterProtectTemp, it) }, Modifier.weight(1f))
                            LabeledField("水温复位值 (℃)", uiState.settings.waterResetTemp, { onTextChange(SettingField.WaterResetTemp, it) }, Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabeledField("电机温度保护值 (℃)", uiState.settings.motorProtectTemp, { onTextChange(SettingField.MotorProtectTemp, it) }, Modifier.weight(1f))
                            LabeledField("电机温度复位值 (℃)", uiState.settings.motorResetTemp, { onTextChange(SettingField.MotorResetTemp, it) }, Modifier.weight(1f))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("传感器模式", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("自动", style = MaterialTheme.typography.labelLarge, color = if (isSensorManual) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                                Switch(
                                    checked = isSensorManual,
                                    onCheckedChange = {
                                        onTextChange(
                                            SettingField.SensorMode,
                                            if (it) {
                                                SensorModeOption.MANUAL.code.toString()
                                            } else {
                                                SensorModeOption.AUTO.code.toString()
                                            },
                                        )
                                    },
                                )
                                Text("手动", style = MaterialTheme.typography.labelLarge, color = if (isSensorManual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("传感器组合", style = MaterialTheme.typography.titleMedium)
                            Text("当前: ${sensorComboMeaning(uiState.settings.sensorCombo.toIntOrNull())}", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SensorComboChip("无传感器", SensorComboOption.NONE.code.toString(), uiState.settings.sensorCombo, isSensorManual) { onTextChange(SettingField.SensorCombo, it) }
                                SensorComboChip("水流开关+压力", SensorComboOption.FLOW_SWITCH_AND_PRESSURE.code.toString(), uiState.settings.sensorCombo, isSensorManual) { onTextChange(SettingField.SensorCombo, it) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SensorComboChip("单水流开关", SensorComboOption.SINGLE_FLOW_SWITCH.code.toString(), uiState.settings.sensorCombo, isSensorManual) { onTextChange(SettingField.SensorCombo, it) }
                                SensorComboChip("单压力", SensorComboOption.SINGLE_PRESSURE.code.toString(), uiState.settings.sensorCombo, isSensorManual) { onTextChange(SettingField.SensorCombo, it) }
                                SensorComboChip("双压力", SensorComboOption.DUAL_PRESSURE.code.toString(), uiState.settings.sensorCombo, isSensorManual) { onTextChange(SettingField.SensorCombo, it) }
                            }
                            if (!isSensorManual) {
                                Text("传感器模式为自动时，仅显示当前组合，不可修改。", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            Button(onClick = onApplyAll, modifier = Modifier.fillMaxWidth(), enabled = uiState.phase == SessionPhase.SessionReady) {
                Text("写入全部设置")
            }
        }
    }
}

@Composable
private fun SensorComboChip(
    label: String,
    value: String,
    selectedValue: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selectedValue == value,
        onClick = { onSelect(value) },
        enabled = enabled,
        label = { Text(label) },
    )
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
    )
}

@Composable
private fun ModeChip(title: String, mode: Int, selectedMode: Int, enabled: Boolean, onModeChange: (Int) -> Unit) {
    FilterChip(
        selected = selectedMode == mode,
        onClick = { onModeChange(mode) },
        enabled = enabled,
        label = { Text(title) },
    )
}

@Composable
private fun ManualGearSelector(selectedValue: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("手动档位", style = MaterialTheme.typography.titleMedium)
        Text(
            "当前: ${selectedValue.takeIf { it.isNotBlank() }?.let { "${it}档" } ?: "--"}",
            style = MaterialTheme.typography.labelLarge,
        )
        (1..5).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { gear ->
                    FilterChip(
                        selected = selectedValue == gear.toString(),
                        onClick = { onSelect(gear.toString()) },
                        label = { Text("${gear}档") },
                    )
                }
            }
        }
    }
}

@Composable
private fun LogRow(item: CommLogItem) {
    val accent = when (item.level) {
        LogLevel.INFO -> Color(0xFF8CD1C8)
        LogLevel.WARN -> Color(0xFFF6C46B)
        LogLevel.ERROR -> Color(0xFFFF907F)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(item.timestamp), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = accent)
            Spacer(Modifier.width(8.dp))
            Text("${formatLogChannel(item.channel)}/${formatLogDirection(item.direction)}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFC8D9EA))
        }
        Text(item.message, color = Color(0xFFF4FBFF))
        item.payloadHex?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = Color(0xFF8AA6C1))
        }
    }
}

@Composable
private fun StatusBanner(text: String, isError: Boolean) {
    val background = if (isError) Color(0xFFFFE7E2) else Color(0xFFE7F7F1)
    val content = if (isError) Color(0xFF902B1C) else Color(0xFF0C6843)
    Box(Modifier.fillMaxWidth().background(background, RoundedCornerShape(18.dp)).padding(12.dp)) {
        Text(text, color = content)
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun formatRunState(state: RunState): String =
    when (state) {
        RunState.OFF -> "停止"
        RunState.RUNNING -> "运行中"
        RunState.TRANSITION_OR_INVALID -> "过渡/异常"
    }

private fun formatSwitchValue(value: Int?): String =
    when (value) {
        null -> "--"
        0x00AA -> "开启"
        0x0055 -> "关闭"
        else -> "0x%04X".format(value)
    }

private fun formatManualGearValue(value: Int?): String =
    when {
        value == null -> "--"
        else -> "${value + 1} 档"
    }

private fun formatPackedTempValue(value: Int?, highLabel: String, lowLabel: String): String {
    if (value == null) return "--"
    val high = (value ushr 8) and 0xFF
    val low = value and 0xFF
    return "$highLabel ${high}℃ / $lowLabel ${low}℃"
}

private fun formatMirrorSummary(mirror: ProtocolMirror): String {
    val sections = buildList {
        add("设备开关 ${formatSwitchValue(mirror.switchRegister)}")
        add("手动档位 ${formatManualGearValue(mirror.manualGearRegister)}")
        if (mirror.configLoaded && mirror.config0190.size >= 9) {
            add("运行模式 ${formatRunModeValue(mirror.config0190[3])}")
            add("防冻 ${if (mirror.config0190[4] != 0) "开启" else "关闭"}")
            add("传感器模式 ${sensorModeMeaning(mirror.config0190[7])}")
            add("传感器组合 ${sensorComboMeaning(mirror.config0190[8])}")
        }
        add("电机温度 ${formatPackedTempValue(mirror.motorTempRegister, "保护", "复位")}")
        add("温控 ${formatPackedTempValue(mirror.targetTempRegister, "设定", "启动")}")
    }
    return "当前设置镜像: ${sections.joinToString(" | ")}"
}

private fun formatRunModeValue(value: Int?): String =
    when (value) {
        null -> "--"
        0 -> "自动"
        1 -> "手动"
        3 -> "温控"
        else -> value.toString()
    }

private fun sensorModeMeaning(value: Int?): String =
    when (SensorModeOption.fromCodeOrNull(value)) {
        SensorModeOption.AUTO -> "自动"
        SensorModeOption.MANUAL -> "手动"
        null -> if (value == null) "--" else "未知($value)"
    }

private fun sensorComboMeaning(value: Int?): String =
    when (SensorComboOption.fromCodeOrNull(value)) {
        SensorComboOption.NONE -> "无传感器"
        SensorComboOption.FLOW_SWITCH_AND_PRESSURE -> "水流开关+压力"
        SensorComboOption.SINGLE_FLOW_SWITCH -> "单水流开关"
        SensorComboOption.SINGLE_PRESSURE -> "单压力"
        SensorComboOption.DUAL_PRESSURE -> "双压力"
        null -> if (value == null) "--" else "未知($value)"
    }

private fun formatPhase(phase: SessionPhase): String =
    when (phase) {
        SessionPhase.Idle -> "空闲"
        SessionPhase.Scanning -> "扫描中"
        SessionPhase.Connecting -> "连接中"
        SessionPhase.Initializing -> "初始化"
        SessionPhase.SessionReady -> "已连接"
        SessionPhase.OptionalWrite -> "写入中"
        SessionPhase.SessionClosing -> "断开中"
        SessionPhase.Disconnected -> "已断开"
    }

private fun formatLogChannel(channel: LogChannel): String =
    when (channel) {
        LogChannel.SCAN -> "扫描"
        LogChannel.GATT -> "GATT"
        LogChannel.MODBUS -> "Modbus"
        LogChannel.SESSION -> "会话"
        LogChannel.UI -> "界面"
    }

private fun formatLogDirection(direction: LogDirection): String =
    when (direction) {
        LogDirection.TX -> "发送"
        LogDirection.RX -> "接收"
        LogDirection.EVT -> "事件"
    }
