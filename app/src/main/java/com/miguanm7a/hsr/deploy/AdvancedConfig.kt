package com.miguanm7a.hsr.deploy

import android.content.Context

/**
 * 「高级配置」数据模型 + 对 config.yaml 的读写。
 *
 * ## 为什么直接改 config.yaml
 *
 * v2 之后 `m7a-project/config.yaml` 就是容器里的 `/m7a/config.yaml`，宿主改完
 * 下一次运行即生效，不需要再 proot 进容器。这里只动**用户选中的顶层键**，
 * 其余键（包括 M7A 写入的时间戳、状态位）原样保留。
 *
 * ## 值格式
 *
 * UI 上统一用「显示字符串」表示一个字段，写盘时再按 YAML 结构序列化：
 * - BOOL：`true` / `false`
 * - INT：整数
 * - ENUM：选项 value（写盘加引号）
 * - TEXT：单行文本（写盘加引号）
 * - MAP：每行 `键=值`，写成缩进映射
 * - TEAM：每行 `角色名, 位置`，写成 `- - 角色名` / `- 位置`
 * - LEVELS：`起始, 结束`，写成两行列表
 * - LINES：每行一项，写成列表
 */
object AdvancedConfig {

    private const val TEAM_A = "Asta, -1\nJingYuan, 1\nTingyun, 2\nHuohuo, 1"
    private const val TEAM_B = "FuXuan, 1\nBronya, 1\nPela, -1\nQingque, 1"
    private const val TEAM_C = "DanHeng, -1"

    private const val INSTANCE_NAMES = "拟造花萼（金）=回忆之蕾\n" +
        "拟造花萼（赤）=收容舱段\n凝滞虚影=无\n侵蚀隧洞=睿治之径\n" +
        "饰品提取=永恒笑剧\n历战余响=毁灭的开端"
    private const val INSTANCE_COUNTS = "拟造花萼（金）=24\n" +
        "拟造花萼（赤）=24\n凝滞虚影=8\n侵蚀隧洞=6\n饰品提取=6\n历战余响=3"
    private const val STATION_PRIORITY = "战斗=1\n精英=1\n事件=2\n奖励=2\n异常=2\n" +
        "铸造=2\n空白=3\n商店=3\n财富=3"

    private val INSTANCE_OPTIONS = listOf(
        "拟造花萼（金）", "拟造花萼（赤）", "凝滞虚影", "侵蚀隧洞", "饰品提取",
    )
    private const val INSTANCE_LABEL = "拟造花萼（金）/（赤）、凝滞虚影、侵蚀隧洞、饰品提取"

    val FIELDS: List<AdvancedField> = listOf(
        // ── 副本与体力 ────────────────────────────────────────────────
        AdvancedField(
            "power_plan_keep", "保留体力计划", AdvancedGroup.INSTANCE, AdvancedType.BOOL,
            default = "false",
            desc = "执行后保留整份体力计划；关闭时按剩余次数更新并删除已完成的计划。",
        ),
        AdvancedField(
            "instance_type", "副本类型", AdvancedGroup.INSTANCE, AdvancedType.ENUM,
            default = "拟造花萼（金）",
            options = INSTANCE_OPTIONS.map { it to it },
            desc = INSTANCE_LABEL,
        ),
        AdvancedField(
            "calyx_golden_preference", "金花萼偏好地区", AdvancedGroup.INSTANCE, AdvancedType.ENUM,
            default = "Jarilo-VI",
            options = listOf(
                "Jarilo-VI" to "雅利洛-VI",
                "XianzhouLuofu" to "仙舟「罗浮」",
                "Penacony" to "匹诺康尼",
            ),
        ),
        AdvancedField(
            "instance_names", "副本名称", AdvancedGroup.INSTANCE, AdvancedType.MAP,
            default = INSTANCE_NAMES,
            desc = "每行「副本类型=名称」，必须是游戏内准确名称。",
            placeholder = "拟造花萼（金）=回忆之蕾",
        ),
        AdvancedField(
            "instance_names_challenge_count", "连续挑战次数", AdvancedGroup.INSTANCE, AdvancedType.MAP,
            default = INSTANCE_COUNTS,
            desc = "每行「副本类型=次数」，超过剩余体力时按实际次数执行。",
            placeholder = "拟造花萼（金）=24",
        ),
        AdvancedField(
            "tp_before_instance", "进本前先传送", AdvancedGroup.INSTANCE, AdvancedType.BOOL,
            default = "false", desc = "进入副本前先传送到任意锚点，降低卡图概率。",
        ),
        AdvancedField(
            "build_target_enable", "启用培养目标", AdvancedGroup.INSTANCE, AdvancedType.BOOL,
            default = "false", desc = "按游戏内「培养目标」自动刷所需副本。",
        ),
        AdvancedField(
            "build_target_scheme", "培养目标识别方式", AdvancedGroup.INSTANCE, AdvancedType.ENUM,
            default = "instance",
            options = listOf("instance" to "按副本名称", "drop" to "按掉落素材"),
        ),
        AdvancedField(
            "build_target_ornament_weekly_count", "每周饰品提取次数", AdvancedGroup.INSTANCE, AdvancedType.INT,
            default = "1", desc = "培养目标每周执行饰品提取的次数，0–7。",
        ),
        AdvancedField(
            "build_target_use_user_instance_when_only_erosion_and_ornament",
            "仅识别到侵蚀/饰品时用自定义副本", AdvancedGroup.INSTANCE, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "break_down_level_four_relicset", "每周分解四星遗器", AdvancedGroup.INSTANCE, AdvancedType.BOOL,
            default = "false", desc = "每周在设定日期清理四星及以下遗器。",
        ),
        AdvancedField(
            "weekly_relic_smart_discard_enable", "每周智能弃置五星", AdvancedGroup.INSTANCE, AdvancedType.BOOL,
            default = "false", desc = "先智能弃置五星遗器，再分解已弃置遗器。",
        ),
        AdvancedField(
            "weekly_relic_cleanup_day_of_week", "遗器清理日", AdvancedGroup.INSTANCE, AdvancedType.INT,
            default = "1", desc = "1=周一 … 7=周日，在游戏刷新时间后执行。",
        ),
        AdvancedField(
            "merge_immersifier_limit", "沉浸器上限", AdvancedGroup.INSTANCE, AdvancedType.INT,
            default = "12", desc = "优先合成沉浸器时使用的上限。",
        ),
        AdvancedField(
            "echo_of_war_start_day_of_week", "历战余响起始日", AdvancedGroup.INSTANCE, AdvancedType.INT,
            default = "1", desc = "1=周一 … 7=周日；此日之前不执行历战余响。",
        ),
        AdvancedField(
            "borrow_character_enable", "强制使用支援角色", AdvancedGroup.INSTANCE, AdvancedType.BOOL,
            default = "false", desc = "开启后必须选到支援角色才开战。",
        ),
        AdvancedField(
            "borrow_scroll_times", "支援列表滚动次数", AdvancedGroup.INSTANCE, AdvancedType.INT,
            default = "10", desc = "支援列表最多滚动查找多少次。",
        ),

        // ── 模拟宇宙 / 差分宇宙 / 货币战争 ───────────────────────────
        AdvancedField(
            "universe_enable", "启用模拟宇宙", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "universe_operation_mode", "运行方式", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "exe",
            options = listOf("exe" to "集成", "source" to "源码"),
            desc = "集成模式使用 3rdparty 内的 Auto_Simulated_Universe。",
        ),
        AdvancedField(
            "universe_category", "宇宙类型", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "divergent",
            options = listOf("divergent" to "差分宇宙", "universe" to "模拟宇宙"),
        ),
        AdvancedField(
            "universe_enable_gpu", "启用 GPU 加速", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false", desc = "不稳定时请关闭（云游戏模式通常不需要）。",
        ),
        AdvancedField(
            "universe_bonus_enable", "自动领取沉浸奖励", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "universe_frequency", "运行频率", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "weekly",
            options = listOf("weekly" to "每周", "daily" to "每天"),
        ),
        AdvancedField(
            "universe_count", "每次尝试次数", AdvancedGroup.UNIVERSE, AdvancedType.INT,
            default = "34",
        ),
        AdvancedField(
            "universe_timeout", "超时时间（小时）", AdvancedGroup.UNIVERSE, AdvancedType.INT,
            default = "20",
        ),
        AdvancedField(
            "divergent_type", "差分宇宙模式", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "normal",
            options = listOf("normal" to "常规演算", "cycle" to "周期演算"),
        ),
        AdvancedField(
            "divergent_team_type", "差分队伍类型", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "终结技",
            options = listOf("追击", "dot", "终结技", "击破", "盾反").map { it to it },
        ),
        AdvancedField(
            "universe_fate", "模拟宇宙命途", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "不配置",
            options = listOf(
                "不配置", "存护", "记忆", "虚无", "丰饶", "巡猎",
                "毁灭", "欢愉", "繁育", "智识", "同谐",
            ).map { it to it },
        ),
        AdvancedField(
            "universe_difficulty", "模拟宇宙难度", AdvancedGroup.UNIVERSE, AdvancedType.INT,
            default = "0",
        ),
        AdvancedField(
            "weekly_divergent_enable", "启用差分积分奖励", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "weekly_divergent_type", "积分奖励模式", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "cycle",
            options = listOf("normal" to "常规演算", "cycle" to "周期演算"),
        ),
        AdvancedField(
            "weekly_divergent_level", "积分奖励难度", AdvancedGroup.UNIVERSE, AdvancedType.INT,
            default = "5", desc = "1–6，6 对应常规演算星阶模式。",
        ),
        AdvancedField(
            "weekly_divergent_bonus_enable", "积分后领饰品", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "weekly_divergent_stable_mode", "低性能兼容模式", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false", desc = "提高事件/随意门交互成功率；云游戏强制使用。",
        ),
        AdvancedField(
            "divergent_auto_save_enable", "探索后自动存档", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "true", desc = "保存到第一个空存档位；没有空位时跳过。",
        ),
        AdvancedField(
            "divergent_station_priority_enable", "自定义站点优先级", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false", desc = "关闭时使用默认优先级。",
        ),
        AdvancedField(
            "divergent_station_priority", "站点优先级", AdvancedGroup.UNIVERSE, AdvancedType.MAP,
            default = STATION_PRIORITY,
            desc = "每行「站点=优先级」，数字越小越优先。",
            placeholder = "战斗=1",
        ),
        AdvancedField(
            "divergent_station_disabled", "禁用站点", AdvancedGroup.UNIVERSE, AdvancedType.LINES,
            default = "", desc = "每行一个站点名；有重抽次数时会尝试避开。",
            placeholder = "商店\n财富",
        ),
        AdvancedField(
            "currencywars_enable", "启用货币战争", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "currencywars_type", "货币战争模式", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "overclock",
            options = listOf("normal" to "标准博弈", "overclock" to "超频博弈"),
        ),
        AdvancedField(
            "currencywars_rank_difficulty", "职级难度", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "lowest",
            options = listOf(
                "current" to "当前职级",
                "lowest" to "最低职级",
                "highest" to "最高职级",
            ),
        ),
        AdvancedField(
            "currencywars_bonus_enable", "积分后快速提取饰品", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "currencywars_fast_mode", "速通模式", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "true", desc = "仅在首领节点尝试装备武器。",
        ),
        AdvancedField(
            "currencywars_strategy", "货币战争策略", AdvancedGroup.UNIVERSE, AdvancedType.ENUM,
            default = "default",
            options = listOf("default" to "默认", "aglaea" to "阿格莱雅"),
        ),
        AdvancedField(
            "currencywars_remembrance_trailblazer_name",
            "「开拓者·记忆」显示名", AdvancedGroup.UNIVERSE, AdvancedType.TEXT,
            default = "", desc = "阿格莱雅策略下使用；留空则跳过该角色。",
        ),
        AdvancedField(
            "currencywars_strategy_restart_on_special_tags",
            "特殊词条允许重开", AdvancedGroup.UNIVERSE, AdvancedType.BOOL,
            default = "true",
        ),

        // ── 忘却 / 虚构 / 末日 ───────────────────────────────────────
        AdvancedField("forgottenhall_enable", "启用混沌回忆", AdvancedGroup.ENDGAME, AdvancedType.BOOL, default = "false"),
        AdvancedField("forgottenhall_stellar_enable", "混沌回忆星启模式", AdvancedGroup.ENDGAME, AdvancedType.BOOL, default = "false"),
        AdvancedField(
            "forgottenhall_level", "混沌回忆关卡范围", AdvancedGroup.ENDGAME, AdvancedType.LEVELS,
            default = "10, 12", desc = "格式「起始, 结束」。",
        ),
        AdvancedField("forgottenhall_team1", "混沌回忆 1 队", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_A, desc = "每行「角色名, 位置」，位置 -1 表示任意。"),
        AdvancedField("forgottenhall_team2", "混沌回忆 2 队", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_B, desc = "每行「角色名, 位置」。"),
        AdvancedField("forgottenhall_team3", "混沌回忆 3 队（星启）", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_C, desc = "星启模式第三队，1–4 名角色，不能留空。"),
        AdvancedField("purefiction_enable", "启用虚构叙事", AdvancedGroup.ENDGAME, AdvancedType.BOOL, default = "false"),
        AdvancedField("purefiction_stellar_enable", "虚构叙事星启模式", AdvancedGroup.ENDGAME, AdvancedType.BOOL, default = "false"),
        AdvancedField(
            "purefiction_level", "虚构叙事关卡范围", AdvancedGroup.ENDGAME, AdvancedType.LEVELS,
            default = "3, 4", desc = "格式「起始, 结束」。",
        ),
        AdvancedField("purefiction_team1", "虚构叙事 1 队", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_A, desc = "每行「角色名, 位置」。"),
        AdvancedField("purefiction_team2", "虚构叙事 2 队", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_B, desc = "每行「角色名, 位置」。"),
        AdvancedField("purefiction_team3", "虚构叙事 3 队（星启）", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_C, desc = "星启模式第三队，不能留空。"),
        AdvancedField("apocalyptic_enable", "启用末日幻影", AdvancedGroup.ENDGAME, AdvancedType.BOOL, default = "false"),
        AdvancedField("apocalyptic_stellar_enable", "末日幻影星启模式", AdvancedGroup.ENDGAME, AdvancedType.BOOL, default = "false"),
        AdvancedField(
            "apocalyptic_level", "末日幻影关卡范围", AdvancedGroup.ENDGAME, AdvancedType.LEVELS,
            default = "3, 4", desc = "格式「起始, 结束」。",
        ),
        AdvancedField("apocalyptic_team1", "末日幻影 1 队", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_A, desc = "每行「角色名, 位置」。"),
        AdvancedField("apocalyptic_team2", "末日幻影 2 队", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_B, desc = "每行「角色名, 位置」。"),
        AdvancedField("apocalyptic_team3", "末日幻影 3 队（星启）", AdvancedGroup.ENDGAME, AdvancedType.TEAM, default = TEAM_C, desc = "星启模式第三队，不能留空。"),

        // ── 资产与兑换码 ────────────────────────────────────────────
        AdvancedField("asset_manager_enable", "启用资产管理", AdvancedGroup.ASSET, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_self_molding_resin_enable", "每月合成自塑尘脂", AdvancedGroup.ASSET, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_ember_special_pass_enable", "余烬换星轨专票", AdvancedGroup.ASSET, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_ember_regular_pass_enable", "余烬换星轨通票", AdvancedGroup.ASSET, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_ember_tracks_of_destiny_enable", "余烬换命运足迹", AdvancedGroup.ASSET, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_lc3_star_superimpose_enable", "三星光锥自动叠加", AdvancedGroup.ASSET, AdvancedType.BOOL, default = "false"),
        AdvancedField(
            "redemption_code", "兑换码", AdvancedGroup.ASSET, AdvancedType.LINES,
            default = "", desc = "每行一个兑换码；执行时会自动领取。",
            placeholder = "ABCD1234\nEFGH5678",
        ),

        // ── 活动 ────────────────────────────────────────────────────
        AdvancedField("activity_gardenofplenty_enable", "花藏繁生活动", AdvancedGroup.ACTIVITY, AdvancedType.BOOL, default = "false"),
        AdvancedField(
            "activity_gardenofplenty_instance_type", "花藏繁生副本类型", AdvancedGroup.ACTIVITY, AdvancedType.ENUM,
            default = "拟造花萼（金）", options = INSTANCE_OPTIONS.map { it to it },
        ),
        AdvancedField("activity_realmofthestrange_enable", "异器盈界活动", AdvancedGroup.ACTIVITY, AdvancedType.BOOL, default = "false"),
        AdvancedField("activity_planarfissure_enable", "位面分裂活动", AdvancedGroup.ACTIVITY, AdvancedType.BOOL, default = "false"),
        AdvancedField("activity_journey_highlights_notification_enable", "活动热点通知", AdvancedGroup.ACTIVITY, AdvancedType.BOOL, default = "false"),
    )

    fun fieldsOf(group: AdvancedGroup): List<AdvancedField> = FIELDS.filter { it.group == group }

    /** 读取 config.yaml；不存在时返回内置模板，保证界面有完整默认值可读。 */
    fun readText(context: Context): String {
        val file = SharedPaths.configFile(context)
        if (file.isFile && file.length() > 0) return file.readText(Charsets.UTF_8)
        return DeployScripts.configYamlTemplate()
    }

    fun displayValues(text: String): Map<String, String> {
        val lines = text.split("\n")
        return FIELDS.associate { it.key to displayOf(it, lines) }
    }

    fun update(context: Context, field: AdvancedField, display: String): Boolean {
        return runCatching {
            SharedPaths.ensure(context)
            val file = SharedPaths.configFile(context)
            val base = if (file.isFile && file.length() > 0) {
                file.readText(Charsets.UTF_8)
            } else {
                DeployScripts.configYamlTemplate()
            }
            val updated = replaceBlock(base, field.key, format(field, display))
            file.writeText(updated, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    // ── 读 ──────────────────────────────────────────────────────────

    private fun displayOf(field: AdvancedField, lines: List<String>): String {
        val inline = inlineValue(lines, field.key)
        return when (field.type) {
            AdvancedType.BOOL -> (inline ?: field.default).let { if (it.equals("true", true)) "true" else "false" }
            AdvancedType.INT -> inline ?: field.default
            AdvancedType.ENUM, AdvancedType.TEXT -> inline ?: field.default
            AdvancedType.MAP -> parseMap(blockOf(lines, field.key)) ?: field.default
            AdvancedType.TEAM -> parseTeam(blockOf(lines, field.key)) ?: field.default
            AdvancedType.LEVELS -> parseLevels(blockOf(lines, field.key)) ?: field.default
            AdvancedType.LINES -> parseLines(blockOf(lines, field.key)) ?: field.default
        }
    }

    private fun blockOf(lines: List<String>, key: String): List<String>? {
        val idx = lines.indexOfFirst { topKeyOf(it) == key }
        if (idx < 0) return null
        val out = ArrayList<String>()
        var i = idx + 1
        while (i < lines.size && lines[i].isNotEmpty() && lines[i].first().isWhitespace()) {
            out.add(lines[i])
            i++
        }
        return out
    }

    private fun inlineValue(lines: List<String>, key: String): String? {
        val line = lines.firstOrNull { topKeyOf(it) == key } ?: return null
        val rest = line.substringAfter(':', "").substringBefore(" #").trim()
        if (rest.isEmpty()) return null
        return unquote(rest)
    }

    private fun parseMap(block: List<String>?): String? {
        if (block == null) return null
        val pairs = block.mapNotNull { raw ->
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val idx = t.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val k = unquote(t.substring(0, idx).trim())
            val v = unquote(t.substring(idx + 1).substringBefore(" #").trim())
            k to v
        }
        return pairs.joinToString("\n") { "${it.first}=${it.second}" }
    }

    private fun parseTeam(block: List<String>?): String? {
        if (block == null) return null
        val out = ArrayList<String>()
        var name: String? = null
        for (raw in block) {
            val t = raw.trim()
            when {
                t.startsWith("- - ") -> name = unquote(t.removePrefix("- - ").trim())
                t.startsWith("- ") && name != null -> {
                    out.add("$name, ${t.removePrefix("- ").trim()}")
                    name = null
                }
            }
        }
        return out.joinToString("\n")
    }

    private fun parseLevels(block: List<String>?): String? {
        if (block == null) return null
        val nums = block.mapNotNull { raw ->
            raw.trim().removePrefix("- ").trim().toIntOrNull()
        }
        return if (nums.isEmpty()) null else nums.joinToString(", ")
    }

    private fun parseLines(block: List<String>?): String? {
        if (block == null) return null
        return block.mapNotNull { raw ->
            val t = raw.trim()
            if (t.startsWith("- ")) unquote(t.removePrefix("- ").trim()) else null
        }.joinToString("\n")
    }

    // ── 写 ──────────────────────────────────────────────────────────

    private fun format(field: AdvancedField, display: String): String {
        return when (field.type) {
            AdvancedType.BOOL -> "${field.key}: ${if (display == "true") "true" else "false"}"
            AdvancedType.INT -> "${field.key}: ${display.trim().toIntOrNull() ?: field.default.toIntOrNull() ?: 0}"
            AdvancedType.ENUM -> "${field.key}: ${quote(display.trim())}"
            AdvancedType.TEXT -> "${field.key}: ${quote(display.trim())}"
            AdvancedType.LEVELS -> {
                val nums = display.split(',').mapNotNull { it.trim().toIntOrNull() }
                buildBlock(field.key, nums.map { "  - $it" })
            }
            AdvancedType.LINES -> {
                val items = display.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                buildBlock(field.key, items.map { "  - ${quote(it)}" })
            }
            AdvancedType.MAP -> {
                val items = display.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    .map { line ->
                        val idx = line.indexOf('=')
                        if (idx < 0) quote(line) + ": " + quote("")
                        else quote(line.substring(0, idx).trim()) + ": " + quote(line.substring(idx + 1).trim())
                    }
                buildBlock(field.key, items.map { "  $it" })
            }
            AdvancedType.TEAM -> {
                val items = display.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    .mapNotNull { line ->
                        val idx = line.lastIndexOf(',')
                        if (idx <= 0) return@mapNotNull null
                        val name = quote(line.substring(0, idx).trim())
                        val pos = line.substring(idx + 1).trim().toIntOrNull() ?: -1
                        listOf("  - - $name", "    - $pos")
                    }
                buildBlock(field.key, items.flatten())
            }
        }
    }

    private fun buildBlock(key: String, body: List<String>): String {
        if (body.isEmpty()) return "$key: []"
        return buildString {
            append(key).append(':')
            for (line in body) append('\n').append(line)
        }
    }

    private fun replaceBlock(text: String, key: String, block: String): String {
        val lines = text.split("\n").toMutableList()
        val idx = lines.indexOfFirst { topKeyOf(it) == key }
        val newLines = block.split("\n")
        if (idx < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            lines.add("")
            lines.addAll(newLines)
        } else {
            var end = idx + 1
            while (end < lines.size && lines[end].isNotEmpty() && lines[end].first().isWhitespace()) end++
            lines.subList(idx, end).clear()
            lines.addAll(idx, newLines)
        }
        val joined = lines.joinToString("\n")
        return if (joined.endsWith("\n")) joined else "$joined\n"
    }

    // ── 基础 ────────────────────────────────────────────────────────

    private val TOP_KEY = Regex("^([A-Za-z0-9_]+)\\s*:.*$")

    private fun topKeyOf(line: String): String? =
        TOP_KEY.matchEntire(line)?.groupValues?.get(1)

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun unquote(value: String): String {
        val t = value.trim()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        if (t.length >= 2 && t.first() == '\'' && t.last() == '\'') {
            return t.substring(1, t.length - 1).replace("''", "'")
        }
        return t
    }
}

enum class AdvancedGroup(val title: String, val subtitle: String) {
    INSTANCE("副本与体力", "刷本类型、次数、培养目标、遗器清理、支援"),
    UNIVERSE("模拟宇宙 / 差分 / 货币战争", "运行方式、命途、站点优先级、积分奖励"),
    ENDGAME("忘却 / 虚构 / 末日", "三模式开关、关卡范围与队伍"),
    ASSET("资产与兑换码", "每月兑换、光锥叠加、兑换码"),
    ACTIVITY("活动", "花藏繁生、异器盈界、位面分裂"),
}

enum class AdvancedType { BOOL, INT, ENUM, TEXT, MAP, TEAM, LEVELS, LINES }

data class AdvancedField(
    val key: String,
    val title: String,
    val group: AdvancedGroup,
    val type: AdvancedType,
    val default: String = "",
    val desc: String = "",
    val options: List<Pair<String, String>> = emptyList(),
    val placeholder: String = "",
)
