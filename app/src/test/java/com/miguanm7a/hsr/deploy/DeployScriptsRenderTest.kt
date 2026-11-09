package com.miguanm7a.hsr.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 把生成的 shell 脚本原样打印出来，用来肉眼核对语法。
 *
 * DeployScripts 是纯 Kotlin（不依赖 Android），所以可以直接在 JVM 单测里跑。
 * 这类脚本一旦有转义错误，真机上的表现是「退出码 1」且看不出原因，
 * 所以值得把生成结果固定下来核对。
 */
class DeployScriptsRenderTest {

    private val alias = "m7a"
    private val projectDir = "/data/data/com.m7ahsr/files/home/m7a-shared/m7a-project"
    private val binds = DeployScripts.bindArgs(projectDir)

    @Test
    fun `print all generated scripts`() {
        val sb = StringBuilder()
        fun sec(title: String, body: String) {
            sb.appendLine("========== $title ==========")
            sb.appendLine(body)
            sb.appendLine()
        }
        sec("setupTermux", DeployScripts.setupTermux(TermuxMirror.TUNA))
        sec("installBundledRootfs", DeployScripts.installBundledRootfs(alias))
        sec("setupInsideContainer", DeployScripts.setupInsideContainer(true))
        sec(
            "runTaskCommand",
            DeployScripts.runTaskCommand(alias, M7aTask.DAILY, AfterFinish.EXIT, "DEBUG", binds)
        )
        sec("startScript", DeployScripts.startScript(alias, AfterFinish.EXIT, "DEBUG", binds))
        sec("configYamlTemplate", DeployScripts.configYamlTemplate())
        sec(
            "configScalars",
            DeployScripts.configScalars(
                AfterFinish.EXIT, "04:00", "scheduled", "DEBUG", false,
                TaskToggles.defaults(),
            ).entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )

        // 写到固定路径，方便人工核对（测试输出在报告里会被截断）
        val out = File(System.getProperty("java.io.tmpdir"), "m7a-rendered-scripts.txt")
        out.writeText(sb.toString(), Charsets.UTF_8)
        println("rendered scripts written to: ${out.absolutePath}")
        println(sb.toString())
    }

    /**
     * 脚本里不能出现未展开的 Kotlin 模板残留。
     * `$` 后面跟字母在 Kotlin 里会被当插值，编译期就报错；
     * 但 `${'$'}` 忘写会变成字面量 `${D}` 之类，需要显式挡住。
     */
    @Test
    fun `no kotlin template leftovers in generated scripts`() {
        val scripts = listOf(
            "setupTermux" to DeployScripts.setupTermux(TermuxMirror.TUNA),
            "installBundledRootfs" to DeployScripts.installBundledRootfs(alias),
            "setupInsideContainer" to DeployScripts.setupInsideContainer(true),
            "runTaskCommand" to DeployScripts.runTaskCommand(
                alias, M7aTask.DAILY, AfterFinish.EXIT, "DEBUG", binds
            ),
            "startScript" to DeployScripts.startScript(alias, AfterFinish.EXIT, "DEBUG", binds),
            "syncDepsAfterUpdate" to DeployScripts.syncDepsAfterUpdate(alias, projectDir),
        )
        for ((name, text) in scripts) {
            assertFalse("$name 残留 \${D} 之类模板", text.contains("\${D}"))
            assertFalse("$name 残留 \${'$'}", text.contains("\${'$'}"))
            assertFalse("$name 残留 CONTAINER_VENV 占位", text.contains("CONTAINER_VENV"))
            assertTrue("$name 不应为空", text.isNotBlank())
        }
    }

    /** 内置镜像下，自检脚本必须用 /opt/venv 与挂载进来的 /m7a。 */
    @Test
    fun `bundled selfcheck references the image layout`() {
        val s = DeployScripts.setupInsideContainer(true)
        assertTrue(s.contains("/opt/venv"))
        assertTrue(s.contains("/m7a/main.py"))
        assertTrue(s.contains("version.json"))
        // 内置模式下不应该去 apt 装东西
        assertFalse("内置模式不应触发 apt-get install", s.contains("apt-get install"))
        // 应该有环境变量块
        assertTrue(s.contains("MARCH7TH_CLOUD_GAME_ENABLE=true"))
    }

    /**
     * 代码外置后，config.yaml 就在 bind 进来的工程目录里，
     * 自检脚本不应再出现「把配置 cp 到 /m7a」的旧逻辑。
     */
    @Test
    fun `no config copy step anymore`() {
        val s = DeployScripts.setupInsideContainer(true)
        assertFalse(s.contains("step config"))
        assertFalse(s.contains("m7a-shared/config.yaml"))
    }

    /** 任务命令必须把宿主工程目录整体 bind 到 /m7a。 */
    @Test
    fun `task command binds project dir to m7a`() {
        val c = DeployScripts.runTaskCommand(alias, M7aTask.DAILY, AfterFinish.EXIT, "DEBUG", binds)
        assertTrue(
            "应把工程目录 bind 到 /m7a",
            c.contains("--bind=$projectDir:${DeployScripts.CONTAINER_APP_DIR} "),
        )
        // argparse 要求选项在位置参数（容器名）之前
        val bindIdx = c.indexOf("--bind=")
        val aliasIdx = c.indexOf("login $alias")
        assertTrue("--bind 必须出现在容器名之前（bind=$bindIdx, alias=$aliasIdx）",
            bindIdx in 1 until aliasIdx)
        // 不再单独 bind logs
        assertFalse(c.contains(":${DeployScripts.CONTAINER_APP_DIR}/logs"))
    }

    @Test
    fun `task command without binds has no bind flag`() {
        val c = DeployScripts.runTaskCommand(alias, M7aTask.DAILY, AfterFinish.EXIT, "DEBUG", "")
        assertFalse("没有 binds 时不应出现 --bind", c.contains("--bind"))
    }

    @Test
    fun `start script also binds project dir`() {
        val s = DeployScripts.startScript(alias, AfterFinish.EXIT, "DEBUG", binds)
        assertTrue(s.contains("--bind=$projectDir:${DeployScripts.CONTAINER_APP_DIR} "))
        val bindIdx = s.indexOf("--bind=")
        val loginIdx = s.indexOf("proot-distro login")
        assertTrue("--bind 应在登录命令之后、容器名之前", bindIdx > loginIdx)
    }

    /** 共享目录常量与 QR 文件名要和上游 March7thAssistant 一致。 */
    @Test
    fun `qr filename matches upstream`() {
        // module/game/cloud.py: os.path.join("logs", "qrcode_login.png")
        assertEquals("qrcode_login.png", SharedPaths.QR_FILENAME)
    }

    /**
     * 容器已存在且可用时必须复用，不能无条件 remove。
     * 无条件重装会抹掉浏览器登录态，用户每次部署都要重新扫码。
     */
    @Test
    fun `existing healthy container is reused not wiped`() {
        val s = DeployScripts.installBundledRootfs(alias)
        assertTrue("应检查容器是否已存在", s.contains("proot-distro list --quiet"))
        assertTrue("应校验容器运行时（venv）", s.contains("test -x /opt/venv/bin/python"))
        assertTrue("应校验容器运行时（chromium）", s.contains("command -v chromium"))
        assertTrue("可用时应跳过重装", s.contains("跳过重装"))
        // remove 只能出现在「容器不可用」分支之后
        val removeIdx = s.indexOf("proot-distro remove")
        val checkIdx = s.indexOf("test -x /opt/venv/bin/python")
        assertTrue("remove 必须在可用性校验之后", removeIdx > checkIdx && checkIdx >= 0)
        // 容器里不再有 /m7a 源码，不能拿它做健康检查
        assertFalse(s.contains("test -f /m7a/main.py"))
    }

    /** 运行命令要点：/opt/venv/bin/python + /m7a，且不依赖 uv。 */
    @Test
    fun `bundled run command uses venv python in m7a`() {
        val c = DeployScripts.runTaskCommand(alias, M7aTask.DAILY, AfterFinish.EXIT, "DEBUG", binds)
        assertTrue(c.contains("/opt/venv/bin/python main.py"))
        assertTrue(c.contains("cd /m7a"))
        assertTrue(c.contains("daily"))
        assertFalse("内置镜像不需要 uv", c.contains("uv run"))
        assertFalse("不能残留 --termux-home（proot-distro 5.x 没有该选项）",
            c.contains("--termux-home"))
    }
}
