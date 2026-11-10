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
 * - RAW：原始 YAML 块（复杂嵌套结构），按原样缩进写回
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

    val FIELDS: List<AdvancedField> by lazy {
        listOf(
        // ── 副本与体力 ────────────────────────────────────────────────
        AdvancedField(
            "power_plan_keep", "保留体力计划", AdvancedGroup.POWER, AdvancedType.BOOL,
            default = "false",
            desc = "执行后保留整份体力计划；关闭时按剩余次数更新并删除已完成的计划。",
        ),
        AdvancedField(
            "instance_type", "副本类型", AdvancedGroup.POWER, AdvancedType.ENUM,
            default = "拟造花萼（金）",
            options = INSTANCE_OPTIONS.map { it to it },
            desc = INSTANCE_LABEL,
        ),
        AdvancedField(
            "calyx_golden_preference", "金花萼偏好地区", AdvancedGroup.POWER, AdvancedType.ENUM,
            default = "Jarilo-VI",
            options = listOf(
                "Jarilo-VI" to "雅利洛-VI",
                "XianzhouLuofu" to "仙舟「罗浮」",
                "Penacony" to "匹诺康尼",
            ),
        ),
        AdvancedField(
            "instance_names", "副本名称", AdvancedGroup.POWER, AdvancedType.MAP,
            default = INSTANCE_NAMES,
            desc = "每行「副本类型=名称」，必须是游戏内准确名称。",
            placeholder = "拟造花萼（金）=回忆之蕾",
        ),
        AdvancedField(
            "instance_names_challenge_count", "连续挑战次数", AdvancedGroup.POWER, AdvancedType.MAP,
            default = INSTANCE_COUNTS,
            desc = "每行「副本类型=次数」，超过剩余体力时按实际次数执行。",
            placeholder = "拟造花萼（金）=24",
        ),
        AdvancedField(
            "tp_before_instance", "进本前先传送", AdvancedGroup.POWER, AdvancedType.BOOL,
            default = "false", desc = "进入副本前先传送到任意锚点，降低卡图概率。",
        ),
        AdvancedField(
            "build_target_enable", "启用培养目标", AdvancedGroup.POWER, AdvancedType.BOOL,
            default = "false", desc = "按游戏内「培养目标」自动刷所需副本。",
        ),
        AdvancedField(
            "build_target_scheme", "培养目标识别方式", AdvancedGroup.POWER, AdvancedType.ENUM,
            default = "instance",
            options = listOf("instance" to "按副本名称", "drop" to "按掉落素材"),
        ),
        AdvancedField(
            "build_target_ornament_weekly_count", "每周饰品提取次数", AdvancedGroup.POWER, AdvancedType.INT,
            default = "1", desc = "培养目标每周执行饰品提取的次数，0–7。",
        ),
        AdvancedField(
            "build_target_use_user_instance_when_only_erosion_and_ornament",
            "仅识别到侵蚀/饰品时用自定义副本", AdvancedGroup.POWER, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "break_down_level_four_relicset", "每周分解四星遗器", AdvancedGroup.POWER, AdvancedType.BOOL,
            default = "false", desc = "每周在设定日期清理四星及以下遗器。",
        ),
        AdvancedField(
            "weekly_relic_smart_discard_enable", "每周智能弃置五星", AdvancedGroup.POWER, AdvancedType.BOOL,
            default = "false", desc = "先智能弃置五星遗器，再分解已弃置遗器。",
        ),
        AdvancedField(
            "weekly_relic_cleanup_day_of_week", "遗器清理日", AdvancedGroup.POWER, AdvancedType.INT,
            default = "1", desc = "1=周一 … 7=周日，在游戏刷新时间后执行。",
        ),
        AdvancedField(
            "merge_immersifier_limit", "沉浸器上限", AdvancedGroup.POWER, AdvancedType.INT,
            default = "12", desc = "优先合成沉浸器时使用的上限。",
        ),
        AdvancedField(
            "echo_of_war_start_day_of_week", "历战余响起始日", AdvancedGroup.POWER, AdvancedType.INT,
            default = "1", desc = "1=周一 … 7=周日；此日之前不执行历战余响。",
        ),
        AdvancedField(
            "borrow_character_enable", "强制使用支援角色", AdvancedGroup.POWER, AdvancedType.BOOL,
            default = "false", desc = "开启后必须选到支援角色才开战。",
        ),
        AdvancedField(
            "borrow_scroll_times", "支援列表滚动次数", AdvancedGroup.POWER, AdvancedType.INT,
            default = "10", desc = "支援列表最多滚动查找多少次。",
        ),

        // ── 模拟宇宙 / 差分宇宙 / 货币战争 ───────────────────────────
        AdvancedField(
            "universe_enable", "启用模拟宇宙", AdvancedGroup.DIVERGENT, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "universe_operation_mode", "运行方式", AdvancedGroup.DIVERGENT, AdvancedType.ENUM,
            default = "exe",
            options = listOf("exe" to "集成", "source" to "源码"),
            desc = "集成模式使用 3rdparty 内的 Auto_Simulated_Universe。",
        ),
        AdvancedField(
            "universe_category", "宇宙类型", AdvancedGroup.DIVERGENT, AdvancedType.ENUM,
            default = "divergent",
            options = listOf("divergent" to "差分宇宙", "universe" to "模拟宇宙"),
        ),
        AdvancedField(
            "universe_enable_gpu", "启用 GPU 加速", AdvancedGroup.DIVERGENT, AdvancedType.BOOL,
            default = "false", desc = "不稳定时请关闭（云游戏模式通常不需要）。",
        ),
        AdvancedField(
            "universe_bonus_enable", "自动领取沉浸奖励", AdvancedGroup.DIVERGENT, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "universe_frequency", "运行频率", AdvancedGroup.DIVERGENT, AdvancedType.ENUM,
            default = "weekly",
            options = listOf("weekly" to "每周", "daily" to "每天"),
        ),
        AdvancedField(
            "universe_count", "每次尝试次数", AdvancedGroup.DIVERGENT, AdvancedType.INT,
            default = "34",
        ),
        AdvancedField(
            "universe_timeout", "超时时间（小时）", AdvancedGroup.DIVERGENT, AdvancedType.INT,
            default = "20",
        ),
        AdvancedField(
            "divergent_type", "差分宇宙模式", AdvancedGroup.DIVERGENT, AdvancedType.ENUM,
            default = "normal",
            options = listOf("normal" to "常规演算", "cycle" to "周期演算"),
        ),
        AdvancedField(
            "divergent_team_type", "差分队伍类型", AdvancedGroup.DIVERGENT, AdvancedType.ENUM,
            default = "终结技",
            options = listOf("追击", "dot", "终结技", "击破", "盾反").map { it to it },
        ),
        AdvancedField(
            "universe_fate", "模拟宇宙命途", AdvancedGroup.DIVERGENT, AdvancedType.ENUM,
            default = "不配置",
            options = listOf(
                "不配置", "存护", "记忆", "虚无", "丰饶", "巡猎",
                "毁灭", "欢愉", "繁育", "智识", "同谐",
            ).map { it to it },
        ),
        AdvancedField(
            "universe_difficulty", "模拟宇宙难度", AdvancedGroup.DIVERGENT, AdvancedType.INT,
            default = "0",
        ),
        AdvancedField(
            "weekly_divergent_enable", "启用差分积分奖励", AdvancedGroup.DIVERGENT, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "weekly_divergent_type", "积分奖励模式", AdvancedGroup.DIVERGENT, AdvancedType.ENUM,
            default = "cycle",
            options = listOf("normal" to "常规演算", "cycle" to "周期演算"),
        ),
        AdvancedField(
            "weekly_divergent_level", "积分奖励难度", AdvancedGroup.DIVERGENT, AdvancedType.INT,
            default = "5", desc = "1–6，6 对应常规演算星阶模式。",
        ),
        AdvancedField(
            "weekly_divergent_bonus_enable", "积分后领饰品", AdvancedGroup.DIVERGENT, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "weekly_divergent_stable_mode", "低性能兼容模式", AdvancedGroup.DIVERGENT, AdvancedType.BOOL,
            default = "false", desc = "提高事件/随意门交互成功率；云游戏强制使用。",
        ),
        AdvancedField(
            "divergent_auto_save_enable", "探索后自动存档", AdvancedGroup.DIVERGENT, AdvancedType.BOOL,
            default = "true", desc = "保存到第一个空存档位；没有空位时跳过。",
        ),
        AdvancedField(
            "divergent_station_priority_enable", "自定义站点优先级", AdvancedGroup.DIVERGENT, AdvancedType.BOOL,
            default = "false", desc = "关闭时使用默认优先级。",
        ),
        AdvancedField(
            "divergent_station_priority", "站点优先级", AdvancedGroup.DIVERGENT, AdvancedType.MAP,
            default = STATION_PRIORITY,
            desc = "每行「站点=优先级」，数字越小越优先。",
            placeholder = "战斗=1",
        ),
        AdvancedField(
            "divergent_station_disabled", "禁用站点", AdvancedGroup.DIVERGENT, AdvancedType.LINES,
            default = "", desc = "每行一个站点名；有重抽次数时会尝试避开。",
            placeholder = "商店\n财富",
        ),
        AdvancedField(
            "currencywars_enable", "启用货币战争", AdvancedGroup.CURRENCY_WARS, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "currencywars_type", "货币战争模式", AdvancedGroup.CURRENCY_WARS, AdvancedType.ENUM,
            default = "overclock",
            options = listOf("normal" to "标准博弈", "overclock" to "超频博弈"),
        ),
        AdvancedField(
            "currencywars_rank_difficulty", "职级难度", AdvancedGroup.CURRENCY_WARS, AdvancedType.ENUM,
            default = "lowest",
            options = listOf(
                "current" to "当前职级",
                "lowest" to "最低职级",
                "highest" to "最高职级",
            ),
        ),
        AdvancedField(
            "currencywars_bonus_enable", "积分后快速提取饰品", AdvancedGroup.CURRENCY_WARS, AdvancedType.BOOL,
            default = "false",
        ),
        AdvancedField(
            "currencywars_fast_mode", "速通模式", AdvancedGroup.CURRENCY_WARS, AdvancedType.BOOL,
            default = "true", desc = "仅在首领节点尝试装备武器。",
        ),
        AdvancedField(
            "currencywars_strategy", "货币战争策略", AdvancedGroup.CURRENCY_WARS, AdvancedType.ENUM,
            default = "default",
            options = listOf("default" to "默认", "aglaea" to "阿格莱雅"),
        ),
        AdvancedField(
            "currencywars_remembrance_trailblazer_name",
            "「开拓者·记忆」显示名", AdvancedGroup.CURRENCY_WARS, AdvancedType.TEXT,
            default = "", desc = "阿格莱雅策略下使用；留空则跳过该角色。",
        ),
        AdvancedField(
            "currencywars_strategy_restart_on_special_tags",
            "特殊词条允许重开", AdvancedGroup.CURRENCY_WARS, AdvancedType.BOOL,
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
        AdvancedField("asset_manager_enable", "启用资产管理", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_self_molding_resin_enable", "每月合成自塑尘脂", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_ember_special_pass_enable", "余烬换星轨专票", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_ember_regular_pass_enable", "余烬换星轨通票", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_ember_tracks_of_destiny_enable", "余烬换命运足迹", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField("asset_lc3_star_superimpose_enable", "三星光锥自动叠加", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField(
            "redemption_code", "兑换码", AdvancedGroup.DAILY, AdvancedType.LINES,
            default = "", desc = "每行一个兑换码；执行时会自动领取。",
            placeholder = "ABCD1234\nEFGH5678",
        ),

        // ── 活动 ────────────────────────────────────────────────────
        AdvancedField("activity_gardenofplenty_enable", "花藏繁生活动", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField(
            "activity_gardenofplenty_instance_type", "花藏繁生副本类型", AdvancedGroup.DAILY, AdvancedType.ENUM,
            default = "拟造花萼（金）", options = INSTANCE_OPTIONS.map { it to it },
        ),
        AdvancedField("activity_realmofthestrange_enable", "异器盈界活动", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField("activity_planarfissure_enable", "位面分裂活动", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField("activity_journey_highlights_notification_enable", "活动热点通知", AdvancedGroup.DAILY, AdvancedType.BOOL, default = "false"),
        AdvancedField(
            "daily_memory_one_team", "回忆一队伍", AdvancedGroup.DAILY, AdvancedType.TEAM,
            default = "DanHeng, -1\nNatasha, 0\nMarch7th, 0\nAsta, 0",
            desc = "启用「回忆一」时使用；每行「角色名, 位置」。",
        ),

        // ── 复合结构（原始 YAML） ─────────────────────────────────────
        AdvancedField(
            "power_plan", "体力计划", AdvancedGroup.POWER, AdvancedType.RAW,
            desc = "按顺序执行的清体力计划，每行一项：[副本类型, 副本名称, 剩余次数]。留空表示使用上面的默认副本。",
            placeholder = "[]",
        ),
        AdvancedField(
            "instance_teams", "副本指定队伍", AdvancedGroup.POWER, AdvancedType.RAW,
            desc = "为特定副本指定队伍编号；每行一个映射：instance_name 与 team_number。",
            placeholder = "- instance_name: \"拟造花萼（金）\"\n  team_number: 1",
        ),
        AdvancedField(
            "borrow_friends", "支援角色好友", AdvancedGroup.POWER, AdvancedType.RAW,
            desc = "借用支援角色的好友列表，每行一项：[角色名, 好友备注]；没有时用 \"None\"。",
            placeholder = "- [\"March7th\", \"赵相机\"]",
        ),
        AdvancedField(
            "daily_tasks", "每日实训任务状态", AdvancedGroup.DAILY, AdvancedType.RAW,
            desc = "上游记录的每日实训任务完成状态（映射）。一般由程序自动维护，手动清空为 [] 可强制重跑。",
            placeholder = "[]",
        ),
        AdvancedField(
            "scheduled_tasks", "定时任务列表", AdvancedGroup.MISC, AdvancedType.RAW,
            desc = "图形界面用的定时任务；每项包含 id/name/time/weekdays/program/args 等字段。云游戏模式下 App 内请优先用「设置 → 定时」。",
            placeholder = "- id: \"uuid\"\n  name: \"每日完整运行\"\n  time: \"04:30\"\n  program: \"self\"\n  args: \"main\"",
        ),
        AdvancedField(
            "notify_template", "通知模板", AdvancedGroup.MISC, AdvancedType.RAW,
            desc = "消息推送的标题与正文模板（映射），支持 {time}/{power}/{error} 等占位符。",
            placeholder = "Title: \"三月七小助手\"\nFullTime: \"开拓力剩余{power}/300\"",
        ),
        AdvancedField(
            "browser_mirror_urls", "浏览器镜像地址", AdvancedGroup.MISC, AdvancedType.RAW,
            desc = "下载 Chrome / chromedriver / EdgeDriver 的镜像根地址（映射）。",
            placeholder = "chrome: \"https://registry.npmmirror.com/-/binary/chrome-for-testing/\"",
        ),
        AdvancedField(
            "pypi_mirror_urls", "PyPI 镜像列表", AdvancedGroup.MISC, AdvancedType.LINES,
            default = "https://mirrors.cloud.tencent.com/pypi/simple/\nhttps://mirrors.aliyun.com/pypi/simple/\nhttps://pypi.org/simple/",
            desc = "依赖同步失败时依次尝试的 PyPI 镜像，每行一个。",
        ),
        AdvancedField(
            "browser_launch_argument", "浏览器额外参数", AdvancedGroup.MISC, AdvancedType.LINES,
            desc = "追加给 Chromium 的启动参数，每行一个，例如 --auto-open-devtools-for-tabs。",
            placeholder = "--auto-open-devtools-for-tabs",
        ),
        AdvancedField(
            "already_used_codes", "已使用兑换码", AdvancedGroup.MISC, AdvancedType.LINES,
            desc = "记录已兑换过的码，避免重复尝试；清空后下次运行会重新尝试。",
        ),
        ) + togglesAsFields() + NOTIFY_FIELDS + READONLY_FIELDS
    }

    private fun togglesAsFields(): List<AdvancedField> = TaskToggles.ALL.map { t ->
        AdvancedField(
            key = t.key,
            title = t.label,
            group = when (t.group) {
                ToggleGroup.POWER -> AdvancedGroup.POWER
                ToggleGroup.REWARD, ToggleGroup.DAILY, ToggleGroup.ACTIVITY -> AdvancedGroup.DAILY
                ToggleGroup.AUTOPLOT, ToggleGroup.RUNTIME -> AdvancedGroup.MISC
            },
            type = AdvancedType.BOOL,
            default = t.defaultValue.toString(),
            desc = t.description,
        )
    }

    private fun nb(key: String, title: String, default: Boolean = false, desc: String = "") =
        AdvancedField(key, title, AdvancedGroup.NOTIFY, AdvancedType.BOOL, default.toString(), desc)

    private fun nt(key: String, title: String, desc: String = "") =
        AdvancedField(key, title, AdvancedGroup.NOTIFY, AdvancedType.TEXT, "", desc)

    private val NOTIFY_FIELDS: List<AdvancedField> = listOf(
        AdvancedField(
            "notify_level", "通知级别", AdvancedGroup.NOTIFY, AdvancedType.ENUM, "all",
            "all 推送所有通知；error 仅推送错误与异常。",
            options = listOf("all" to "全部通知", "error" to "仅错误"),
        ),
        nb("notify_merge", "合并通知", false, "完整运行结束时把所有通知合并成一条。"),
        nb("notify_send_images", "发送图片", true, "推送时附带截图。"),
        nb("notify_serverchanturbo_enable", "Server酱·Turbo"),
        nt("notify_serverchanturbo_sctkey", "Server酱·Turbo SendKey"),
        nb("notify_serverchan3_enable", "Server酱·3"),
        nt("notify_serverchan3_sendkey", "Server酱·3 SendKey"),
        nb("notify_bark_enable", "Bark"),
        nt("notify_bark_key", "Bark Key"),
        nt("notify_bark_group", "Bark 分组"),
        nb("notify_telegram_enable", "Telegram"),
        nt("notify_telegram_token", "Telegram Bot Token"),
        nt("notify_telegram_userid", "Telegram 用户/群组 ID"),
        nb("notify_wechatworkbot_enable", "企业微信机器人"),
        nt("notify_wechatworkbot_key", "企业微信机器人 Key"),
        nb("notify_wechatworkapp_enable", "企业微信应用"),
        nt("notify_wechatworkapp_corpid", "企业微信 CorpID"),
        nt("notify_wechatworkapp_corpsecret", "企业微信 CorpSecret"),
        nt("notify_wechatworkapp_agentid", "企业微信 AgentID"),
        nb("notify_dingtalk_enable", "钉钉机器人"),
        nt("notify_dingtalk_token", "钉钉 Access Token"),
        nt("notify_dingtalk_secret", "钉钉加签密钥（可选）"),
        nb("notify_lark_enable", "飞书机器人"),
        nt("notify_lark_webhook", "飞书 Webhook"),
        nb("notify_discord_enable", "Discord"),
        nt("notify_discord_webhook", "Discord Webhook"),
        nb("notify_pushplus_enable", "PushPlus"),
        nt("notify_pushplus_token", "PushPlus Token"),
        nb("notify_gotify_enable", "Gotify"),
        nt("notify_gotify_url", "Gotify 服务器 URL"),
        nt("notify_gotify_token", "Gotify Token"),
        nb("notify_smtp_enable", "SMTP 邮件"),
        nt("notify_smtp_host", "SMTP 服务器"),
        nt("notify_smtp_user", "SMTP 用户名"),
        nt("notify_smtp_password", "SMTP 密码/授权码"),
        nt("notify_smtp_To", "收件人邮箱"),
    )

    private fun ro(key: String, title: String) =
        AdvancedField(key, title, AdvancedGroup.MISC, AdvancedType.READONLY, "0", "只读，由 M7A 写入。")

    private val READONLY_FIELDS: List<AdvancedField> = listOf(
        ro("last_run_timestamp", "上次完整运行"),
        ro("weekly_relic_cleanup_timestamp", "上次遗器清理"),
        ro("echo_of_war_timestamp", "上次历战余响"),
        ro("currencywars_timestamp", "上次货币战争"),
        ro("weekly_divergent_timestamp", "上次差分积分"),
        ro("universe_timestamp", "上次模拟宇宙"),
        ro("forgottenhall_timestamp", "上次混沌回忆"),
        ro("purefiction_timestamp", "上次虚构叙事"),
        ro("apocalyptic_timestamp", "上次末日幻影"),
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
            AdvancedType.RAW -> rawBlockOf(lines, field.key) ?: field.default.ifBlank { "[]" }
            AdvancedType.READONLY -> inline ?: field.default
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

    /** RAW 字段：取顶层键的原始 YAML 内容；行内值（如 `[]`）直接返回，块内容去掉 2 格基础缩进。 */
    private fun rawBlockOf(lines: List<String>, key: String): String? {
        val idx = lines.indexOfFirst { topKeyOf(it) == key }
        if (idx < 0) return null
        val inline = inlineValue(lines, key)
        if (!inline.isNullOrEmpty()) return inline
        val body = blockOf(lines, key) ?: return ""
        return body.joinToString("\n") { it.removePrefix("  ") }
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
            AdvancedType.READONLY -> ""
            AdvancedType.RAW -> {
                val body = display.trim()
                if (body.isEmpty() || body == "[]") {
                    "${field.key}: []"
                } else {
                    buildString {
                        append(field.key).append(':')
                        for (line in body.split('\n')) {
                            append('\n').append("  ").append(line)
                        }
                    }
                }
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
    POWER("体力", "清体力、副本、支援、遗器清理、历战余响"),
    DAILY("日常", "每日实训、活动、奖励领取、资产与兑换码"),
    CURRENCY_WARS("货币战争", "积分奖励、模式与职级"),
    DIVERGENT("差分宇宙", "模拟宇宙 / 差分、命途、站点优先级"),
    ENDGAME("逐光捡金", "混沌回忆、虚构叙事、末日幻影"),
    NOTIFY("消息推送", "渠道、通知级别、合并与图片"),
    MISC("杂项", "任务行为、通知模板、调度、镜像与调试"),
}

enum class AdvancedType { BOOL, INT, ENUM, TEXT, MAP, TEAM, LEVELS, LINES, RAW, READONLY }

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
