package com.miguanm7a.hsr.deploy

import android.content.Context
import java.io.File

/**
 * config.yaml 的生成与增量同步。
 *
 * ## v2：配置文件就在宿主的工程目录里
 *
 * 源码目录已经整体 bind 到容器 `/m7a`，所以 `m7a-project/config.yaml` 在宿主
 * 和容器里是**同一个文件**。不再需要「从容器里拉出来 → 改 → 推回去」这一套，
 * 也就少了一次 proot 调用和一类路径映射错误。
 *
 * ## 为什么是增量修改而不是整份生成
 *
 * March7thAssistant 会把整份合并配置 dump 回 `config.yaml`，其中包含状态字段：
 *
 * ```yaml
 * echo_of_war_timestamp: 1730000000
 * weekly_relic_cleanup_timestamp: 1730000001
 * daily_tasks: []
 * power_plan: [...]
 * ```
 *
 * 整份覆盖会把它们清零，程序就会以为「历战余响没打过」「每周遗器没清过」
 * 而重复执行。所以必须先读出现有文件当基底，再只改我们管理的键。
 */
object ConfigSync {

    /**
     * 刷新工程目录里的 config.yaml。
     *
     * @return 写好的配置文件（宿主 File，同时等于容器里的 `/m7a/config.yaml`）
     */
    fun refresh(
        context: Context,
        settings: DeploySettings,
        onLog: (String) -> Unit,
    ): File {
        SharedPaths.ensure(context)
        val file = SharedPaths.configFile(context)
        val base = if (file.isFile && file.length() > 0) {
            file.readText(Charsets.UTF_8)
        } else {
            onLog("    未找到现有配置，使用模板")
            DeployScripts.configYamlTemplate()
        }

        val patched = ConfigPatcher.upsertTopLevel(
            base,
            DeployScripts.configScalars(
                afterFinish = settings.afterFinish(),
                scheduledTime = settings.runDailyTime(),
                loopMode = settings.loopMode(),
                logLevel = settings.logLevel(),
                usePaidTime = settings.usePaidTime(),
                toggles = emptyMap(),
                debugPort = settings.monitorPort(),
            ),
        )
        file.writeText(patched, Charsets.UTF_8)
        onLog("    配置已更新（${patched.length} 字节；任务开关与配置文件页的修改已保留）")
        return file
    }
}
