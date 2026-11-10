package com.miguanm7a.hsr.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miguanm7a.hsr.deploy.AfterFinish
import com.miguanm7a.hsr.deploy.DeployEngine
import com.miguanm7a.hsr.deploy.DeployScripts
import com.miguanm7a.hsr.deploy.DeploySettings
import com.miguanm7a.hsr.deploy.M7aTask
import com.miguanm7a.hsr.deploy.CodeInstaller
import com.miguanm7a.hsr.deploy.ConfigSync
import com.miguanm7a.hsr.deploy.RootfsInstaller
import com.miguanm7a.hsr.deploy.RootfsSource
import com.miguanm7a.hsr.deploy.SharedPaths
import com.miguanm7a.hsr.deploy.TaskToggles
import com.miguanm7a.hsr.deploy.UpdateManager
import com.miguanm7a.hsr.deploy.UpstreamGapChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.miguanm7a.hsr.deploy.TermuxMirror
import com.miguanm7a.hsr.service.TaskKeepAliveService
import com.miguanm7a.hsr.service.TaskRunner
import com.miguanm7a.hsr.termux.BootstrapInstaller
import com.miguanm7a.hsr.termux.TermuxPaths
import com.miguanm7a.hsr.termux.TermuxShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UpdateOutcome { UP_TO_DATE, AVAILABLE, ERROR }

/** 一次「检查更新」的结果，首页用它弹即时对话框。 */
data class UpdateCheckUi(
    val outcome: UpdateOutcome,
    val tag: String = "",
    val message: String? = null,
)

enum class AppUpdateOutcome { UP_TO_DATE, AVAILABLE, ERROR }

/** 一次「检查 App 更新」的结果。 */
data class AppUpdateCheckUi(
    val outcome: AppUpdateOutcome,
    val version: String = "",
    val notes: String? = null,
    val pageUrl: String? = null,
    val message: String? = null,
)

data class UiState(
    val runtimeReady: Boolean = false,
    val rootfsReady: Boolean = false,
    val deployed: Boolean = false,
    val runningTask: Boolean = false,
    val runningTaskName: String? = null,
    val containerAlias: String = "m7a",
    val termuxMirror: TermuxMirror = TermuxMirror.TUNA,
    val rootfsSource: RootfsSource = RootfsSource.BUNDLED,
    val task: M7aTask = M7aTask.FULL,
    val afterFinish: AfterFinish = AfterFinish.EXIT,
    val logLevel: String = "DEBUG",
    val runDailyTime: String = "04:00",
    val loopMode: String = "scheduled",
    val usePaidTime: Boolean = false,
    val monitorPort: Int = 9222,
    val version: String = "",
    // ---- 源码版本 / 检查更新 ----
    val installedCodeTag: String = "",
    val latestCodeTag: String? = null,
    val checkingUpdate: Boolean = false,
    val updating: Boolean = false,
    val updateError: String? = null,
    val releaseNotes: String? = null,
    val releasePage: String? = null,
    val updateCheck: UpdateCheckUi? = null,
    val gapReport: UpstreamGapChecker.Report? = null,
    // ---- App 自身更新 ----
    val appLatestVersion: String? = null,
    val checkingAppUpdate: Boolean = false,
    val appUpdateCheck: AppUpdateCheckUi? = null,
    // ---- 启动静默检查留下的红点 ----
    val codeUpdateDot: Boolean = false,
    val appUpdateDot: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = DeploySettings(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _qr = MutableStateFlow<QrInfo?>(null)

    /** 当前可用的登录二维码；为 null 表示暂时没有（未到登录步骤或已登录）。 */
    val qr: StateFlow<QrInfo?> = _qr.asStateFlow()

    private var qrWatchJob: Job? = null

    init {        // 观察任务输出与状态。任务进程归 TaskRunner 所有，
        // 所以 ViewModel 重建后重新接上即可，不会丢任务。
        viewModelScope.launch {
            TaskRunner.lines.collect { line ->
                appendLog(line, LogParsing.detectLevel(line))
            }
        }
        viewModelScope.launch {
            TaskRunner.state.collect { snap ->
                val wasRunning = _ui.value.runningTask
                _ui.update {
                    it.copy(runningTask = snap.running, runningTaskName = snap.label)
                }
                if (wasRunning && !snap.running) {
                    // 任务结束
                    stopQrWatch()
                    appendLog(
                        "任务已结束（退出码 ${snap.lastExitCode ?: "?"}）",
                        if (snap.lastExitCode == 0) LogLevel.OK else LogLevel.WARN,
                    )
                }
            }
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val engine = DeployEngine(
        context = app,
        settings = settings,
        onLog = { line -> appendLog(line) },
    )
    val deployState = engine.state

    private var taskProcess: Process? = null

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    fun appendLog(text: String, level: LogLevel = LogLevel.INFO) {
        // 存进去之前就剥掉 ANSI 颜色码，避免界面上一堆 [94m / [0m
        val entry = LogEntry(timeFmt.format(Date()), level, LogParsing.stripAnsi(text))
        _logs.update { current ->
            val next = current + entry
            if (next.size > 3000) next.takeLast(3000) else next
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    fun refresh(context: Context) {
        val ready = TermuxPaths.isInstalled(context)
        _ui.update {
            it.copy(
                runtimeReady = ready,
                rootfsReady = RootfsInstaller.isReady(context),
                deployed = settings.isDeployed(),
                containerAlias = settings.containerAlias(),
                termuxMirror = settings.termuxMirror(),
                rootfsSource = settings.rootfsSource(),
                task = settings.task(),
                afterFinish = settings.afterFinish(),
                logLevel = settings.logLevel(),
                runDailyTime = settings.runDailyTime(),
                loopMode = settings.loopMode(),
                usePaidTime = settings.usePaidTime(),
                monitorPort = settings.monitorPort(),
                version = com.miguanm7a.hsr.BuildConfig.VERSION_NAME,
                installedCodeTag = CodeInstaller.installedTag(context).orEmpty(),
            )
        }
        // 开关读一次进内存，避免界面重组时反复读 DataStore
        _toggles.value = settings.allToggles()

        // 从后台回到前台时：任务可能仍在跑（归 TaskRunner 所有），
        // 同步状态并恢复二维码监听与保活服务。
        val snap = TaskRunner.state.value
        _ui.update { it.copy(runningTask = snap.running, runningTaskName = snap.label) }
        if (snap.running) {
            if (qrWatchJob?.isActive != true) startQrWatch()
            TaskKeepAliveService.start(context.applicationContext, snap.label.orEmpty())
        }

        maybeSilentCheck(context)
    }

    // ------------------------------------------------------------------
    // 一键部署
    // ------------------------------------------------------------------

    fun startDeploy() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            appendLog("=== 开始一键部署 ===", LogLevel.OK)
            val result = engine.runFull()
            refresh(app)
            if (result.isSuccess) {
                appendLog("=== 部署完成 ===", LogLevel.OK)
            } else {
                appendLog(
                    "=== 部署失败：${result.exceptionOrNull()?.message} ===",
                    LogLevel.ERROR,
                )
            }
        }
    }

    /** 只安装运行时（用于快速开始）。 */
    fun installRuntimeOnly() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val r = BootstrapInstaller.install(app) { p ->
                appendLog(p.message)
            }
            refresh(app)
            r.onFailure { appendLog("运行时安装失败：${it.message}", LogLevel.ERROR) }
        }
    }

    // ------------------------------------------------------------------
    // 运行任务
    // ------------------------------------------------------------------

    /** 一键运行选中的任务。 */
    fun runSelectedTask() {
        val app = getApplication<Application>()
        val current = _ui.value
        if (TaskRunner.isRunning()) {
            appendLog("已有任务在运行中，请先停止", LogLevel.WARN)
            return
        }
        val cmd = DeployScripts.runTaskCommand(
            alias = current.containerAlias,
            task = current.task,
            afterFinish = current.afterFinish,
            logLevel = current.logLevel,
            // 把宿主工程目录绑到 /m7a：源码、config.yaml、logs 都在里面
            binds = DeployScripts.bindArgs(SharedPaths.guestProjectDir(app)),
        )
        runRawCommand(cmd, "任务：${current.task.label}")
    }

    fun runRawCommand(cmd: String, label: String) {
        val app = getApplication<Application>()
        // 确保 --bind 的源目录存在，否则 proot 的 bind 会失败
        SharedPaths.ensure(app)

        if (TaskRunner.isRunning()) {
            appendLog("已有任务在运行中，请先停止", LogLevel.WARN)
            return
        }

        // 冒烟测试若发现设备需要 PROOT_NO_SECCOMP=1，任务运行同样沿用
        val prootEnv = DeployScripts.readProotEnvExtra(TermuxPaths.homeDir(app))
        if (prootEnv.isNotEmpty()) {
            appendLog("（沿用 proot 额外环境：$prootEnv）")
        }

        // 任务进程归 TaskRunner（单例）所有，这样 ViewModel/Activity 被回收
        // 也不会杀掉正在跑的任务。
        if (!TaskRunner.start(app, cmd, label, prootEnv)) {
            appendLog("任务启动失败", LogLevel.ERROR)
            return
        }

        // 前台服务 + WakeLock：防止 App 退到后台被系统冻结/回收
        TaskKeepAliveService.start(app, label)
        startQrWatch()
    }

    // ------------------------------------------------------------------
    // 登录二维码监听
    // ------------------------------------------------------------------

    /**
     * 任务运行期间轮询共享目录里的登录二维码。
     *
     * 容器内的 `logs` 已被 `--bind` 到共享目录，所以 March7thAssistant 写出的
     * `logs/qrcode_login.png` 会直接出现在这里。二维码会过期刷新，
     * 所以按 mtime+size 判断变化并重新解码。
     */
    private fun startQrWatch() {
        qrWatchJob?.cancel()
        val app = getApplication<Application>()
        qrWatchJob = viewModelScope.launch(Dispatchers.IO) {
            val f = SharedPaths.qrFile(app)
            appendLog("等待登录二维码（首次运行需要米游社 App 扫码）…", LogLevel.INFO)
            while (isActive) {
                val info = if (f.isFile && f.length() > 0) {
                    QrInfo(f, f.lastModified(), f.length())
                } else {
                    null
                }
                if (info?.revision != _qr.value?.revision) {
                    _qr.value = info
                    if (info != null) {
                        appendLog(
                            "检测到登录二维码 → 请用米游社 App 扫描（也可在「任务」页查看）",
                            LogLevel.OK,
                        )
                    }
                }
                delay(2000)
            }
        }
    }

    private fun stopQrWatch() {
        qrWatchJob?.cancel()
        qrWatchJob = null
    }

    /** 手动清除已显示的二维码（例如已登录成功）。 */
    fun clearQr() {
        _qr.value = null
    }

    /** 手动重新读取二维码文件（任务不在跑、轮询停掉时用）。 */
    fun rescanQr() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val f = SharedPaths.qrFile(app)
            val info = if (f.isFile && f.length() > 0) QrInfo(f, f.lastModified(), f.length()) else null
            _qr.value = info
            withContext(Dispatchers.Main) {
                appendLog(
                    if (info == null) "未找到登录二维码（${f.absolutePath}）"
                    else "已读取登录二维码：${f.name}（${info.size} 字节）",
                    if (info == null) LogLevel.WARN else LogLevel.OK,
                )
            }
        }
    }

    fun stopTask() {
        if (!TaskRunner.isRunning()) {
            appendLog("当前没有正在运行的任务", LogLevel.WARN)
            return
        }
        TaskRunner.stop()
        stopQrWatch()
        _ui.update { it.copy(runningTask = false, runningTaskName = null) }
        appendLog("=== 任务已停止 ===", LogLevel.WARN)
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    fun setTermuxMirror(v: TermuxMirror) {
        settings.setTermuxMirror(v); _ui.update { it.copy(termuxMirror = v) }
    }

    fun setRootfsSource(v: RootfsSource) {
        settings.setRootfsSource(v); _ui.update { it.copy(rootfsSource = v) }
    }

    fun setContainerAlias(v: String) {
        settings.setContainerAlias(v); _ui.update { it.copy(containerAlias = settings.containerAlias()) }
    }

    fun setTask(v: M7aTask) {
        settings.setTask(v); _ui.update { it.copy(task = v) }
    }

    fun setAfterFinish(v: AfterFinish) {
        settings.setAfterFinish(v); _ui.update { it.copy(afterFinish = v) }
    }

    fun setLogLevel(v: String) {
        settings.setLogLevel(v); _ui.update { it.copy(logLevel = v) }
    }

    fun setRunDailyTime(v: String) {
        settings.setRunDailyTime(v); _ui.update { it.copy(runDailyTime = v) }
    }

    fun setUsePaidTime(v: Boolean) {
        settings.setUsePaidTime(v); _ui.update { it.copy(usePaidTime = v) }
    }

    fun setLoopMode(v: String) {
        settings.setLoopMode(v)
        _ui.update { it.copy(loopMode = v) }
    }

    fun setMonitorPort(v: Int) {
        settings.setMonitorPort(v)
        _ui.update { it.copy(monitorPort = settings.monitorPort()) }
    }

    /**
     * 任务开关（config.yaml 里的 `*_enable`）的内存镜像。
     *
     * 不要每次重组都去读 DataStore（30 个开关 × runBlocking 读取会卡顿），
     * 所以启动时读一次、改动时更新这里的值。
     */
    private val _toggles = MutableStateFlow(TaskToggles.defaults())
    val toggles: StateFlow<Map<String, Boolean>> = _toggles.asStateFlow()

    fun setToggle(key: String, value: Boolean) {
        settings.setToggle(key, value)
        _toggles.update { it + (key to value) }
    }

    fun resetTogglesToDefault() {
        TaskToggles.ALL.forEach { settings.setToggle(it.key, it.defaultValue) }
        _toggles.value = TaskToggles.defaults()
        appendLog("任务开关已恢复默认值，点「生成脚本」后生效", LogLevel.OK)
    }

    /** 重新生成 start-m7a.sh 与 config.yaml。 */
    fun regenerateScripts() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val cur = _ui.value
            val home = TermuxPaths.homeDir(app).also { it.mkdirs() }

            // 1) 宿主侧便捷启动脚本
            val script = File(home, "start-m7a.sh")
            script.writeText(
                DeployScripts.startScript(
                    alias = cur.containerAlias,
                    afterFinish = cur.afterFinish,
                    logLevel = cur.logLevel,
                    binds = DeployScripts.bindArgs(SharedPaths.guestProjectDir(app)),
                ),
                Charsets.UTF_8,
            )
            script.setExecutable(true, false)

            // 2) config.yaml 直接写工程目录（该目录以 bind 方式挂在容器 /m7a，
            //    不需要再「推回容器」）
            ConfigSync.refresh(
                context = app,
                settings = settings,
                onLog = { appendLog(it) },
            )

            withContext(Dispatchers.Main) {
                appendLog("已重新生成 start-m7a.sh 与 config.yaml", LogLevel.OK)
                if (!cur.deployed) {
                    appendLog("尚未部署完成，配置会在部署时自动生效", LogLevel.INFO)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 检查更新（只查 March7thAssistant 上游源码版本）
    // ------------------------------------------------------------------

    /** 查到的待安装版本；界面上的「立即更新」用它。 */
    private var pendingRelease: UpdateManager.Release? = null

    fun checkUpdate() {
        val app = getApplication<Application>()
        val cur = _ui.value
        if (cur.checkingUpdate || cur.updating) return
        _ui.update { it.copy(checkingUpdate = true, updateError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val current = CodeInstaller.installedTag(app)
            runCatching { UpdateManager.checkForUpdate(current) }
                .onSuccess { result ->
                    val resultTag = when (result) {
                        is UpdateManager.CheckResult.Update -> result.release.tag
                        is UpdateManager.CheckResult.UpToDate -> result.tag
                    }
                    val gap = runCatching { UpstreamGapChecker.check(app, resultTag) }.getOrNull()
                    when (result) {
                        is UpdateManager.CheckResult.Update -> {
                            pendingRelease = result.release
                            _ui.update {
                                it.copy(
                                    checkingUpdate = false,
                                    latestCodeTag = result.release.tag,
                                    releaseNotes = result.release.notes,
                                    releasePage = result.release.pageUrl,
                                    gapReport = gap,
                                    codeUpdateDot = false,
                                    updateCheck = UpdateCheckUi(
                                        UpdateOutcome.AVAILABLE,
                                        result.release.tag,
                                    ),
                                )
                            }
                            appendLog(
                                "发现新版本 ${result.release.tag}（当前 ${current ?: "未安装"}）",
                                LogLevel.OK,
                            )
                        }

                        is UpdateManager.CheckResult.UpToDate -> {
                            pendingRelease = null
                            _ui.update {
                                it.copy(
                                    checkingUpdate = false,
                                    latestCodeTag = null,
                                    releaseNotes = null,
                                    releasePage = null,
                                    gapReport = gap,
                                    codeUpdateDot = false,
                                    updateCheck = UpdateCheckUi(UpdateOutcome.UP_TO_DATE, result.tag),
                                )
                            }
                            appendLog("已是最新版本（${result.tag}）", LogLevel.OK)
                        }
                    }
                }
                .onFailure {
                    _ui.update { s ->
                        s.copy(
                            checkingUpdate = false,
                            updateError = it.message,
                            updateCheck = UpdateCheckUi(
                                UpdateOutcome.ERROR,
                                message = it.message,
                            ),
                        )
                    }
                    appendLog("检查更新失败：${it.message}", LogLevel.ERROR)
                }
        }
    }

    /** 关闭「检查更新」的结果对话框。 */
    fun dismissUpdateCheck() {
        _ui.update { it.copy(updateCheck = null) }
    }

    // ------------------------------------------------------------------
    // App 自身更新（本仓库 Release）
    // ------------------------------------------------------------------

    private var pendingAppRelease: UpdateManager.AppRelease? = null

    fun checkAppUpdate() {
        val cur = _ui.value
        if (cur.checkingAppUpdate) return
        _ui.update { it.copy(checkingAppUpdate = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { UpdateManager.checkAppUpdate(com.miguanm7a.hsr.BuildConfig.VERSION_NAME) }
                .onSuccess { result ->
                    when (result) {
                        is UpdateManager.AppCheckResult.Update -> {
                            pendingAppRelease = result.release
                            _ui.update { s ->
                                s.copy(
                                    checkingAppUpdate = false,
                                    appLatestVersion = result.release.version,
                                    appUpdateDot = false,
                                    appUpdateCheck = AppUpdateCheckUi(
                                        AppUpdateOutcome.AVAILABLE,
                                        result.release.version,
                                        result.release.notes,
                                        result.release.pageUrl,
                                    ),
                                )
                            }
                        }

                        is UpdateManager.AppCheckResult.UpToDate -> {
                            pendingAppRelease = null
                            _ui.update { s ->
                                s.copy(
                                    checkingAppUpdate = false,
                                    appLatestVersion = null,
                                    appUpdateDot = false,
                                    appUpdateCheck = AppUpdateCheckUi(
                                        AppUpdateOutcome.UP_TO_DATE,
                                        result.version,
                                    ),
                                )
                            }
                        }
                    }
                }
                .onFailure {
                    _ui.update { s ->
                        s.copy(
                            checkingAppUpdate = false,
                            appUpdateCheck = AppUpdateCheckUi(
                                AppUpdateOutcome.ERROR,
                                message = it.message,
                            ),
                        )
                    }
                }
        }
    }

    fun dismissAppUpdateCheck() {
        _ui.update { it.copy(appUpdateCheck = null) }
    }

    /** 启动静默检查：6 小时内只查一次，只更新红点，不弹窗、不写日志。 */
    private fun maybeSilentCheck(context: Context) {
        val prefs = context.getSharedPreferences("m7a_update", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check_ms", 0L)
        if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return
        val cur = _ui.value
        if (cur.checkingUpdate || cur.updating || cur.checkingAppUpdate) return
        prefs.edit().putLong("last_check_ms", System.currentTimeMillis()).apply()

        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { UpdateManager.checkForUpdate(CodeInstaller.installedTag(app)) }
                .onSuccess { r ->
                    when (r) {
                        is UpdateManager.CheckResult.Update -> {
                            pendingRelease = r.release
                            _ui.update {
                                it.copy(
                                    latestCodeTag = r.release.tag,
                                    releaseNotes = r.release.notes,
                                    releasePage = r.release.pageUrl,
                                    codeUpdateDot = true,
                                )
                            }
                        }

                        is UpdateManager.CheckResult.UpToDate -> _ui.update { it.copy(codeUpdateDot = false) }
                    }
                }
            runCatching { UpdateManager.checkAppUpdate(com.miguanm7a.hsr.BuildConfig.VERSION_NAME) }
                .onSuccess { r ->
                    when (r) {
                        is UpdateManager.AppCheckResult.Update -> _ui.update {
                            it.copy(appLatestVersion = r.release.version, appUpdateDot = true)
                        }

                        is UpdateManager.AppCheckResult.UpToDate -> _ui.update {
                            it.copy(appLatestVersion = null, appUpdateDot = false)
                        }
                    }
                }
        }
    }

    /** 下载并安装待更新的源码；依赖清单变化时会自动在容器内同步一次。 */
    fun applyUpdate() {
        val app = getApplication<Application>()
        val release = pendingRelease ?: return
        val cur = _ui.value
        if (cur.updating) return
        if (TaskRunner.isRunning()) {
            appendLog("任务正在运行，请先停止再更新", LogLevel.WARN)
            return
        }
        if (!cur.deployed) {
            appendLog("请先完成一键部署，再检查更新", LogLevel.WARN)
            return
        }
        _ui.update { it.copy(updating = true, updateError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            appendLog("=== 开始更新到 ${release.tag} ===", LogLevel.OK)
            val r = UpdateManager.downloadAndInstall(app, release, cur.containerAlias) { pct, msg ->
                appendLog("[$pct%] $msg")
            }
            withContext(Dispatchers.Main) {
                refresh(app)
            }
            r.onSuccess { depsSynced ->
                pendingRelease = null
                _ui.update {
                    it.copy(
                        updating = false,
                        latestCodeTag = null,
                        releaseNotes = null,
                        updateCheck = null,
                        installedCodeTag = CodeInstaller.installedTag(app).orEmpty(),
                    )
                }
                appendLog(
                    if (depsSynced) "=== 已更新到 ${release.tag}（依赖已同步）==="
                    else "=== 已更新到 ${release.tag} ===",
                    LogLevel.OK,
                )
            }.onFailure {
                _ui.update { s -> s.copy(updating = false, updateError = it.message) }
                appendLog("更新失败：${it.message}", LogLevel.ERROR)
            }
        }
    }

    /** 更新时依赖同步失败后，可单独重试。 */
    fun retryDepsSync() {
        val app = getApplication<Application>()
        if (_ui.value.updating) return
        _ui.update { it.copy(updating = true, updateError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { UpdateManager.syncDeps(app, _ui.value.containerAlias) { line -> appendLog(line) } }
            _ui.update { it.copy(updating = false) }
            r.onSuccess { appendLog("依赖同步完成", LogLevel.OK) }
                .onFailure { appendLog("依赖同步失败：${it.message}", LogLevel.ERROR) }
        }
    }

    /** 检查容器是否存在。 */
    fun checkContainer() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val alias = _ui.value.containerAlias
            val out = TermuxShell.capture(app, "proot-distro list --quiet 2>&1 || true", 20_000)
            withContext(Dispatchers.Main) {
                appendLog("--- proot-distro list --quiet ---")
                out.lines().filter { it.isNotBlank() }.forEach { appendLog(it) }
                val exists = out.lines().any { it.trim() == alias }
                appendLog(
                    if (exists) "容器 $alias 存在" else "容器 $alias 不存在",
                    if (exists) LogLevel.OK else LogLevel.WARN,
                )
            }
        }
    }

    fun resetRuntime() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            appendLog("=== 正在重置运行时 ===", LogLevel.WARN)
            BootstrapInstaller.uninstall(app)
            RootfsInstaller.delete(app)
            settings.setDeployed(false)
            refresh(app)
            appendLog("=== 运行时已重置 ===", LogLevel.WARN)
        }
    }

    override fun onCleared() {
        // 刻意**不**销毁任务进程：任务归 TaskRunner（单例）所有，
        // Activity/ViewModel 被回收不应该中断挂机任务。
        // 真正需要停止请用 stopTask() 或通知栏的「停止任务」。
        super.onCleared()
    }
}
