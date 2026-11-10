package com.miguanm7a.hsr.ui.config

import android.os.Bundle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miguanm7a.hsr.deploy.AdvancedConfig
import com.miguanm7a.hsr.deploy.AdvancedField
import com.miguanm7a.hsr.deploy.AdvancedGroup
import com.miguanm7a.hsr.deploy.AdvancedType
import com.miguanm7a.hsr.deploy.ConfigSync
import com.miguanm7a.hsr.deploy.DeployScripts
import com.miguanm7a.hsr.deploy.DeploySettings
import com.miguanm7a.hsr.deploy.SharedPaths
import com.miguanm7a.hsr.deploy.TermuxPaths
import com.miguanm7a.hsr.ui.components.MaaCard
import com.miguanm7a.hsr.ui.components.SectionHeader
import com.miguanm7a.hsr.ui.theme.AccentTheme
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.MaaTermuxTheme
import com.miguanm7a.hsr.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 高级配置页：把上游 config.example.yaml 里常用的副本 / 宇宙 / 终局 / 资产 / 活动
 * 配置项做成表单，直接读写 m7a-project/config.yaml。
 *
 * 两层结构：先选大分类（6 组），进组后再看具体字段 —— 87 项平铺一页太长。
 */
class AdvancedConfigActivity : AppCompatActivity() {

    private var generating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = DeploySettings(applicationContext)
        val themeMode = runCatching { ThemeMode.valueOf(settings.themeMode()) }
            .getOrDefault(ThemeMode.SYSTEM)
        val accent = runCatching { AccentTheme.valueOf(settings.accentTheme()) }
            .getOrDefault(AccentTheme.MARCH7)

        setContent {
            MaaTermuxTheme(themeMode = themeMode, accentTheme = accent) {
                AdvancedConfigScreen(onBack = { finish() })
            }
        }
    }

    /** 离开配置文件页时自动保存当前 config.yaml 并重新生成启动脚本。 */
    override fun finish() {
        if (!generating) {
            generating = true
            val ctx = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching {
                    val settings = DeploySettings(ctx)
                    ConfigSync.refresh(ctx, settings, {})
                    val binds = DeployScripts.bindArgs(SharedPaths.guestProjectDir(ctx))
                    val file = File(TermuxPaths.homeDir(ctx), "start-m7a.sh")
                    file.parentFile?.mkdirs()
                    file.writeText(
                        DeployScripts.startScript(
                            alias = settings.containerAlias(),
                            afterFinish = settings.afterFinish(),
                            logLevel = settings.logLevel(),
                            binds = binds,
                        ),
                        Charsets.UTF_8,
                    )
                    file.setExecutable(true, false)
                }
                val msg = result.fold(
                    onSuccess = { "已保存并生成启动脚本" },
                    onFailure = { "生成启动脚本失败：${it.message}" },
                )
                runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
            }
        }
        super.finish()
    }
}

@Composable
private fun AdvancedConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editing by remember { mutableStateOf<AdvancedField?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<AdvancedGroup?>(null) }

    LaunchedEffect(Unit) {
        values = withContext(Dispatchers.IO) {
            AdvancedConfig.displayValues(AdvancedConfig.readText(context))
        }
        loaded = true
    }

    BackHandler(enabled = selectedGroup != null) { selectedGroup = null }

    fun save(field: AdvancedField, display: String, onDone: () -> Unit) {
        val ok = AdvancedConfig.update(context, field, display)
        if (ok) {
            values = values + (field.key to display)
            onDone()
            Toast.makeText(context, "已保存，下次运行生效", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "保存失败，请先完成部署", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        val group = selectedGroup
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (group == null) onBack() else selectedGroup = null
                },
            ) { Text(if (group == null) "← 返回" else "← 分类") }
            Text(
                group?.title ?: "配置文件设置",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (group == null) {
                    "${AdvancedConfig.FIELDS.size} 项"
                } else {
                    "${AdvancedConfig.fieldsOf(group).size} 项"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!loaded) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            return@Column
        }

        AnimatedContent(
            targetState = selectedGroup,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            transitionSpec = {
                val forward = targetState != null
                val enter = slideInHorizontally(
                    animationSpec = tween(
                        durationMillis = MaaDesignTokens.Motion.page,
                        easing = MaaDesignTokens.Motion.EaseOut,
                    ),
                    initialOffsetX = { w -> if (forward) w / 8 else -w / 8 },
                ) + fadeIn(tween(MaaDesignTokens.Motion.fast))
                val exit = slideOutHorizontally(
                    animationSpec = tween(
                        durationMillis = MaaDesignTokens.Motion.page,
                        easing = MaaDesignTokens.Motion.EaseIn,
                    ),
                    targetOffsetX = { w -> if (forward) -w / 12 else w / 12 },
                ) + fadeOut(tween(MaaDesignTokens.Motion.fast))
                enter togetherWith exit
            },
            label = "advancedConfigGroup",
        ) { target ->
            if (target == null) {
                CategoryList(
                    onOpen = { selectedGroup = it },
                )
            } else {
                GroupFields(
                    group = target,
                    values = values,
                    onToggle = { field, checked ->
                        save(field, if (checked) "true" else "false") {}
                    },
                    onEdit = { editing = it },
                )
            }
        }
    }

    val field = editing
    if (field != null) {
        EditDialog(
            field = field,
            initial = values[field.key] ?: field.default,
            onDismiss = { editing = null },
            onSave = { display -> save(field, display) { editing = null } },
        )
    }
}

@Composable
private fun CategoryList(onOpen: (AdvancedGroup) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MaaDesignTokens.Spacing.listHorizontal,
            end = MaaDesignTokens.Spacing.listHorizontal,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        item {
            MaaCard {
                Text(
                    "按电脑端设置页的结构分组；修改直接写入 m7a-project/config.yaml，离开本页时自动保存并生成启动脚本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "定时、日志级别、容器名、外观等运行参数仍在「设置」页。时间戳只读，由 M7A 写入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(AdvancedGroup.entries, key = { it.name }) { group ->
            val count = AdvancedConfig.fieldsOf(group).size
            MaaCard(onClick = { onOpen(group) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$count 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "›",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupFields(
    group: AdvancedGroup,
    values: Map<String, String>,
    onToggle: (AdvancedField, Boolean) -> Unit,
    onEdit: (AdvancedField) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MaaDesignTokens.Spacing.listHorizontal,
            end = MaaDesignTokens.Spacing.listHorizontal,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        item {
            SectionHeader(group.title)
            Text(
                group.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(AdvancedConfig.fieldsOf(group), key = { it.key }) { field ->
            FieldRow(
                field = field,
                value = values[field.key] ?: field.default,
                onToggle = { checked -> onToggle(field, checked) },
                onEdit = { onEdit(field) },
            )
        }
    }
}

@Composable
private fun FieldRow(
    field: AdvancedField,
    value: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    MaaCard(onClick = if (field.type == AdvancedType.BOOL || field.type == AdvancedType.READONLY) null else onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(field.title, style = MaterialTheme.typography.titleSmall)
                if (field.desc.isNotBlank()) {
                    Text(
                        field.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    summary(field, value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (field.type == AdvancedType.BOOL) {
                Switch(checked = value == "true", onCheckedChange = onToggle)
            } else if (field.type != AdvancedType.READONLY) {
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

private fun summary(field: AdvancedField, value: String): String {
    return when (field.type) {
        AdvancedType.BOOL -> if (value == "true") "已开启" else "已关闭"
        AdvancedType.ENUM -> field.options.firstOrNull { it.first == value }?.second ?: value
        AdvancedType.READONLY -> formatTimestamp(value)
        AdvancedType.RAW -> {
            val n = value.split('\n').count { it.isNotBlank() }
            if (n == 0) "（空 · 使用默认）" else "原始 YAML · $n 行"
        }
        AdvancedType.MAP, AdvancedType.TEAM, AdvancedType.LINES -> {
            val lines = value.split('\n').filter { it.isNotBlank() }
            when {
                lines.isEmpty() -> "（空）"
                lines.size == 1 -> lines.first()
                else -> "${lines.first()} 等 ${lines.size} 项"
            }
        }
        else -> value.ifBlank { "（空）" }
    }
}

private fun formatTimestamp(raw: String): String {
    val sec = raw.trim().toLongOrNull() ?: return "—"
    if (sec <= 0L) return "尚未执行"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000L))
}

@Composable
private fun EditDialog(
    field: AdvancedField,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(field.key) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (field.desc.isNotBlank()) {
                    Text(
                        field.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (field.type == AdvancedType.ENUM) {
                    field.options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = text == value,
                                onClick = { text = value },
                            )
                            Text(label)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = field.type == AdvancedType.INT ||
                            field.type == AdvancedType.TEXT,
                        minLines = when (field.type) {
                            AdvancedType.RAW -> 10
                            AdvancedType.MAP, AdvancedType.TEAM,
                            AdvancedType.LINES, AdvancedType.LEVELS -> 6
                            else -> 1
                        },
                        placeholder = { Text(field.placeholder.ifBlank { field.default }) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.type == AdvancedType.INT) {
                                KeyboardType.Number
                            } else {
                                KeyboardType.Text
                            },
                        ),
                    )
                }
                HorizontalDivider()
                TextButton(onClick = { text = field.default }) {
                    Text("恢复默认：${summary(field, field.default)}")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
