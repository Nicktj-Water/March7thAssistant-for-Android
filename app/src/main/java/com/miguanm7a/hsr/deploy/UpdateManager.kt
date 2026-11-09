package com.miguanm7a.hsr.deploy

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxPaths
import com.miguanm7a.hsr.termux.TermuxShell
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * March7thAssistant 上游源码的检查更新与安装。
 *
 * 只检查**上游代码版本**（moesnow/March7thAssistant 的 GitHub Release），
 * 不检查本 APK 自身的版本。更新流程：
 *
 * ```
 * GET /releases/latest  →  tag
 * 下载 codeload zip（几 MB）→ 解压替换 m7a-project（保留 config/logs/3rdparty）
 * 对比 uv.lock 哈希 → 变了才在容器内跑一次 uv sync
 * ```
 *
 * 之所以只下源码包：容器镜像里已经带了 Debian + Chromium + `/opt/venv`，
 * 依赖不变时换源码是几秒钟的事，不需要重装 500MB 镜像。
 */
object UpdateManager {

    private const val REPO = "moesnow/March7thAssistant"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val UA = "M7A-Android/2.0 (Termux proot)"

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val publishedAt: String,
    )

    sealed class CheckResult {
        /** 有新版本。 */
        data class Update(val release: Release) : CheckResult()

        /** 已是最新。 */
        data class UpToDate(val tag: String) : CheckResult()
    }

    /** 查询 GitHub 最新 release；网络错误会抛出异常，由调用方转成界面提示。 */
    fun checkForUpdate(currentTag: String?): CheckResult {
        val json = httpText(API_LATEST)
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name").ifBlank { error("GitHub 返回缺少 tag_name") }
        val release = Release(
            tag = tag,
            name = obj.optString("name").ifBlank { tag },
            notes = obj.optString("body").take(4000),
            pageUrl = obj.optString("html_url"),
            publishedAt = obj.optString("published_at"),
        )
        return if (currentTag != null && compareVersions(tag, currentTag) <= 0) {
            CheckResult.UpToDate(currentTag)
        } else {
            CheckResult.Update(release)
        }
    }

    /**
     * 下载并安装指定版本源码。
     *
     * @param onProgress 进度（0-100）与说明
     * @return 是否需要（且已经成功执行）依赖同步
     */
    fun downloadAndInstall(
        context: Context,
        release: Release,
        alias: String = "m7a",
        onProgress: (Int, String) -> Unit,
    ): Result<Boolean> = runCatching {
        val project = SharedPaths.projectDir(context)
        if (!project.isDirectory) error("尚未部署：源码目录不存在")

        val oldLock = sha256(File(project, "uv.lock"))
        val updateDir = SharedPaths.updateDir(context).also { it.mkdirs() }
        val zip = File(updateDir, "m7a-${release.tag}.zip")
        val part = File(updateDir, "m7a-${release.tag}.zip.part")
        part.delete()

        onProgress(0, "正在下载 ${release.tag} 源码包…")
        downloadSourceZip(release.tag, part) { pct ->
            onProgress(pct.coerceIn(0, 80) * 80 / 100, "正在下载 ${release.tag} 源码包…")
        }
        if (zip.exists()) zip.delete()
        if (!part.renameTo(zip)) {
            part.copyTo(zip, overwrite = true)
            part.delete()
        }

        onProgress(82, "正在解压并替换源码…")
        val r = CodeInstaller.installFromZip(context, zip, release.tag) { p ->
            onProgress(82 + p.percent.coerceIn(0, 100) * 10 / 100, p.message)
        }
        r.getOrThrow()
        zip.delete()

        val newLock = sha256(File(project, "uv.lock"))
        val depsChanged = oldLock != newLock
        if (depsChanged) {
            onProgress(95, "依赖清单有变化，正在容器内同步 Python 依赖（可能需要几分钟）…")
            syncDeps(context, alias) { line -> onProgress(95, "   $line") }
        }
        onProgress(100, if (depsChanged) "更新完成（已同步依赖）" else "更新完成")
        depsChanged
    }

    /**
     * 在容器内执行 `uv sync --only-group docker`（依赖清单变化后需要）。
     *
     * @throws IllegalStateException 退出码非 0
     */
    fun syncDeps(context: Context, alias: String = "m7a", onLine: (String) -> Unit = {}): Int {
        SharedPaths.ensure(context)
        val script = DeployScripts.syncDepsAfterUpdate(
            alias = alias,
            guestProjectDir = SharedPaths.guestProjectDir(context),
        )
        val code = TermuxShell.run(
            context,
            script,
            onLine = onLine,
            onErrorLine = onLine,
            extraEnv = DeployScripts.readProotEnvExtra(TermuxPaths.homeDir(context)),
        )
        if (code != 0) error("容器内依赖同步失败（退出码 $code）")
        return code
    }

    // ------------------------------------------------------------------
    // 网络
    // ------------------------------------------------------------------

    private fun downloadSourceZip(tag: String, out: File, onProgress: (Int) -> Unit) {
        val urls = listOf(
            "https://codeload.github.com/$REPO/zip/refs/tags/$tag",
            "https://ghfast.top/https://github.com/$REPO/archive/refs/tags/$tag.zip",
        )
        var lastError: Throwable? = null
        for (url in urls) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 180_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        error("HTTP ${conn.responseCode} from ${conn.url}")
                    }
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        FileOutputStream(out).use { output ->
                            val buf = ByteArray(1 shl 16)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) onProgress((done * 100 / total).toInt())
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    if (out.length() < 1_000_000) error("下载不完整：${out.length()} 字节")
                    return
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                lastError = t
                out.delete()
            }
        }
        throw IllegalStateException("源码包下载失败：${lastError?.message}", lastError)
    }

    private fun httpText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("GitHub API HTTP ${conn.responseCode}：${body.take(200)}")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 版本比较：`v2026.11.8` / `2026.11.8` 都按数字段比较。 */
    fun compareVersions(a: String, b: String): Int {
        fun parts(s: String): List<Int> = s.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '+', '_')
            .map { it.takeWhile(Char::isDigit) }
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }

        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
