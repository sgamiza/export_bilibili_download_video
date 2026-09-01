# sessions_readme.md

> 最后更新：2026-05-15 v1.8
> 目标：记录 BilibiliExporter APK 的工程结构、功能说明和构建方式，供后续增量修改使用。

## v1.8：进度条 + 屏幕常亮 + 工具栏折叠

### 1. 横向进度条（百分比）
- `activity_main.xml` 加 `progressExport`（`progressBarStyleHorizontal`，max=100）
- 总进度单元 `totalUnits = seriesMap.size + (if merge then videos.size else 0)`
- 复制完一个系列 / 合并完一个视频 → `doneUnits++` → `pushProgress(stageText)` 切到主线程更新
- 完成时强制拉到 100% 再隐藏

### 2. 屏幕常亮 + CPU 不休眠
- Manifest 加 `<uses-permission android:name="android.permission.WAKE_LOCK" />`
- `keepDeviceAwake(on: Boolean)` 同时管理：
  - `Window.addFlags(FLAG_KEEP_SCREEN_ON)`：屏幕不熄
  - `PowerManager.newWakeLock(PARTIAL_WAKE_LOCK).acquire(60*60*1000)`：CPU 不休眠
- doExport 开头 `keepDeviceAwake(true)`，finally / 完成时 `keepDeviceAwake(false)`
- onDestroy 也释放，避免 WakeLock 泄漏

### 3. 工具栏可折叠
- 标题栏改为 horizontal LinearLayout，右侧加 `btnExportCompact`（紧凑导出）+ `btnToggleToolbar`（▲/▼）
- 把 [tvStatus 卡片, 授权按钮区, toolbarSearch, toolbarSort, 操作按钮] 全部包进 `LinearLayout id="collapsibleSection"`
- `toggleToolbar()`：切换 collapsibleSection.visibility 并改图标
- 折叠时副标题改为 `buildCompactSubtitle()`：「已选 X · 共 Y 集 · 点 ▼ 展开工具栏」
- VideoAdapter 加 `onSelectionChanged: (() -> Unit)?` 回调，选中变化时调用，MainActivity 设置回调以实时刷新副标题
- 进度条放在 collapsibleSection 之外，折叠时仍可见

---

## v1.7：修复 B 站新版缓存识别（cid 长度限制问题）

**用户反馈**：「download/116401868112562 下的视频没显示」

**根因**：v1.0-v1.6 都用 `Regex("c_\\d{8,10}")` 识别集数文件夹：
- 老版本 B 站 cid 是 8-10 位 → `c_12345678` 匹配 ✓
- 新版本 B 站 cid 是 13-15 位 → `c_116401868112562` 匹配 ✗
- 部分类型可能用纯数字命名 → 完全不匹配 ✗

**修复**：统一改为「**目录中含 `entry.json` 即视为集数文件夹**」。这是 B 站缓存的核心特征，与命名无关。

**修改文件**：
- `MainActivity.kt`：
  - 新增 `isEpisodeFolderDirect(File): Boolean` / `isEpisodeFolderSAF(DocumentFile): Boolean` 帮手方法
  - `scanDirectRecursive` / `scanSAFRecursive` 用上述方法判定
  - 旧 `EPISODE_FOLDER_PATTERN` 仅保留为 `c_\d+`（任意位数）并标 `@Suppress("unused")`，仅作历史参考
- `UserService.kt`：`listEpisodeFolders` 改为 `walkTopDown().filter { it.isDirectory && File(it, "entry.json").isFile }`

**副作用**：扫描时每个目录会额外 stat 一次 `entry.json`，性能略降但可忽略（B 站缓存通常不超过几百个目录）。

---

## v1.6：列表搜索

- 新增 `toolbarSearch`（`activity_main.xml`）：`EditText etSearch` + `Button btnClearSearch`
- VideoAdapter 重构：分离 `allGroups`（原始）和 `groups`（filter+sort 后的显示数据）
  - `setFilter(keyword: String)`：实时过滤
  - 匹配优先级：系列名（`seriesTitle`/`seriesFolderName`）匹配整组保留；否则按视频名匹配，临时复制 Group 只保留匹配项，并 expanded = true
  - `getDisplayedVideoCount` / `getDisplayedSelectedCount`：用于"全选"按钮判断
  - `getSelectedVideos`：返回**全部** allGroups 中已选（不被过滤影响）
  - `selectAll/deselectAll`：仅作用于当前 displayed groups
- MainActivity：
  - `TextWatcher` 接 `adapter.setFilter`
  - `btnClearSearch` 点击清空输入
  - `refreshListStatusLine()`：tvStatus 显示"搜索结果 X/Y (全部 N/M)"

---

## v1.5：APP 改名 + 双删除选项

- `strings.xml` `app_name` 改为「阿米B站视频导出」
- `activity_main.xml` 顶部标题同步更新
- `dialog_export_options.xml` 新增独立 checkbox `cbDeleteExportedM4s`（默认勾选）
- `ExportOptions` data class 加 `deleteExportedM4s: Boolean` 字段
- `doExport` 中合并成功后，按选项独立执行两种删除：
  - `options.deleteOriginal` → `deleteSourceEpisode(video.source)` 删 B 站源
  - `options.deleteExportedM4s` → `tryDeleteRecursively(episodeDir)` 删导出副本
- Dialog 联动：取消"合并 MP4"自动禁用并取消两个删除选项

---

## v1.4 修复：删除目标搞错了

**用户反馈**："我勾选了删除原来的视频，但是没有删除" —— 反复出现。

**真正的根因**（v1.3 没修对）：
- 用户期望删的是 **B 站源缓存**（`/sdcard/Android/data/tv.danmaku.bili/download/.../c_XXX`），用来**释放手机存储空间**。
- v1.3 实际删的是 **导出目录下的副本**（`/sdcard/Download/BilibiliExport/[title]/c_XXX`），这个副本对用户来说没多大意义。
- 用户看到 B 站缓存仍然占着几个 GB，自然觉得"没删"。

**修复**：
- 新增 `deleteSourceEpisode(source: VideoSource): Pair<Boolean, String>` 方法
- 按 VideoSource 类型走不同删除路径：
  ```kotlin
  is VideoSource.Direct -> tryDeleteRecursively(source.episodeFolder)
  is VideoSource.SAF    -> source.episodeDoc.delete()
  is VideoSource.Shizuku-> userService.deleteRecursively(source.episodePath)
  ```
- 失败时返回详细原因（"APP 无法 unlink"/"Shizuku 返回 false 且 stillExists=true" 等）
- Dialog checkbox 默认改为 **unchecked**，文案红色"删除 B 站缓存源文件夹"
- 勾选后弹二次确认对话框（"B 站 APP 之后将无法再播放这些已下载视频"）
- 错误日志显示前 10 条（之前是前 5 条）

**导出目录的 c_XXX 副本**：现在**保留**——含 entry.json、弹幕等附加文件，用户可能有用。

---

## v1.3 新增功能

### 1. 系列分组折叠 + 排序

**痛点**：扁平列表里几十集 c_XXX 文件夹一字排开，找不到属于哪个系列。

**改造**：
- 新增 `VideoGroup` 数据模型（在 `BiliVideo.kt`）：按 `seriesFolderName` 把扫描结果分组
- 新增 `SortMode` 枚举：`TIME_DESC / TIME_ASC / NAME_ASC / NAME_DESC` 4 种排序
- 重写 `VideoAdapter`：内部维护 `List<VideoGroup>`，根据每组的 `expanded` 状态扁平化成 `List<FlatItem>`
  - `TYPE_GROUP`：系列标题（CheckBox + ▼/▶ 展开图标 + 标题 + 集数/时间信息）
  - `TYPE_VIDEO`：单集（左侧 paddingStart=40dp 表示层级缩进）
- 新增 `item_group_header.xml` 布局
- 主界面新增 `toolbarSort`：Spinner 选择排序方式 + "展开"/"折叠"按钮
- 系列头 CheckBox 实现"整组三态"：全选 / 部分选中 / 未选
- 点击系列头任意位置（除 CheckBox）= 展开/折叠
- 点击 CheckBox = 整组全选/全不选

**新增的 `BiliVideo.lastModified` 字段**：
- Direct/SAF 模式：通过 `File.lastModified()` / `DocumentFile.lastModified()` 获取
- Shizuku 模式：新增 `IUserService.lastModified(path)` AIDL 方法

### 2. 修复"删除原始视频"不生效 Bug

**根因分析**：
- Shizuku 模式下，`UserService.copyDirectory()` 以 shell UID（2000）创建文件
- 虽然代码里 chmod 0644/0755，但 sdcardfs/fuse 在某些 ROM 上的 unlink 检查仍可能拒绝跨 UID 操作
- 老代码 `episodeDir.deleteRecursively()` 失败时被 `try { } catch (_: Exception) {}` 静默吞掉
- 用户勾选了"删除"但看不到任何错误提示，看到的是 c_XXX 仍然在导出目录里

**修复方案（三级 fallback）**：
```kotlin
private fun tryDeleteRecursively(target: File): Boolean {
    // 1. APP 进程本地删除
    if (target.deleteRecursively() && !target.exists()) return true
    // 2. Shizuku 服务以 shell 身份删（解决 UID 不匹配）
    userService?.deleteRecursively(target.absolutePath)
    // 3. walkBottomUp + 逐文件 delete（应对 deleteRecursively 部分失败）
    target.walkBottomUp().forEach { it.delete() }
    return !target.exists()
}
```

并把删除失败的错误信息写入导出结果对话框（不再吞错）。

**新增 AIDL 方法**：
```aidl
boolean deleteRecursively(String path) = 8;
long lastModified(String path) = 9;
```

---

## v1.2 新增功能

### 1. 内置 MP4 合并（替代 Python ffmpeg 脚本）
- 新增 `Mp4Merger.kt`，使用 Android 原生 `MediaMuxer + MediaExtractor`
- 工作流程：
  1. 扫描 m4s 文件前 64 字节查找 `ftyp` box 标识符，自动跳过 B 站混淆头
  2. 通过 `MediaExtractor` 分别解析 audio.m4s 和 video.m4s 的轨道
  3. 通过 `MediaMuxer` 把两个轨道复用到同一 mp4 容器（不重编码，等价于 `ffmpeg -c copy`）
- 性能：约 1 秒/集（取决于视频大小，主要瓶颈是 IO）

### 2. 导出选项对话框
- 新增 `dialog_export_options.xml` 布局，包含 3 个 checkbox：
  - ☑ 合并为 MP4
  - ☑ 删除原始 m4s 文件（联动：取消"合并"则禁用此项）
  - ☑ 用视频标题命名系列文件夹（清理 Windows/Android 非法字符）
- 新增 `MainActivity.ExportOptions` data class

### 3. 导出流程改造
- `doExport(videos, exportDir, options)` 现接收 `ExportOptions`
- 流程：复制原始 → 合并 MP4 → 删除原始 m4s 目录 → 重命名系列文件夹
- 抽出 `copySeriesToDest(source, dest)` 通用复制方法，支持 Direct/SAF/Shizuku 三种源
- 抽出 `findM4sFiles(episodeDir)` 在集数目录中按清晰度优先级查找 m4s 对


---

## 1) 工程与产物

- Android 工程：`BilibiliExporter/`
- 主逻辑：`BilibiliExporter/app/src/main/java/com/biliexport/downloader/MainActivity.kt`
- 数据模型：`BilibiliExporter/app/src/main/java/com/biliexport/downloader/BiliVideo.kt`（含 `VideoSource`、`VideoGroup`、`SortMode`）
- 列表适配器：`BilibiliExporter/app/src/main/java/com/biliexport/downloader/VideoAdapter.kt`（分组折叠 + 排序）
- MP4 合并：`BilibiliExporter/app/src/main/java/com/biliexport/downloader/Mp4Merger.kt`
- Shizuku 服务：`BilibiliExporter/app/src/main/java/com/biliexport/downloader/UserService.kt`
- Shizuku 工具类：`BilibiliExporter/app/src/main/java/com/biliexport/downloader/ShizukuHelper.kt`
- AIDL 接口：`BilibiliExporter/app/src/main/aidl/com/biliexport/downloader/IUserService.aidl`
- 主布局：`BilibiliExporter/app/src/main/res/layout/activity_main.xml`
- 列表项布局：`BilibiliExporter/app/src/main/res/layout/item_video.xml`
- 系列分组头布局：`BilibiliExporter/app/src/main/res/layout/item_group_header.xml`
- 导出选项对话框：`BilibiliExporter/app/src/main/res/layout/dialog_export_options.xml`
- Manifest：`BilibiliExporter/app/src/main/AndroidManifest.xml`
- **APK 输出：`BilibiliExporter/app/build/outputs/apk/debug/app-debug.apk`**（5.6 MB，versionCode=9, versionName=1.8）
- APK 显示名：`阿米B站视频导出`（applicationId 仍为 `com.biliexport.downloader`，不影响升级覆盖安装）

---

## 2) APP 功能说明

### 2.1 目标路径
访问 `Android/data/tv.danmaku.bili/download`（B 站缓存视频目录）

### 2.2 授权方式（四种，按推荐度排序）

**方式 ① Shizuku（★ 完美方案）**
- 关键代码：
  - `ShizukuHelper.kt`：检测状态、申请权限、绑定服务
  - `UserService.kt`：远程服务实现（运行在 shell UID 下）
  - `IUserService.aidl`：AIDL 接口
  - `MainActivity.useShizukuMode()` / `bindShizukuAndLoad()` / `loadVideosShizuku()`
- 流程：
  1. 检查 Shizuku APP 是否安装 + 服务是否运行
  2. 检查/请求 Shizuku 运行时权限（用户在弹窗点"允许"）
  3. `Shizuku.bindUserService()` 启动 `UserService`（运行在独立进程，UID = shell/2000）
  4. APP 通过 `IUserService` binder 接口调用：`listEpisodeFolders`、`readTextFile`、`copyDirectory` 等
  5. 远程服务用普通 `java.io.File` API 直接读 `/sdcard/Android/data/*`（shell UID 没有 Scoped Storage 限制）
- 优点：完美方案，对所有 Android 版本生效，不需要复制中转
- 缺点：用户需安装 Shizuku（github.com/RikkaApps/Shizuku/releases）并通过 ADB 启动一次



**方式 ① SAF 直达 download 目录（强烈推荐）**
- 关键代码：`launchSAFPicker()` in `MainActivity.kt`
- 流程：
  1. 通过 `StorageManager.primaryStorageVolume.createOpenDocumentTreeIntent()` 获取基础 Intent
  2. 取出 `EXTRA_INITIAL_URI`（默认指向根目录的 tree URI）
  3. 字符串替换 `/tree/` → `/document/`，并拼接 `%3AAndroid%2Fdata%2Ftv.danmaku.bili%2Fdownload`
  4. 替换原始的 INITIAL_URI 后启动 picker
- 效果：文件选择器**直接打开** download 目录，用户点底部按钮即可授权
- 兼容性：MIUI、原生 Android 11/12 完全支持；Android 13 部分支持

**方式 ② MANAGE_EXTERNAL_STORAGE**
- 关键代码：`requestManageAccess()` + `openManageAllFilesSettings()` in `MainActivity.kt`
- Intent 启动有 fallback 链：
  1. `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` + package URI（最直接）
  2. `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`（系统级权限列表页）
  3. `ACTION_APPLICATION_DETAILS_SETTINGS`（APP 详情页）
- 兼容性：MIUI 13/14 通常允许；Android 13 原生可能仍无法访问 Android/data

**方式 ③ 扫描任意目录（兜底方案）**
- 关键代码：`showCustomFolderGuideAndRequest()` + `launchSAFPickerGeneric()` in `MainActivity.kt`
- 用户先用 MIUI 文件管理把 download 复制到 `/sdcard/Download/` 等普通位置
- APP 用通用 SAF 选择器（不带 INITIAL_URI）让用户选择已复制的目录
- 优点：完全不受 Android/data 访问限制影响，对所有版本都有效

### 2.3 视频扫描逻辑
- 递归扫描，查找匹配 `c_\d{8,10}` 的文件夹（B 站缓存的单集目录）
- 读取每个集数目录下的 `entry.json` 获取视频标题（`title`）和分P名称（`page_data.part`）
- 识别清晰度子目录（按优先级：120 > 116 > 112 > 80 > 74 > 64 > 32 > 16）

### 2.4 导出功能
- 支持单选/全选
- 点击「导出选中视频到 Downloads」
- 以系列文件夹为单位整体复制（保留完整目录结构，供 Python 脚本处理）
- 导出到 `/sdcard/Downloads/BilibiliExport/`
- 导出后可通过 USB 在电脑端访问，再用 `bilibili_to_mp4.py` 合并 MP4

### 2.5 数据模型
```kotlin
sealed class VideoSource {
    Direct(episodeFolder: File, seriesFolder: File)            // 直接访问
    SAF(episodeDoc: DocumentFile, seriesDoc: DocumentFile)     // SAF
    Shizuku(episodePath: String, seriesPath: String)           // Shizuku（远程服务）
}

data class BiliVideo(
    source: VideoSource,
    title: String,          // entry.json → title
    partName: String,       // entry.json → page_data.part
    quality: String,        // 清晰度目录名
    lastModified: Long,     // v1.3 新增：修改时间（毫秒）
    isSelected: Boolean
)

data class VideoGroup(    // v1.3 新增：系列分组
    seriesFolderName: String,    // 原始文件夹名（如 "45937890"）
    seriesTitle: String,         // 显示标题
    videos: MutableList<BiliVideo>,
    lastModified: Long,          // 组内最新视频时间
    expanded: Boolean
)

enum class SortMode {  // v1.3 新增
    TIME_DESC, TIME_ASC, NAME_ASC, NAME_DESC
}
```

### 2.6 UI 交互（v1.3 新版）
- 列表上方：排序 Spinner、"展开"按钮、"折叠"按钮
- 列表项分两层：
  - 系列头（蓝色背景，CheckBox + ▼/▶ 三角 + 标题 + "N 集 • 时间 • 已选 X • 文件夹名"）
  - 单集（缩进显示，CheckBox + 标题 + "清晰度 • 时间"）
- 系列头交互：
  - 点击 CheckBox → 整组全选/全不选
  - 点击其他位置 → 展开/折叠该组
- 排序：组之间排序 + 组内视频排序都生效

---

## 3) 构建方式

### 环境
| 工具 | 版本 |
|------|------|
| Java | JDK 17 |
| Gradle | 7.5 |
| AGP | 7.4.2 |
| Kotlin | 1.8.10 |
| Android SDK | compileSdk 34, build-tools 34.0.0 |

### 构建命令（PowerShell）
```powershell
# 设置 JAVA_HOME（根据实际安装路径修改）
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

# 国内环境按需设置代理
# $env:HTTP_PROXY = "http://YOUR_PROXY:8080"
# $env:HTTPS_PROXY = "http://YOUR_PROXY:8080"

cd BilibiliExporter
.\gradlew :app:assembleDebug
```

### APK 输出
```
BilibiliExporter/app/build/outputs/apk/debug/app-debug.apk
```

### 注意事项
- 必须使用 Java 17（Java 25 与 Gradle 7.5 不兼容，会报 class file major version 69 错误）
- AGP 7.4.2 不正式支持 compileSdk 34，但构建不会失败（只有 warning）
- 国内环境首次构建需配置 Gradle 代理，见 `gradle.properties` 注释

---

## 4) 权限声明

| 权限 | 用途 | 版本范围 |
|------|------|---------|
| `READ_EXTERNAL_STORAGE` | 读取外部存储 | maxSdkVersion=32 |
| `WRITE_EXTERNAL_STORAGE` | 写入外部存储 | maxSdkVersion=29 |
| `MANAGE_EXTERNAL_STORAGE` | 管理所有文件（访问 Android/data）| Android 11+ |

---

## 5) 下次提需求建议格式

直接描述：
1. 新增哪些功能（按钮/显示内容）
2. 访问逻辑变化
3. 导出行为变化（目标路径/文件格式）

我将基于现有工程增量改动并重新编译可安装 APK。
