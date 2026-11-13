package com.operit.qwenapp.tools

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 内置 Termux 环境（v9.20）：
 * 把 assets/termux-bootstrap.zip（官方 bootstrap，基于 bionic 编译，直接跑在 Android 上）
 * 首次启动解压到 App 私有目录（filesDir/termux，ext4 有执行权限），
 * 提供 真实 bash + 完整命令集（ls/grep/sed/tar/curl/ping/dpkg 等 253 个命令）。
 * 无需 root、无需安装 Termux。环境变量按 Termux 约定设置（PREFIX/HOME/PATH/LD_LIBRARY_PATH）。
 */
object TermuxRuntime {

    @Volatile private var ready = false
    @Volatile private var prefix: File? = null
    private const val ASSET = "termux-bootstrap.zip"

    /** 环境是否已就绪 */
    fun isReady(): Boolean = ready

    /** 获取 PREFIX 目录，未就绪先解压；失败返回 null */
    @Synchronized
    fun ensure(ctx: Context): File? {
        prefix?.let { if (ready) return it }
        return try {
            val dir = File(ctx.filesDir, "termux")
            val zip = File(ctx.filesDir, ASSET)

            // 1) 把 assets 里的压缩包复制到私有目录（避免每次都从 assets 流式解压）
            if (!zip.exists() || zip.length() < 10000) {
                ctx.assets.open(ASSET).use { input ->
                    FileOutputStream(zip).use { out -> input.copyTo(out) }
                }
            }

            // 2) 首次解压（bin/bash 存在说明已解压过）
            val bashFile = File(dir, "bin/bash")
            if (!bashFile.exists()) {
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
                unzip(zip, dir)
                rebuildSymlinks(dir)
            }

            // 3) 补 home/tmp 目录，确保 bin 可执行（解压可能丢执行位）
            File(dir, "home").mkdirs()
            File(dir, "tmp").mkdirs()
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "chmod -R +x ${dir.absolutePath}/bin")).waitFor()
            } catch (_: Exception) {}

            prefix = dir
            ready = true
            dir
        } catch (e: Exception) {
            null
        }
    }

    /** 获取 bash 完整路径，未就绪返回 null */
    fun bashPath(ctx: Context): String? {
        val dir = ensure(ctx) ?: return null
        return File(dir, "bin/bash").absolutePath
    }

    /** Termux 约定环境变量（供 ProcessBuilder 注入） */
    fun env(ctx: Context): Map<String, String> {
        val dir = ensure(ctx) ?: return emptyMap()
        val bin = File(dir, "bin").absolutePath
        return mapOf(
            "PREFIX" to dir.absolutePath,
            "HOME" to File(dir, "home").absolutePath,
            "TMPDIR" to File(dir, "tmp").absolutePath,
            "PATH" to "$bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to File(dir, "lib").absolutePath,
            "TERM" to "dumb",
            "LANG" to "C.UTF-8"
        )
    }

    /** 解压 zip 到 dest（bootstrap 内部含 SYMLINKS.txt，核心命令为真实文件，符号链接暂不重建） */
    private fun unzip(zip: File, dest: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            val buf = ByteArray(8192)
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(dest, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        var n = zis.read(buf)
                        while (n >= 0) {
                            if (n > 0) out.write(buf, 0, n)
                            n = zis.read(buf)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** 重建 SYMLINKS.txt 里声明的符号链接（zip 解压不恢复软链）。
     * 格式形如：../../LICENSES/GPL-2.0.txt←./share/doc/net-tools/LICENSE
     * 即 目标←链接路径。逐个创建。 */
    private fun rebuildSymlinks(dir: File) {
        val symFile = File(dir, "SYMLINKS.txt")
        if (!symFile.exists()) return
        try {
            symFile.readLines().forEach { line ->
                val line2 = line.trim()
                if (line2.isEmpty()) return@forEach
                val idx = line2.indexOf('←')
                if (idx <= 0) return@forEach
                val target = line2.substring(0, idx).trim()
                val linkPath = line2.substring(idx + 1).trim()
                // 链接路径以 ./ 开头，相对 dir
                val linkFile = File(dir, linkPath.removePrefix(".").removePrefix("/"))
                try {
                    linkFile.parentFile?.mkdirs()
                    if (linkFile.exists()) linkFile.delete()
                    java.nio.file.Files.createSymbolicLink(linkFile.toPath(), File(dir, target).toPath())
                } catch (_: Exception) {
                    // 个别创建失败不影响整体（核心命令是真实文件）
                }
            }
        } catch (_: Exception) {}
    }
}