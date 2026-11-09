package com.miguanm7a.hsr.deploy

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxPaths
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * March7thAssistant 源码的安装 / 更新。
 *
 * 源码不再烤进 500MB 的容器镜像，而是放在宿主共享目录
 * `$HOME/m7a-shared/m7a-project`，运行时 bind 到容器 `/m7a`。本类负责：
 *
 * 1. 首次部署时把 APK 内置的 `assets/code/m7a-code.zip` 解压到该目录；
 * 2. 检查更新后，把新下载的源码包**原地替换**进去；
 * 3. 替换时永远保留 `config.yaml` / `logs` / `3rdparty` / `version.json`，
 *    所以更新不会丢配置、日志和浏览器登录态。
 */
object CodeInstaller {

    private const val TAG = "CodeInstaller"

    data class Progress(val percent: Int, val message: String)

    /** 内置源码包的版本号（读 assets/code/version.json 的 tag）。 */
    fun bundledTag(context: Context): String? = runCatching {
        context.assets.open(DeployScripts.CODE_VERSION_ASSET).use { input ->
            JSONObject(input.bufferedReader(Charsets.UTF_8).readText()).optString("tag").ifBlank { null }
        }
    }.getOrNull()

    /** 已安装源码的版本号。 */
    fun installedTag(context: Context): String? = runCatching {
        val f = SharedPaths.versionFile(context)
        if (!f.isFile) return@runCatching null
        JSONObject(f.readText(Charsets.UTF_8)).optString("tag").ifBlank { null }
    }.getOrNull()

    /** 源码是否已就位（main.py 存在 + 版本号可读）。 */
    fun isInstalled(context: Context): Boolean {
        val project = SharedPaths.projectDir(context)
        return File(project, "main.py").isFile && installedTag(context) != null
    }

    /**
     * 安装 APK 内置的源码包。
     * 幂等：版本号一致且 main.py 存在时直接跳过。
     */
    fun installBundled(
        context: Context,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = runCatching {
        SharedPaths.ensure(context)
        val want = bundledTag(context) ?: error("内置源码包缺少 version.json（assets/code/version.json）")
        if (isInstalled(context) && installedTag(context) == want) {
            onProgress(Progress(100, "源码已就位（$want）"))
            return@runCatching
        }

        onProgress(Progress(0, "正在解压内置源码包…"))
        val assetSize = runCatching {
            context.assets.openFd(DeployScripts.CODE_ASSET).use { it.length }
        }.getOrNull() ?: 0L

        context.assets.open(DeployScripts.CODE_ASSET).use { input ->
            applyZip(context, input, assetSize, want, onProgress)
        }
        onProgress(Progress(100, "源码已就位（$want）"))
    }

    /**
     * 用新下载的源码包替换现有源码。
     *
     * @param zip 已经落盘的源码 zip（GitHub release source zipball）
     * @param tag 新版本号，写进 version.json
     */
    fun installFromZip(
        context: Context,
        zip: File,
        tag: String,
        commit: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = runCatching {
        SharedPaths.ensure(context)
        zip.inputStream().buffered(1 shl 16).use { input ->
            applyZip(context, input, zip.length(), tag, onProgress, commit)
        }
        onProgress(Progress(100, "源码已更新到 $tag"))
    }

    /**
     * 解压并替换。整个过程：
     * 1. 先解到 `update/stage`，确认 main.py 存在；
     * 2. 把工程里的旧代码条目移到 `update/backup`；
     * 3. 把新条目移进工程；
     * 4. 写 version.json；失败则回滚。
     */
    private fun applyZip(
        context: Context,
        input: java.io.InputStream,
        totalBytes: Long,
        tag: String,
        onProgress: (Progress) -> Unit,
        commit: String? = null,
    ) {
        val shared = SharedPaths.dir(context)
        val updateRoot = SharedPaths.updateDir(context)
        val stage = File(updateRoot, "stage")
        val backup = File(updateRoot, "backup")
        stage.deleteRecursively()
        backup.deleteRecursively()
        stage.mkdirs()
        backup.mkdirs()

        unzip(input, stage, totalBytes, onProgress)
        val source = findProjectRoot(stage)
            ?: error("源码包结构异常：没有找到 main.py")

        val project = SharedPaths.projectDir(context)
        project.mkdirs()

        val movedOld = mutableListOf<Pair<File, File>>()
        try {
            // 2) 旧代码挪到备份（保留运行数据）
            project.listFiles()?.forEach { f ->
                if (f.name in SharedPaths.PRESERVED_TOP_LEVEL) return@forEach
                val to = File(backup, f.name)
                if (f.renameTo(to)) {
                    movedOld += f to to
                } else {
                    f.copyRecursively(to, overwrite = true)
                    f.deleteRecursively()
                    movedOld += f to to
                }
            }

            // 3) 新代码搬进来
            source.listFiles()?.forEach { f ->
                if (f.name in SharedPaths.PRESERVED_TOP_LEVEL) return@forEach
                val dest = File(project, f.name)
                dest.deleteRecursively()
                if (!f.renameTo(dest)) {
                    f.copyRecursively(dest, overwrite = true)
                }
            }

            // 4) 版本标记
            writeVersion(project, tag, commit)
        } catch (t: Throwable) {
            // 回滚：删掉新搬进来的，把旧代码移回去
            project.listFiles()?.forEach { f ->
                if (f.name !in SharedPaths.PRESERVED_TOP_LEVEL) f.deleteRecursively()
            }
            movedOld.forEach { (original, saved) ->
                runCatching {
                    if (!saved.renameTo(original)) saved.copyRecursively(original, overwrite = true)
                }
            }
            throw t
        } finally {
            stage.deleteRecursively()
            backup.deleteRecursively()
        }
    }

    private fun writeVersion(project: File, tag: String, commit: String?) {
        val json = JSONObject()
            .put("tag", tag)
            .put("commit", commit ?: "")
            .put("installed_at", System.currentTimeMillis() / 1000)
        File(project, "version.json").writeText(json.toString(2), Charsets.UTF_8)
    }

    /** GitHub 的 zipball 解压后通常只有一层 `March7thAssistant-<tag>/`，这里剥掉。 */
    private fun findProjectRoot(dir: File): File? {
        if (File(dir, "main.py").isFile) return dir
        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null
        val withMain = children.firstOrNull { File(it, "main.py").isFile }
        if (withMain != null) return withMain
        return children.singleOrNull()?.let { findProjectRoot(it) }
    }

    private fun unzip(
        input: java.io.InputStream,
        dest: File,
        totalBytes: Long,
        onProgress: (Progress) -> Unit,
    ) {
        ZipInputStream(BufferedInputStream(input, 1 shl 16), Charsets.UTF_8).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var done = 0L
            var files = 0
            val buffer = ByteArray(1 shl 16)
            while (entry != null) {
                val name = entry.name
                // 防目录穿越（GitHub 的包不会有，但 zip 是不可信输入）
                if (name.contains("..") || name.startsWith("/")) {
                    throw IllegalStateException("源码包包含非法路径：$name")
                }
                val target = File(dest, name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val n = zis.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (totalBytes > 0) {
                                val pct = (done * 90 / totalBytes).toInt().coerceIn(0, 90)
                                if (files % 200 == 0) onProgress(Progress(pct, "正在解压源码…"))
                            }
                        }
                    }
                    files++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            android.util.Log.i(TAG, "解压完成：$files 个文件，$done 字节")
        }
    }
}
