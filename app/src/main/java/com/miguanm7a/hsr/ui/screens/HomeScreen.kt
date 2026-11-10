package com.miguanm7a.hsr.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miguanm7a.hsr.deploy.UpstreamGapChecker
import com.miguanm7a.hsr.ui.AppUpdateOutcome
import com.miguanm7a.hsr.ui.MainViewModel
import com.miguanm7a.hsr.ui.UpdateOutcome
import com.miguanm7a.hsr.ui.components.InfoRow
import com.miguanm7a.hsr.ui.components.LoginQrCard
import com.miguanm7a.hsr.ui.components.MaaCard
import com.miguanm7a.hsr.ui.components.SectionHeader
import com.miguanm7a.hsr.ui.components.SmallSpinner
import com.miguanm7a.hsr.ui.components.pressScale
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.OkColor
import com.miguanm7a.hsr.ui.theme.WarnColor
import com.miguanm7a.hsr.ui.theme.color

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onGoDeploy: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val deploy by vm.deployState.collectAsState()
    val qr by vm.qr.collectAsState()
    val context = LocalContext.current

    val check = ui.updateCheck
    if (check != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissUpdateCheck() },
            title = {
                Text(
                    when (check.outcome) {
                        UpdateOutcome.UP_TO_DATE -> "已是最新版本"
                        UpdateOutcome.AVAILABLE -> "发现新版本 ${check.tag}"
                        UpdateOutcome.ERROR -> "检查更新失败"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    when (check.outcome) {
                        UpdateOutcome.UP_TO_DATE -> Text(
                            "当前已安装 ${ui.installedCodeTag.ifBlank { "未安装" }}，" +
                                "与上游最新 Release（${check.tag}）一致。"
                        )

                        UpdateOutcome.AVAILABLE -> {
                            Text(
                                "当前 ${ui.installedCodeTag.ifBlank { "未安装" }} → " +
                                    "最新 ${check.tag}"
                            )
                            if (!ui.releaseNotes.isNullOrBlank()) {
                                Text(
                                    ui.releaseNotes.orEmpty().take(400),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        UpdateOutcome.ERROR -> Text(
                            check.message ?: "网络请求失败，请检查网络后重试。"
                        )
                    }

                    val gap = ui.gapReport
                    if (gap != null) {
                        if (gap.warning != null && gap.isEmpty) {
                            Text(
                                gap.warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = WarnColor,
                            )
                        } else if (!gap.isEmpty) {
                            Text(
                                "上游新增（相对构建基线 ${gap.baselineTag ?: "未知"}）",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (gap.newConfigKeys.isNotEmpty()) {
                                Text("配置键", style = MaterialTheme.typography.labelSmall)
                                gap.newConfigKeys.take(10).forEach { item ->
                                    Text(
                                        "· ${item.name}  ${if (item.supported) "已支持" else "未支持"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (item.supported) OkColor else WarnColor,
                                    )
                                }
                            }
                            if (gap.newTasks.isNotEmpty()) {
                                Text("任务", style = MaterialTheme.typography.labelSmall)
                                gap.newTasks.take(10).forEach { item ->
                                    Text(
                                        "· ${item.name}  ${if (item.supported) "已支持" else "未支持"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (item.supported) OkColor else WarnColor,
                                    )
                                }
                            }
                            TextButton(onClick = { copyGap(context, gap) }) { Text("复制清单") }
                        } else {
                            Text(
                                "上游没有新增配置键或任务。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when (check.outcome) {
                    UpdateOutcome.AVAILABLE -> Button(
                        onClick = {
                            vm.applyUpdate()
                            vm.dismissUpdateCheck()
                        }
                    ) { Text("立即更新") }

                    UpdateOutcome.UP_TO_DATE -> TextButton(
                        onClick = { vm.dismissUpdateCheck() }
                    ) { Text("知道了") }

                    UpdateOutcome.ERROR -> TextButton(
                        onClick = {
                            vm.dismissUpdateCheck()
                            vm.checkUpdate()
                        }
                    ) { Text("重试") }
                }
            },
            dismissButton = {
                if (check.outcome == UpdateOutcome.AVAILABLE) {
                    TextButton(onClick = { vm.dismissUpdateCheck() }) { Text("稍后") }
                }
            },
        )
    }

    val appCheck = ui.appUpdateCheck
    if (appCheck != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissAppUpdateCheck() },
            title = {
                Text(
                    when (appCheck.outcome) {
                        AppUpdateOutcome.UP_TO_DATE -> "App 已是最新版本"
                        AppUpdateOutcome.AVAILABLE -> "发现新版 App ${appCheck.version}"
                        AppUpdateOutcome.ERROR -> "检查 App 更新失败"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    when (appCheck.outcome) {
                        AppUpdateOutcome.UP_TO_DATE -> Text("当前 ${ui.version}，已是最新。")
                        AppUpdateOutcome.AVAILABLE -> {
                            Text("当前 ${ui.version} → 最新 ${appCheck.version}")
                            if (!appCheck.notes.isNullOrBlank()) {
                                Text(
                                    appCheck.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 10,
                                )
                            }
                            Text(
                                "HyperOS 不允许后台静默安装，请到 Release 页手动下载安装。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        AppUpdateOutcome.ERROR -> Text(
                            appCheck.message ?: "网络请求失败，请检查网络后重试。"
                        )
                    }
                }
            },
            confirmButton = {
                when (appCheck.outcome) {
                    AppUpdateOutcome.AVAILABLE -> Button(
                        onClick = {
                            openUrl(context, appCheck.pageUrl)
                            vm.dismissAppUpdateCheck()
                        },
                    ) { Text("打开下载页") }

                    AppUpdateOutcome.UP_TO_DATE -> TextButton(
                        onClick = { vm.dismissAppUpdateCheck() },
                    ) { Text("知道了") }

                    AppUpdateOutcome.ERROR -> TextButton(
                        onClick = {
                            vm.dismissAppUpdateCheck()
                            vm.checkAppUpdate()
                        },
                    ) { Text("重试") }
                }
            },
            dismissButton = {
                if (appCheck.outcome != AppUpdateOutcome.UP_TO_DATE) {
                    TextButton(onClick = { vm.dismissAppUpdateCheck() }) { Text("关闭") }
                }
            },
        )
    }

    val runningPulse by rememberInfiniteTransition(label = "runningPulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "runningAlpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaaDesignTokens.Spacing.listHorizontal),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        Spacer(Modifier.height(MaaDesignTokens.Spacing.lg))

        Text(
            text = "March7thAssistant",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "内嵌 Termux 运行时 · 一键部署 · 一键运行",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "版本 ${ui.version}（测试版）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))

        // ---------------- 状态卡片 ----------------
        MaaCard(modifier = Modifier.animateContentSize(tween(200))) {
            Text("运行状态", style = MaterialTheme.typography.titleMedium)
            InfoRow(
                "Termux 运行时",
                if (ui.runtimeReady) "已就绪" else "未安装",
                valueColor = if (ui.runtimeReady) OkColor else WarnColor,
            )
            InfoRow(
                "容器",
                if (ui.deployed) ui.containerAlias else "未部署",
                valueColor = if (ui.deployed) OkColor else WarnColor,
            )
            InfoRow("当前任务", ui.task.label)
            if (ui.runningTask) {
                InfoRow(
                    "执行中",
                    ui.runningTaskName ?: "任务",
                    valueColor = MaterialTheme.colorScheme.primary.copy(alpha = runningPulse),
                )
            }
        }

        // ---------------- 版本 / 检查更新 ----------------
        MaaCard {
            Text("版本与更新", style = MaterialTheme.typography.titleMedium)
            VersionRow(
                label = "上游代码",
                value = ui.installedCodeTag.ifBlank { "未安装（请先部署）" },
                valueColor = if (ui.installedCodeTag.isBlank()) WarnColor else OkColor,
                dot = ui.codeUpdateDot,
                checking = ui.checkingUpdate,
                checkEnabled = !ui.checkingUpdate && !ui.updating,
                onCheck = { vm.checkUpdate() },
            )
            VersionRow(
                label = "App 版本",
                value = ui.version,
                dot = ui.appUpdateDot,
                checking = ui.checkingAppUpdate,
                checkEnabled = !ui.checkingAppUpdate,
                onCheck = { vm.checkAppUpdate() },
            )
            ui.latestCodeTag?.let {
                InfoRow("上游最新", it, valueColor = MaterialTheme.colorScheme.primary)
            }
            ui.appLatestVersion?.let {
                InfoRow("App 最新", it, valueColor = MaterialTheme.colorScheme.primary)
            }
            Text(
                "上游检查会同时对比配置键与任务，列出本 APK 构建后新增、尚不支持的功能；App 更新需到 Release 页手动安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.updateError != null) {
                Text(
                    "检查失败：${ui.updateError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (ui.latestCodeTag != null) {
                Button(
                    onClick = { vm.applyUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !ui.updating && !ui.runningTask,
                ) {
                    Text(if (ui.updating) "更新中…" else "立即更新到 ${ui.latestCodeTag}")
                }
            }
            if (ui.updating) {
                FilledTonalButton(
                    onClick = { vm.retryDepsSync() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重试依赖同步")
                }
            }
            AnimatedVisibility(
                visible = !ui.releaseNotes.isNullOrBlank(),
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "更新说明（${ui.latestCodeTag}）",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        ui.releaseNotes.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                    )
                }
            }
        }

        // 部署进行中时展示总进度
        if (deploy.running) {
            val animatedProgress by animateFloatAsState(
                targetValue = deploy.overallProgress / 100f,
                animationSpec = tween(
                    durationMillis = 400,
                    easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f),
                ),
                label = "deployProgress",
            )
            MaaCard {
                Text("部署进行中", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${deploy.overallProgress}%  ${deploy.currentStep?.message.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 检测到需要登录时，直接把二维码摆在首页（首次运行最容易卡在这里）
        LoginQrCard(
            qr = qr,
            runningTask = ui.runningTask,
            onRefresh = { vm.rescanQr() },
        )

        // ---------------- 主操作 ----------------
        val deployPress = remember { MutableInteractionSource() }
        val runPress = remember { MutableInteractionSource() }
        if (!ui.runtimeReady || !ui.deployed) {
            Button(
                onClick = onGoDeploy,
                interactionSource = deployPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .pressScale(deployPress),
                enabled = !deploy.running,
            ) {
                Text("一键部署", style = MaterialTheme.typography.titleMedium)
            }
        }

        Button(
            onClick = { vm.runSelectedTask() },
            interactionSource = runPress,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .pressScale(runPress),
            enabled = ui.runtimeReady && !ui.runningTask,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
            Text("一键运行任务", style = MaterialTheme.typography.titleMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
            OutlinedButton(
                onClick = { vm.stopTask() },
                modifier = Modifier.weight(1f),
                enabled = ui.runningTask,
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("停止")
            }
            FilledTonalButton(
                onClick = onOpenTerminal,
                modifier = Modifier.weight(1f),
                enabled = ui.runtimeReady,
            ) {
                Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("终端")
            }
        }

        // ---------------- 最近日志（点击进入全屏日志页） ----------------
        val logs by vm.logs.collectAsState()
        MaaCard(onClick = onOpenLogs) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最近输出",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "查看全部 ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (logs.isEmpty()) {
                Text(
                    "暂无输出。部署或运行任务后这里会显示实时日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logs.takeLast(6).forEach { entry ->
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                        color = entry.level.color(),
                        maxLines = 2,
                    )
                }
            }
        }

        Spacer(Modifier.height(MaaDesignTokens.Spacing.xxl))
    }
}

@Composable
private fun VersionRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    dot: Boolean = false,
    checking: Boolean,
    checkEnabled: Boolean,
    onCheck: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (dot) {
                    Spacer(Modifier.size(6.dp))
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                }
            }
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
        OutlinedButton(
            onClick = onCheck,
            enabled = checkEnabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (checking) {
                SmallSpinner()
                Spacer(Modifier.size(6.dp))
            }
            Text(if (checking) "检查中…" else "检查")
        }
    }
}

private fun openUrl(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun copyGap(context: Context, report: UpstreamGapChecker.Report) {
    val sb = StringBuilder("M7A 上游新增功能（相对构建基线 ${report.baselineTag ?: "?"}）\n")
    if (report.newConfigKeys.isNotEmpty()) {
        sb.append("配置键：\n")
        report.newConfigKeys.forEach {
            sb.append("- ${it.name}（${if (it.supported) "已支持" else "未支持"}）\n")
        }
    }
    if (report.newTasks.isNotEmpty()) {
        sb.append("任务：\n")
        report.newTasks.forEach {
            sb.append("- ${it.name}（${if (it.supported) "已支持" else "未支持"}）\n")
        }
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("M7A 上游新增功能", sb.toString()))
}
