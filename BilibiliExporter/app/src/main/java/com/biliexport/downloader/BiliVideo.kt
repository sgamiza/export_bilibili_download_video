package com.biliexport.downloader

import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 视频来源：直接文件访问（Android 10 以下 或 MIUI MANAGE_EXTERNAL_STORAGE）
 *           或 SAF 文档访问（用户通过文件选择器授权）
 *           或 Shizuku 模式（路径以字符串形式存储，所有操作通过远程服务）
 */
sealed class VideoSource {
    data class Direct(val episodeFolder: File, val seriesFolder: File) : VideoSource()
    data class SAF(val episodeDoc: DocumentFile, val seriesDoc: DocumentFile) : VideoSource()
    data class Shizuku(val episodePath: String, val seriesPath: String) : VideoSource()
}

/**
 * B 站下载的单集视频信息
 *
 * B 站下载结构:
 *   download/
 *     [系列文件夹]/
 *       c_XXXXXXXX/        ← episodeFolder（本集数据）
 *         entry.json       ← 含标题、分P名称
 *         80/              ← 清晰度子目录
 *           audio.m4s
 *           video.m4s
 */
data class BiliVideo(
    val source: VideoSource,
    val title: String,
    val partName: String,
    val quality: String,
    val lastModified: Long = 0L,    // 修改时间（毫秒时间戳），用于排序
    var isSelected: Boolean = false
) {
    /** 显示标题：有分P名时显示 "系列 - 分P"，否则只显示标题 */
    val displayTitle: String
        get() = if (title == partName || partName.isBlank()) title else "$title  -  $partName"

    /** 集数文件夹名（例如 c_12345678）*/
    val episodeFolderName: String
        get() = when (val s = source) {
            is VideoSource.Direct -> s.episodeFolder.name
            is VideoSource.SAF -> s.episodeDoc.name ?: "unknown"
            is VideoSource.Shizuku -> s.episodePath.substringAfterLast('/').ifEmpty { "unknown" }
        }

    /** 系列文件夹名 */
    val seriesFolderName: String
        get() = when (val s = source) {
            is VideoSource.Direct -> s.seriesFolder.name
            is VideoSource.SAF -> s.seriesDoc.name ?: "unknown"
            is VideoSource.Shizuku -> s.seriesPath.substringAfterLast('/').ifEmpty { "unknown" }
        }
}

/**
 * 视频系列：B 站下载结构中 c_XXX 的父目录就是一个系列。
 * 一个系列可包含多个 c_XXX 集数文件夹（多分 P / 多集番剧）。
 */
data class VideoGroup(
    val seriesFolderName: String,            // 系列文件夹原始名（如 "45937890"）
    val seriesTitle: String,                 // 显示标题（取自任一视频的 title 字段）
    val videos: MutableList<BiliVideo>,      // 该系列下所有视频
    val lastModified: Long,                  // 系列内最新视频的修改时间
    var expanded: Boolean = true             // 是否展开（UI 状态）
) {
    /** 是否全部视频被选中 */
    val isAllSelected: Boolean
        get() = videos.isNotEmpty() && videos.all { it.isSelected }

    /** 是否部分视频被选中 */
    val isPartiallySelected: Boolean
        get() = videos.any { it.isSelected } && !isAllSelected

    /** 选中的视频数量 */
    val selectedCount: Int
        get() = videos.count { it.isSelected }
}

/** 排序方式 */
enum class SortMode(val label: String) {
    TIME_DESC("时间 新→旧"),
    TIME_ASC("时间 旧→新"),
    NAME_ASC("名称 A→Z"),
    NAME_DESC("名称 Z→A");

    companion object {
        val DEFAULT = TIME_DESC
    }
}
