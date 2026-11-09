package com.miguanm7a.hsr.deploy

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxPaths
import java.io.File

/**
 * 宿主与 proot 容器之间共享的目录。
 *
 * ## 布局（v2：代码外置）
 *
 * ```
 * $HOME/m7a-shared/                 宿主侧共享根目录
 * ├── m7a-project/                  ← 整个目录 bind 到容器的 /m7a
 * │   ├── main.py                   March7thAssistant 源码（可替换）
 * │   ├── version.json              当前安装的源码版本
 * │   ├── config.yaml               运行配置（跨更新保留）
 * │   ├── logs/                     日志 + 登录二维码（跨更新保留）
 * │   └── 3rdparty/                 浏览器登录态（跨更新保留）
 * ├── m7a-rootfs.tar.gz             内置容器镜像（解压后即删/可复用）
 * ├── proot-env                     proot 额外环境变量（PROOT_NO_SECCOMP=1）
 * └── update/                       更新下载的临时文件
 * ```
 *
 * 关键点：
 * - proot-distro 默认把 `$HOME` 以**同一路径**绑定进容器，所以这个目录在
 *   容器里按绝对路径也能访问；我们再把 `m7a-project` 显式 bind 到 `/m7a`，
 *   这样 `main.py` 里 `chdir(自身目录)` 之后读 `./assets/...` 依然成立。
 * - 源码更新只替换「代码文件」，`config.yaml` / `logs` / `3rdparty` 永远保留。
 *   这就是「代码外置 + 镜像烤死」方案的核心：更新只需要下一个几 MB 的源码包。
 */
object SharedPaths {

    /** 共享根目录（宿主 File）。 */
    fun dir(context: Context): File =
        File(TermuxPaths.homeDir(context), DeployScripts.SHARED_DIR_NAME)

    /** 容器内可见的共享根目录字符串。 */
    fun guestDir(context: Context): String = TermuxPaths.guestPath(dir(context))

    /**
     * 源码工程目录（宿主 File）。
     *
     * 这个目录会整体 bind 到容器的 `/m7a`，所以它**就是**容器里的项目根目录：
     * 源码、config.yaml、logs、3rdparty 都在这一层，路径与官方 Docker 镜像一致。
     */
    fun projectDir(context: Context): File = File(dir(context), DeployScripts.PROJECT_DIR_NAME)

    /** 容器内可见的工程目录字符串（作为 `--bind` 的源）。 */
    fun guestProjectDir(context: Context): String = TermuxPaths.guestPath(projectDir(context))

    /** 工程内的 config.yaml（宿主 File）。 */
    fun configFile(context: Context): File =
        File(projectDir(context), DeployScripts.SHARED_CONFIG_NAME)

    /** 工程内的 logs 目录（宿主 File）。 */
    fun logsDir(context: Context): File = File(projectDir(context), "logs")

    /** 工程内的 3rdparty 目录（宿主 File）。 */
    fun thirdpartyDir(context: Context): File = File(projectDir(context), "3rdparty")

    /** 浏览器登录态目录（跨更新保留，否则每次更新都要重新扫码）。 */
    fun userProfileDir(context: Context): File =
        File(File(thirdpartyDir(context), "WebBrowser"), "UserProfile")

    /** 登录二维码文件（宿主 File）。 */
    fun qrFile(context: Context): File = File(logsDir(context), QR_FILENAME)

    /** 更新下载 / 解压的临时目录。 */
    fun updateDir(context: Context): File = File(dir(context), "update")

    /** 源码版本文件。 */
    fun versionFile(context: Context): File = File(projectDir(context), "version.json")

    const val QR_FILENAME = "qrcode_login.png"

    /** 更新时需要保留的顶层条目（源码包不会包含它们，双保险）。 */
    val PRESERVED_TOP_LEVEL = setOf("config.yaml", "logs", "3rdparty", "version.json")

    /** 确保运行所需目录都存在（`--bind` 的源必须已存在）。 */
    fun ensure(context: Context): File {
        val project = projectDir(context)
        project.mkdirs()
        logsDir(context).mkdirs()
        userProfileDir(context).mkdirs()
        configFile(context).parentFile?.mkdirs()
        return project
    }
}
