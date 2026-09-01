package com.biliexport.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 把 B 站缓存的 audio.m4s + video.m4s 合并成标准 mp4 文件。
 *
 * 对应 bilibili_to_mp4.py 中的 `ffmpeg -i audio -i video -c copy output.mp4` 命令。
 *
 * 实现原理：
 *  1. 跳过 B 站给 m4s 文件加的混淆头（前若干字节 ASCII '0'），定位到真正的 mp4 数据起始处
 *     （通过查找 "ftyp" box 标识符）
 *  2. 用 Android 原生 MediaExtractor 解析两个 m4s 文件的音视频轨
 *  3. 用 MediaMuxer 把两个轨道复用进同一个 mp4 容器
 *  4. 整个过程不重新编码（=copy），速度极快，约 1 秒/集
 */
object Mp4Merger {

    private const val TAG = "Mp4Merger"
    private const val BUFFER_SIZE = 1024 * 1024  // 1 MB

    /**
     * 合并 audio.m4s 和 video.m4s 为 mp4。
     *
     * @return 成功返回 null，失败返回错误描述
     */
    fun mergeAudioVideo(
        audioFile: File,
        videoFile: File,
        outputFile: File,
        onProgress: ((String) -> Unit)? = null
    ): String? {
        if (!audioFile.exists()) return "音频文件不存在: ${audioFile.absolutePath}"
        if (!videoFile.exists()) return "视频文件不存在: ${videoFile.absolutePath}"

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val tempDir = File(outputFile.parentFile, ".bili_tmp")
        tempDir.mkdirs()
        val ts = System.currentTimeMillis()
        val cleanedAudio = File(tempDir, "a_$ts.mp4")
        val cleanedVideo = File(tempDir, "v_$ts.mp4")

        return try {
            onProgress?.invoke("预处理音频流...")
            stripBilibiliHeader(audioFile, cleanedAudio)
            onProgress?.invoke("预处理视频流...")
            stripBilibiliHeader(videoFile, cleanedVideo)

            onProgress?.invoke("合并音视频...")
            doMerge(cleanedAudio, cleanedVideo, outputFile)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed for ${outputFile.name}", e)
            if (outputFile.exists()) outputFile.delete()
            "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try { cleanedAudio.delete() } catch (_: Exception) {}
            try { cleanedVideo.delete() } catch (_: Exception) {}
            try { tempDir.delete() } catch (_: Exception) {}
        }
    }

    /**
     * 去除 B 站 m4s 文件的混淆头。
     * B 站 2020+ 版本会在 m4s 开头加几字节 ASCII '0' (0x30) 防止直接播放。
     * 通过扫描前 64 字节找 "ftyp" box 标识符（任何标准 mp4 都以 ftyp 开头）来兼容所有版本。
     */
    private fun stripBilibiliHeader(srcFile: File, destFile: File) {
        FileInputStream(srcFile).use { input ->
            val head = ByteArray(64)
            val readLen = input.read(head).coerceAtLeast(0)

            // 在 head 中找 "ftyp" (0x66 0x74 0x79 0x70)
            // mp4 box 格式: [4 bytes size][4 bytes type="ftyp"][payload...]
            // 所以 ftyp 出现在偏移 +4 处
            var offset = 0
            for (i in 0..(readLen - 8)) {
                if (head[i + 4] == 0x66.toByte() &&
                    head[i + 5] == 0x74.toByte() &&
                    head[i + 6] == 0x79.toByte() &&
                    head[i + 7] == 0x70.toByte()
                ) {
                    offset = i
                    break
                }
            }

            FileOutputStream(destFile).use { output ->
                if (offset < readLen) {
                    output.write(head, offset, readLen - offset)
                }
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                }
            }
        }
    }

    private fun doMerge(audioFile: File, videoFile: File, outputFile: File) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxerStarted = false

        try {
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrack = selectTrack(videoExtractor, "video/")
                ?: throw RuntimeException("未在视频文件中找到视频轨")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            videoExtractor.selectTrack(videoTrack)
            val videoTrackIndex = muxer.addTrack(videoFormat)

            audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(audioFile.absolutePath)
            val audioTrack = selectTrack(audioExtractor, "audio/")
                ?: throw RuntimeException("未在音频文件中找到音频轨")
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            audioExtractor.selectTrack(audioTrack)
            val audioTrackIndex = muxer.addTrack(audioFormat)

            muxer.start()
            muxerStarted = true

            writeTrack(videoExtractor, muxer, videoTrackIndex)
            writeTrack(audioExtractor, muxer, audioTrackIndex)
        } finally {
            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) { Log.w(TAG, "muxer.stop failed", e) }
            }
            try { muxer.release() } catch (_: Exception) {}
            videoExtractor?.release()
            audioExtractor?.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun writeTrack(extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int) {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            bufferInfo.offset = 0
            bufferInfo.size = size
            bufferInfo.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    /** 清理 Android/Windows 都不允许的文件名字符（参考 bilibili_to_mp4.py 的 format_windows_filename） */
    fun sanitizeFilename(name: String): String {
        val sanitized = name.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F\\x7F]"), "").trim()
        return sanitized.ifEmpty { "unnamed_${System.currentTimeMillis()}" }
    }
}
