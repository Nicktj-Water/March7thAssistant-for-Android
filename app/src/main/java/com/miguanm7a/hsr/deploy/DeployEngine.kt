package com.miguanm7a.hsr.deploy

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxPaths
import com.miguanm7a.hsr.termux.TermuxShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/** 部署步骤状态。 */
enum class StepState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class DeployStep(
    val id: String,
    val title: String,
    val detail: String,
    val state: StepState = StepState.PENDING,
    val progress: Int = 0,
    val message: String = "",
)

data class DeployState(
    val running: Boolean = false,
    val steps: List<DeployStep> = emptyList(),
    val deployed: Boolean = false,
    val error: String? = null,
) {
    val overallProgress: Int
        get() = if (steps.isEmpty()) 0 else steps.sumOf { it.progress } / steps.size

    val currentStep: DeployStep?
        get() = steps.firstOrNull { it.state == StepState.RUNNING }
}

/**
 * 一键部署状态机。
 *
 * 链路：内置运行时 → 内置镜像释放 → Termux 准备 → 容器安装 → proot 冒烟
 *      → 源码就位（宿主工程目录 bind 到 `/m7a`）→ 容器自检 → 依赖。
 *
 * 源码与容器镜像分离：镜像只提供 Debian + Chromium + `/opt/venv`，
 * 源码放在宿主 `$HOME/m7a-shared/m7a-project`，以 bind 方式挂进 `/m7a`。
 * 这样升级 March7thAssistant 只需换几 MB 的源码包，不用重装 500MB 镜像。
 */
class DeployEngine(
    private val context: Context,
    private val settings: DeploySettings,
    private val onLog: (String) -> Unit,
) {

    private val _state = MutableStateFlow(DeployState())
    val state: StateFlow<DeployState> = _state.asStateFlow()

    /** 最近若干行输出，失败时附进步骤说明，避免只显示「退出码 1」。 */
    private val recentLines = ArrayDeque<String>(64)

    private fun remember(line: String) {
        val t = line.trim()
        if (t.isEmpty()) return
        if (recentLines.size >= 60) recentLines.removeFirst()
        recentLines.addLast(t)
    }

    private fun tailSummary(max: Int = 3): String =
        recentLines.takeLast(max).joinToString(" ／ ").take(400)

    fun refresh() {
        _state.update { it.copy(deployed = settings.isDeployed()) }
    }

    private fun updateStep(id: String, state: StepState, progress: Int, message: String = "") {
        _state.update { s ->
            s.copy(steps = s.steps.map {
                if (it.id == id) it.copy(state = state, progress = progress, message = message) else it
            })
        }
    }

    /** 执行完整的一键部署。 */
    suspend fun runFull(): Result<Unit> = withContext(Dispatchers.IO) {
        val bundled = settings.rootfsSource() == RootfsSource.BUNDLED
        _state.value = DeployState(running = true, steps = buildStepList(bundled), deployed = false)
        val log = onLog
        val alias = settings.containerAlias()
        val binds = DeployScripts.bindArgs(SharedPaths.guestProjectDir(context))

        fun handleLine(line: String) {
            remember(line)
            when {
                line.startsWith(DeployScripts.MARK) -> {
                    val rest = line.removePrefix(DeployScripts.MARK)
                    val id = rest.substringBefore(':')
                    val msg = rest.substringAfter(':', "")
                    mapStepId(id)?.let { updateStep(it, StepState.RUNNING, 50, msg) }
                    if (msg.isNotBlank()) log(msg)
                }

                line.startsWith(DeployScripts.MARK_DONE) -> {
                    mapStepId(line.removePrefix(DeployScripts.MARK_DONE))
                        ?.let { updateStep(it, StepState.DONE, 100) }
                }

                line.startsWith(DeployScripts.MARK_FAIL) -> {
                    val rest = line.removePrefix(DeployScripts.MARK_FAIL)
                    val id = rest.substringBefore(':')
                    val msg = rest.substringAfter(':', "")
                    mapStepId(id)?.let { updateStep(it, StepState.FAILED, 100, msg) }
                    log("✘ ${msg.ifBlank { id }}")
                }

                else -> log(line)
            }
        }

        try {
            // 共享目录与工程骨架先建好，后面每一步都依赖它
            SharedPaths.ensure(context)

            // ---------- 1. 内置 Termux 运行时 ----------
            if (!TermuxPaths.isInstalled(context)) {
                updateStep("runtime", StepState.RUNNING, 0, "解压内置 Termux 运行时")
                log("==> 安装内置 Termux 运行时（离线）")
                val r = com.miguanm7a.hsr.termux.BootstrapInstaller.install(context) { p ->
                    updateStep("runtime", StepState.RUNNING, p.percent, p.message)
                    log(p.message)
                }
                r.getOrElse {
                    updateStep("runtime", StepState.FAILED, 100, it.message ?: "安装失败")
                    _state.update { s -> s.copy(running = false, error = it.message) }
                    return@withContext Result.failure(it)
                }
                updateStep("runtime", StepState.DONE, 100)
            } else {
                updateStep("runtime", StepState.SKIPPED, 100, "运行时已就绪")
                log("==> Termux 运行时已就绪，跳过")
            }

            // ---------- 2. 释放内置离线镜像（仅内置源） ----------
            if (bundled) {
                updateStep("rootfsCopy", StepState.RUNNING, 0, "释放内置容器镜像")
                log("==> 释放内置离线镜像（约 500MB，首次需要一点时间）")
                val r = RootfsInstaller.install(context) { p ->
                    updateStep("rootfsCopy", StepState.RUNNING, p.percent, p.message)
                }
                r.getOrElse {
                    updateStep("rootfsCopy", StepState.FAILED, 100, it.message ?: "释放失败")
                    _state.update { s -> s.copy(running = false, error = it.message) }
                    return@withContext Result.failure(it)
                }
                updateStep("rootfsCopy", StepState.DONE, 100)
            } else {
                updateStep("rootfsCopy", StepState.SKIPPED, 100, "使用联网镜像")
            }

            // 写入启动脚本（绑定工程目录）
            writeStartScript(bundled, binds)

            // ---------- 3. Termux 侧准备 ----------
            updateStep("termux", StepState.RUNNING, 0)
            log("==> Termux 侧准备")
            var code = TermuxShell.run(
                context,
                DeployScripts.setupTermux(settings.termuxMirror()),
                onLine = ::handleLine,
                onErrorLine = ::handleLine,
            )
            if (code != 0) return@withContext failOn("termux", "Termux 侧准备失败（退出码 $code）")
            updateStep("termux", StepState.DONE, 100)

            // ---------- 4. 安装容器 ----------
            updateStep("rootfs", StepState.RUNNING, 0)
            log("==> 安装容器（来源：${settings.rootfsSource().label}）")
            val installScript =
                if (bundled) DeployScripts.installBundledRootfs(alias)
                else DeployScripts.installRemoteRootfs(settings.rootfsSource(), alias)
            code = TermuxShell.run(context, installScript, onLine = ::handleLine, onErrorLine = ::handleLine)
            if (code != 0) return@withContext failOn("rootfs", "容器安装失败（退出码 $code）")
            updateStep("rootfs", StepState.DONE, 100)

            // ---------- 5. proot 冒烟测试（首次真正启动 proot） ----------
            updateStep("prootrun", StepState.RUNNING, 0)
            log("==> proot 冒烟测试")
            code = TermuxShell.run(
                context,
                DeployScripts.prootSmokeTest(alias),
                onLine = ::handleLine,
                onErrorLine = ::handleLine,
            )
            if (code != 0) {
                return@withContext failOn(
                    "prootrun",
                    "proot 无法启动。常见原因：设备内核限制 ptrace/seccomp，" +
                        "或厂商 ROM 阻止了 proot 的运行。"
                )
            }
            updateStep("prootrun", StepState.DONE, 100)

            // ---------- 6. 源码就位（APK 内置 zip → 宿主工程目录） ----------
            updateStep("code", StepState.RUNNING, 0, "解压内置源码包")
            log("==> 安装 March7thAssistant 源码")
            val codeResult = CodeInstaller.installBundled(context) { p ->
                updateStep("code", StepState.RUNNING, p.percent, p.message)
            }
            codeResult.getOrElse {
                return@withContext failOn("code", "源码安装失败：${it.message}")
            }
            updateStep("code", StepState.DONE, 100)

            // ---------- 7. 刷新配置 + 容器内自检 ----------
            updateStep("deps", StepState.RUNNING, 0)
            refreshSharedConfig()
            log("==> 容器内自检")
            code = execLogin(alias, binds, DeployScripts.setupInsideContainer(bundled, true), ::handleLine)
            if (code != 0) return@withContext failOn("deps", "容器内准备失败（退出码 $code）")
            updateStep("deps", StepState.DONE, 100)

            // ---------- 8. 依赖 ----------
            // 内置镜像自带 /opt/venv（与内置源码的 uv.lock 对应），无需再装；
            // 联网镜像的依赖可能和我们的源码版本对不上，跑一次 uv sync 对齐。
            if (bundled) {
                updateStep("pydeps", StepState.SKIPPED, 100, "内置镜像已含依赖")
            } else {
                updateStep("pydeps", StepState.RUNNING, 0)
                log("==> 同步 Python 依赖")
                code = execLogin(
                    alias,
                    binds,
                    "#!/bin/bash\nexec ${DeployScripts.CONTAINER_UV} sync --only-group docker",
                    ::handleLine,
                )
                if (code != 0) return@withContext failOn("pydeps", "Python 依赖同步失败（退出码 $code）")
                updateStep("pydeps", StepState.DONE, 100)
            }

            settings.setDeployed(true)
            _state.update { it.copy(running = false, deployed = true) }
            log("==> 部署完成 ✔")
            Result.success(Unit)
        } catch (t: Throwable) {
            log("部署异常：${t.message}")
            _state.update { it.copy(running = false, error = t.message) }
            Result.failure(t)
        }
    }

    private fun failOn(stepId: String, message: String): Result<Unit> {
        val tail = tailSummary()
        val full = if (tail.isBlank()) message else "$message\n最近输出：$tail"
        updateStep(stepId, StepState.FAILED, 100, full)
        _state.update { it.copy(running = false, error = full) }
        onLog("✘ $message")
        if (tail.isNotBlank()) onLog("最近输出：$tail")
        return Result.failure(IllegalStateException(full))
    }

    private fun buildStepList(bundled: Boolean): List<DeployStep> = listOf(
        DeployStep("runtime", "安装 Termux 运行时", "解压 APK 内置的 rootfs"),
        DeployStep(
            "rootfsCopy",
            if (bundled) "释放内置容器镜像" else "下载容器镜像",
            if (bundled) "约 500MB，已含 Python/Chromium" else "从网络拉取",
        ),
        DeployStep("termux", "准备 Termux 环境", "配置软件源、校验 proot-distro"),
        DeployStep("rootfs", "安装 proot 容器", "proot-distro install"),
        DeployStep("prootrun", "proot 冒烟测试", "首次真正启动 proot"),
        DeployStep("code", "安装源码", "APK 内置 March7thAssistant 源码包"),
        DeployStep("deps", "容器内自检", "Python / Chromium / 源码挂载"),
        DeployStep("pydeps", "同步 Python 依赖", if (bundled) "内置镜像已包含" else "uv sync"),
    )

    private fun mapStepId(id: String): String? = when (id) {
        "pre", "proot", "dirs" -> "termux"
        "rootfs" -> "rootfs"
        "prootrun" -> "prootrun"
        "code" -> "code"
        "deps", "config", "env" -> "deps"
        "project" -> "pydeps"
        else -> null
    }

    /** 冒烟测试可能发现需要 PROOT_NO_SECCOMP=1，后续容器操作沿用。 */
    private fun prootExtraEnv(): Map<String, String> =
        DeployScripts.readProotEnvExtra(TermuxPaths.homeDir(context))

    /** 在容器内执行一段脚本（先写到 $HOME 再让容器读取）。 */
    private fun execLogin(alias: String, binds: String, script: String, onLine: (String) -> Unit): Int {
        if (script.isBlank()) return 0
        val tmp = File(TermuxPaths.homeDir(context), ".m7a-inner.sh")
        tmp.writeText(script, Charsets.UTF_8)
        // 路径必须规范化成 /data/data/... 形式：宿主写文件用的是 /data/user/0/...，
        // 而 proot-distro 只把 /data/data/<pkg> 绑定进容器。
        val guestScript = TermuxPaths.guestPath(tmp)
        onLog("    容器内执行：$guestScript")
        return TermuxShell.run(
            context,
            "exec proot-distro login $binds$alias -- /bin/bash $guestScript",
            onLine = onLine,
            onErrorLine = onLine,
            extraEnv = prootExtraEnv(),
        )
    }

    private fun writeStartScript(bundled: Boolean, binds: String) {
        val f = File(TermuxPaths.homeDir(context), "start-m7a.sh")
        f.parentFile?.mkdirs()
        f.writeText(
            DeployScripts.startScript(
                alias = settings.containerAlias(),
                afterFinish = settings.afterFinish(),
                logLevel = settings.logLevel(),
                binds = binds,
            ),
            Charsets.UTF_8,
        )
        f.setExecutable(true, false)
    }

    /** 把设置页管理的键增量合并进工程目录里的 config.yaml。 */
    private fun refreshSharedConfig(): File =
        ConfigSync.refresh(context = context, settings = settings, onLog = onLog)
}
