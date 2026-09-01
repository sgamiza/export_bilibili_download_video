package com.biliexport.downloader

import android.os.IBinder
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Shizuku 远程服务。
 * 此服务被 Shizuku 框架以 shell UID（2000）启动，
 * 因此拥有比第三方 APP 更高的权限，可以访问 /sdcard/Android/data 下的任意目录。
 *
 * 注意：
 *  - 必须包含一个无参构造函数（Shizuku 框架要求）
 *  - destroy() 由 Shizuku 自动调用，必须存在
 *  - 此进程独立于 APP 进程，所有数据通过 binder IPC 传输
 */
class UserService : IUserService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    /**
     * 扫描 B 站下载目录，返回所有"集数文件夹"的绝对路径。
     *
     * 识别规则：**目录中含有 `entry.json` 即视为集数文件夹**。
     * 这是 B 站缓存的核心特征，能兼容：
     *   - 老版本 c_XXXXXXXX（8-10 位数字）
     *   - 新版本 c_XXXXXXXXXXXXXXX（15+ 位 cid）
     *   - 其他可能的命名方式（纯数字、字母前缀等）
     */
    override fun listEpisodeFolders(basePath: String): MutableList<String> {
        val result = mutableListOf<String>()
        val base = File(basePath)
        if (!base.exists() || !base.isDirectory) return result
        try {
            base.walkTopDown()
                .filter { it.isDirectory && File(it, "entry.json").isFile }
                .forEach { result.add(it.absolutePath) }
        } catch (_: Exception) {
        }
        return result
    }

    override fun readTextFile(filePath: String): String {
        return try {
            File(filePath).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    override fun listDir(dirPath: String): MutableList<String> {
        val result = mutableListOf<String>()
        try {
            File(dirPath).listFiles()?.forEach { f ->
                val prefix = if (f.isDirectory) "[D] " else "[F] "
                result.add("$prefix${f.name}")
            }
        } catch (_: Exception) {
        }
        return result
    }

    override fun exists(path: String): Boolean {
        return try {
            File(path).exists()
        } catch (_: Exception) {
            false
        }
    }

    override fun copyFile(srcPath: String, destPath: String): Boolean {
        return try {
            val src = File(srcPath)
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 512 * 1024)
                }
            }
            // 让 APP 进程能读这个文件（默认 shell 创建的文件权限较严格）
            try {
                Os.chmod(destPath, 420 /* 0644 */)
            } catch (_: Exception) {
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun copyDirectory(srcDir: String, destDir: String): Boolean {
        return try {
            val src = File(srcDir)
            val dest = File(destDir)
            if (!src.exists() || !src.isDirectory) return false
            dest.mkdirs()
            copyDirRecursive(src, dest)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copyDirRecursive(src: File, dest: File) {
        if (src.isDirectory) {
            if (!dest.exists()) dest.mkdirs()
            src.listFiles()?.forEach { child ->
                copyDirRecursive(child, File(dest, child.name))
            }
            try {
                Os.chmod(dest.absolutePath, 493 /* 0755 */)
            } catch (_: Exception) {
            }
        } else {
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 512 * 1024)
                }
            }
            try {
                Os.chmod(dest.absolutePath, 420 /* 0644 */)
            } catch (_: Exception) {
            }
        }
    }

    override fun fileSize(filePath: String): Long {
        return try {
            File(filePath).length()
        } catch (_: Exception) {
            -1L
        }
    }

    override fun deleteRecursively(path: String): Boolean {
        return try {
            File(path).deleteRecursively()
        } catch (_: Exception) {
            false
        }
    }

    override fun lastModified(path: String): Long {
        return try {
            File(path).lastModified()
        } catch (_: Exception) {
            0L
        }
    }

    override fun asBinder(): IBinder = this
}
