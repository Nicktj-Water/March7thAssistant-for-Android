package com.miguanm7a.hsr.deploy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 对比「打造 APK 时打进包里的上游基线」与「GitHub 上最新 tag 的上游文件」，
 * 找出 APK 构建之后上游新增的配置键与任务，并标记 App 是否已经支持。
 *
 * 基线由 scripts/make_baseline.py 在 CI 里生成，放进 assets/meta/upstream-baseline.json。
 * 运行时只下载两个很小的文本文件（config.example.yaml、utils/tasks.py），失败时降级为
 * 「仅版本检查」，不阻塞更新流程。
 */
object UpstreamGapChecker {

    private const val REPO_RAW = "https://raw.githubusercontent.com/moesnow/March7thAssistant/%s/%s"
    private const val MIRROR_PREFIX = "https://ghfast.top/"
    private const val CONFIG_PATH = "assets/config/config.example.yaml"
    private const val TASKS_PATH = "utils/tasks.py"
    private const val BASELINE_ASSET = "meta/upstream-baseline.json"
    private const val UA = "M7A-Android/2.0 (Termux proot)"

    data class GapItem(val name: String, val supported: Boolean)

    data class Report(
        val tag: String,
        val baselineTag: String?,
        val newConfigKeys: List<GapItem>,
        val newTasks: List<GapItem>,
        val warning: String? = null,
    ) {
        val isEmpty: Boolean get() = newConfigKeys.isEmpty() && newTasks.isEmpty()
    }

    /** App 当前能编辑/写入的配置键（高级配置 + 开关 + 部署时写入的标量）。 */
    fun supportedConfigKeys(): Set<String> {
        val keys = LinkedHashSet<String>()
        AdvancedConfig.FIELDS.forEach { keys += it.key }
        TaskToggles.ALL.forEach { keys += it.key }
        keys += DeployScripts.configScalars(
            afterFinish = AfterFinish.EXIT,
            scheduledTime = "04:00",
            loopMode = "scheduled",
            logLevel = "DEBUG",
            usePaidTime = false,
            toggles = emptyMap(),
        ).keys
        return keys
    }

    /** App 任务页能直接运行的任务 ID。 */
    fun supportedTasks(): Set<String> =
        M7aTask.entries.mapNotNull { it.arg ?: "main" }.toSet()

    fun check(context: Context, tag: String): Report {
        val baseline = loadBaseline(context)
        if (baseline == null) {
            return Report(tag, null, emptyList(), emptyList(), "APK 内缺少上游基线，无法对比新增功能")
        }

        val configText = runCatching { httpText(REPO_RAW.format(tag, CONFIG_PATH)) }.getOrNull()
        val tasksText = runCatching { httpText(REPO_RAW.format(tag, TASKS_PATH)) }.getOrNull()
        if (configText == null && tasksText == null) {
            return Report(tag, baseline.optString("source_tag").ifBlank { null }, emptyList(), emptyList(), "拉取上游文件失败，跳过功能对比")
        }

        val supportedConfig = supportedConfigKeys()
        val supportedTaskIds = supportedTasks()

        val newConfig = if (configText != null) {
            val keys = parseConfigKeys(configText)
            (keys - baseline.optJSONArray("config_keys").toStringSet())
                .sorted()
                .map { GapItem(it, it in supportedConfig) }
        } else {
            emptyList()
        }

        val newTasks = if (tasksText != null) {
            val tasks = parseTaskIds(tasksText)
            (tasks - baseline.optJSONArray("tasks").toStringSet())
                .sorted()
                .map { GapItem(it, it in supportedTaskIds) }
        } else {
            emptyList()
        }

        return Report(
            tag = tag,
            baselineTag = baseline.optString("source_tag").ifBlank { null },
            newConfigKeys = newConfig,
            newTasks = newTasks,
        )
    }

    private fun loadBaseline(context: Context): JSONObject? = runCatching {
        context.assets.open(BASELINE_ASSET).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { optString(it).ifBlank { null } }.toSet()
    }

    private fun parseConfigKeys(text: String): Set<String> = text.lineSequence()
        .mapNotNull { Regex("^([A-Za-z_][A-Za-z0-9_]*):").find(it)?.groupValues?.get(1) }
        .toSet()

    private fun parseTaskIds(text: String): Set<String> {
        val block = Regex("AVAILABLE_TASKS\\s*=\\s*\\{([\\s\\S]*?)\\n\\}").find(text)?.groupValues?.get(1)
            ?: return emptySet()
        return Regex("(?m)^\\s+\"([a-z0-9_]+)\"\\s*:").findAll(block)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun httpText(url: String): String {
        val attempts = listOf(url, MIRROR_PREFIX + url)
        var last: Exception? = null
        for (u in attempts) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "text/plain")
                }
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("请求失败")
    }
}
