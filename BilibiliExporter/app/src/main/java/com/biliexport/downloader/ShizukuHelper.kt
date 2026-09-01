package com.biliexport.downloader

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Shizuku 集成助手。
 *
 * Shizuku 是一个开源框架（github.com/RikkaApps/Shizuku），
 * 通过 ADB 或 root 启动一个具有 shell UID（2000）权限的常驻服务，
 * APP 通过 binder IPC 让该服务代理执行原本无权限的操作。
 *
 * 本类负责：
 *  1) 检查 Shizuku 是否安装、是否运行
 *  2) 申请 Shizuku 运行时权限
 *  3) 通过 Shizuku.bindUserService 启动 UserService（运行在 shell UID 下）
 *  4) 获取 IUserService binder 句柄供调用
 */
object ShizukuHelper {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_PERMISSION_CODE = 10086

    enum class Status {
        NOT_INSTALLED,        // 设备上没装 Shizuku APP
        NOT_RUNNING,          // 装了但服务未启动
        NEED_PERMISSION,      // 服务运行但本 APP 未授权
        READY                 // 一切就绪
    }

    fun isShizukuInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun checkStatus(context: Context): Status {
        if (!isShizukuInstalled(context)) return Status.NOT_INSTALLED
        // pingBinder 会返回 false 当服务未启动
        if (!Shizuku.pingBinder()) return Status.NOT_RUNNING
        // 检查权限
        return try {
            if (Shizuku.isPreV11()) {
                // Shizuku v10 及以下不支持运行时权限模型
                Status.READY
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Status.READY
            } else {
                Status.NEED_PERMISSION
            }
        } catch (_: Exception) {
            Status.NOT_RUNNING
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
            }
        } catch (_: Exception) {
        }
    }

    /** 状态描述（用于 UI 显示） */
    fun statusDescription(status: Status): String = when (status) {
        Status.NOT_INSTALLED ->
            "未检测到 Shizuku APP。\n请先安装 Shizuku（github.com/RikkaApps/Shizuku/releases），\n然后用 ADB 启动一次：\nadb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
        Status.NOT_RUNNING ->
            "Shizuku 已安装但未运行。\n请打开 Shizuku APP，按提示用 ADB 启动服务，或在 root 设备上点击「启动」"
        Status.NEED_PERMISSION ->
            "Shizuku 已运行但未授权本 APP。\n点击「申请 Shizuku 权限」后在弹窗中选择「允许」"
        Status.READY ->
            "Shizuku 就绪"
    }

    /**
     * 绑定 UserService。
     * 注意：调用前必须确保 status == READY。
     *
     * @param connection 服务连接回调
     */
    fun bindUserService(context: Context, connection: ServiceConnection) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, UserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku")
            .debuggable(false)
            .version(1)

        Shizuku.bindUserService(args, connection)
    }

    fun unbindUserService(context: Context, connection: ServiceConnection) {
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, UserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shizuku")
                .version(1)
            Shizuku.unbindUserService(args, connection, true)
        } catch (_: Exception) {
        }
    }

    /** 安装 Shizuku 的官方下载地址 */
    const val DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases"
}

/** 把 IBinder 转成 IUserService */
fun IBinder?.toUserService(): IUserService? =
    if (this == null) null else IUserService.Stub.asInterface(this)
