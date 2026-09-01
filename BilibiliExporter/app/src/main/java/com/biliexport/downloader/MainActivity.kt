package com.biliexport.downloader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREF_NAME = "BiliExporter"
        private const val PREF_TREE_URI = "treeUri"
        private const val BILIBILI_PACKAGE = "tv.danmaku.bili"
        private const val BILIBILI_RELATIVE_PATH = "Android/data/tv.danmaku.bili/download"
        private const val EXPORT_DIR_NAME = "BilibiliExport"
        // 历史规则：c_ + 8-10 位数字。仅用于兼容性参考，新代码用 entry.json 存在作判定。
        @Suppress("unused")
        private val EPISODE_FOLDER_PATTERN = Regex("c_\\d+")
        private val QUALITY_PRIORITY = listOf("120", "116", "112", "80", "74", "64", "32", "16")
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnAccessShizuku: Button
    private lateinit var btnAccessManage: Button
    private lateinit var btnAccessSAF: Button
    private lateinit var btnAccessCustom: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnExportSelected: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var toolbarSort: LinearLayout
    private lateinit var spinnerSort: Spinner
    private lateinit var btnExpandAll: Button
    private lateinit var btnCollapseAll: Button
    private lateinit var toolbarSearch: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: Button
    private lateinit var collapsibleSection: LinearLayout
    private lateinit var btnToggleToolbar: Button
    private lateinit var btnExportCompact: Button
    private lateinit var progressExport: ProgressBar
    private lateinit var tvHeaderSubtitle: TextView

    private var toolbarCollapsed: Boolean = false

    private val groupList = mutableListOf<VideoGroup>()
    private var currentSortMode = SortMode.DEFAULT
    private lateinit var adapter: VideoAdapter

    /** 导出过程中持有的 WakeLock，保证 CPU 不休眠 + 屏幕常亮 */
    private var exportWakeLock: PowerManager.WakeLock? = null

    // Shizuku 相关
    private var userService: IUserService? = null
    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = binder.toUserService()
            if (userService != null) {
                loadVideosShizuku()
            } else {
                Toast.makeText(this@MainActivity, "Shizuku 服务绑定失败", Toast.LENGTH_SHORT).show()
                setLoadingState(false)
                showInitialState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { reqCode, grantResult ->
        if (reqCode == ShizukuHelper.REQUEST_PERMISSION_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindShizukuAndLoad()
            } else {
                Toast.makeText(this, "用户拒绝了 Shizuku 权限", Toast.LENGTH_SHORT).show()
                showInitialState()
            }
        }
    }

    // SAF 文件夹选择器
    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: run {
                Toast.makeText(this, "未获取到文件夹 URI，请重试", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 部分 URI 不支持写权限，忽略
            }
            saveSAFUri(uri)
            loadVideosSAF(uri)
        } else {
            showInitialState()
            Toast.makeText(this, "未选择文件夹，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    // Android 10 以下的存储权限申请
    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadVideosDirect()
        else {
            tvStatus.text = "未授权存储权限，请前往系统设置手动开启"
            showInitialState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        setupRecyclerView()
        setupButtons()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        tryRestoreAccess()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        try {
            ShizukuHelper.unbindUserService(this, userServiceConnection)
        } catch (_: Exception) {
        }
        try {
            if (exportWakeLock?.isHeld == true) exportWakeLock?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 从系统权限设置页面返回后，检查是否已授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && Environment.isExternalStorageManager()
            && groupList.isEmpty()
        ) {
            loadVideosDirect()
        }
    }

    // ===== 初始化 =====

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnAccessShizuku = findViewById(R.id.btnAccessShizuku)
        btnAccessManage = findViewById(R.id.btnAccessManage)
        btnAccessSAF = findViewById(R.id.btnAccessSAF)
        btnAccessCustom = findViewById(R.id.btnAccessCustom)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnExportSelected = findViewById(R.id.btnExportSelected)
        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        toolbarSort = findViewById(R.id.toolbarSort)
        spinnerSort = findViewById(R.id.spinnerSort)
        btnExpandAll = findViewById(R.id.btnExpandAll)
        btnCollapseAll = findViewById(R.id.btnCollapseAll)
        toolbarSearch = findViewById(R.id.toolbarSearch)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        collapsibleSection = findViewById(R.id.collapsibleSection)
        btnToggleToolbar = findViewById(R.id.btnToggleToolbar)
        btnExportCompact = findViewById(R.id.btnExportCompact)
        progressExport = findViewById(R.id.progressExport)
        tvHeaderSubtitle = findViewById(R.id.tvHeaderSubtitle)
    }

    private fun setupRecyclerView() {
        adapter = VideoAdapter()
        adapter.onSelectionChanged = {
            // 折叠时副标题随选中数实时刷新；展开时不需要（已选信息显示在系列头）
            if (toolbarCollapsed) {
                tvHeaderSubtitle.text = buildCompactSubtitle()
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        // 不再加 DividerItemDecoration，避免破坏分组样式
    }

    private fun setupButtons() {
        btnAccessShizuku.setOnClickListener { useShizukuMode() }
        btnAccessManage.setOnClickListener { requestManageAccess() }
        btnAccessSAF.setOnClickListener { showSAFGuideAndRequest() }
        btnAccessCustom.setOnClickListener { showCustomFolderGuideAndRequest() }

        // 排序下拉
        val sortModes = SortMode.values()
        spinnerSort.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sortModes.map { it.label }
        )
        spinnerSort.setSelection(sortModes.indexOf(currentSortMode))
        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newMode = sortModes[pos]
                if (newMode != currentSortMode) {
                    currentSortMode = newMode
                    adapter.applySort(newMode)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnExpandAll.setOnClickListener { adapter.expandAll() }
        btnCollapseAll.setOnClickListener { adapter.collapseAll() }

        // 搜索框：实时过滤
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val kw = s?.toString().orEmpty()
                adapter.setFilter(kw)
                btnClearSearch.visibility = if (kw.isEmpty()) View.GONE else View.VISIBLE
                refreshListStatusLine()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        btnClearSearch.setOnClickListener { etSearch.setText("") }

        btnSelectAll.setOnClickListener {
            // 仅作用于"当前可见"视频（搜索过滤时只对结果集生效）
            val total = adapter.getDisplayedVideoCount()
            val selected = adapter.getDisplayedSelectedCount()
            if (total > 0 && selected == total) {
                adapter.deselectAll()
                btnSelectAll.text = "全选"
            } else {
                adapter.selectAll()
                btnSelectAll.text = "取消全选"
            }
        }

        btnExportSelected.setOnClickListener { confirmAndExport() }
        btnExportCompact.setOnClickListener { confirmAndExport() }

        btnToggleToolbar.setOnClickListener { toggleToolbar() }
    }

    /**
     * 控制导出期间是否保持设备唤醒：
     *  - FLAG_KEEP_SCREEN_ON：APP 在前台时屏幕不熄灭
     *  - PARTIAL_WAKE_LOCK：CPU 不休眠，即使屏幕关闭也能继续 IO/合并
     */
    private fun keepDeviceAwake(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (exportWakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                exportWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BiliExporter:export"
                )
            }
            try {
                if (exportWakeLock?.isHeld == false) {
                    exportWakeLock?.acquire(60 * 60 * 1000L) // 最长 1 小时，避免泄漏
                }
            } catch (_: Exception) {
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                if (exportWakeLock?.isHeld == true) exportWakeLock?.release()
            } catch (_: Exception) {
            }
        }
    }

    /** 折叠/展开顶部工具区，让视频列表占据更多空间。 */
    private fun toggleToolbar() {
        toolbarCollapsed = !toolbarCollapsed
        if (toolbarCollapsed) {
            collapsibleSection.visibility = View.GONE
            btnToggleToolbar.text = "▼"
            tvHeaderSubtitle.text = buildCompactSubtitle()
        } else {
            collapsibleSection.visibility = View.VISIBLE
            btnToggleToolbar.text = "▲"
            tvHeaderSubtitle.text = "访问 Android/data/tv.danmaku.bili/download"
        }
    }

    /** 折叠模式下副标题显示已选数等关键信息。 */
    private fun buildCompactSubtitle(): String {
        if (!::adapter.isInitialized || groupList.isEmpty()) {
            return "访问 Android/data/tv.danmaku.bili/download"
        }
        val total = groupList.sumOf { it.videos.size }
        val selected = adapter.getSelectedVideos().size
        val visible = adapter.getDisplayedVideoCount()
        val filterHint = if (adapter.hasActiveFilter()) "  搜索: $visible/$total" else ""
        return "已选 $selected · 共 $total 集$filterHint  ·  点 ▼ 展开工具栏"
    }

    // ===== 权限恢复 / 初始状态 =====

    private fun tryRestoreAccess() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    loadVideosDirect()
                    return
                }
                val savedUri = getSavedSAFUri()
                if (savedUri != null && hasPersistentPermission(savedUri)) {
                    loadVideosSAF(savedUri)
                    return
                }
                showInitialState()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    loadVideosDirect()
                } else {
                    showInitialState()
                }
            }
            else -> loadVideosDirect()
        }
    }

    private fun showInitialState() {
        val sdkInt = Build.VERSION.SDK_INT
        val isAndroid13Plus = sdkInt >= Build.VERSION_CODES.TIRAMISU
        val shizukuStatus = ShizukuHelper.checkStatus(this)
        tvStatus.text = buildString {
            appendLine("当前系统：Android ${Build.VERSION.RELEASE}（API $sdkInt）")
            appendLine("Shizuku 状态：${ShizukuHelper.statusDescription(shizukuStatus)}")
            appendLine()
            if (isAndroid13Plus) {
                appendLine("⚠ Android 13+ 已封死 SAF 和 MANAGE_EXTERNAL_STORAGE 对 Android/data 的访问。")
                appendLine("推荐使用【方案 ①】Shizuku 或【方案 ②】中转复制。")
                appendLine()
            }
            appendLine("【方案 ① Shizuku】★ 完美方案 / 直接读取无需复制")
            appendLine("原理：通过 ADB 启动 shell UID 的服务，绕过 Scoped Storage 限制。")
            appendLine("需要先装 Shizuku 并启动（详情看「Shizuku」按钮弹窗）。")
            appendLine()
            appendLine("【方案 ② 中转复制】可靠备用 / 无需 ADB")
            appendLine("用 MIUI 自带「文件管理」把 download 复制到 /Download/，再用 APP 扫描。")
            appendLine()
            appendLine("【方案 ③ SAF 直达 download】仅 Android 11/12 有效")
            appendLine()
            appendLine("【方案 ④ 完全文件访问权限】部分 MIUI 有效")
            appendLine()
            appendLine("目标路径：$BILIBILI_RELATIVE_PATH")
        }
        btnAccessShizuku.visibility = View.VISIBLE
        btnAccessCustom.visibility = View.VISIBLE
        btnAccessSAF.visibility = View.VISIBLE
        btnAccessManage.visibility = View.VISIBLE
        btnSelectAll.visibility = View.GONE
        btnExportSelected.visibility = View.GONE
        toolbarSort.visibility = View.GONE
        toolbarSearch.visibility = View.GONE
        btnToggleToolbar.visibility = View.GONE
        btnExportCompact.visibility = View.GONE
        // 初始状态强制展开
        if (toolbarCollapsed) toggleToolbar()
    }

    // ===== 方案 ① Shizuku =====

    private fun useShizukuMode() {
        when (ShizukuHelper.checkStatus(this)) {
            ShizukuHelper.Status.NOT_INSTALLED -> showShizukuNotInstalledDialog()
            ShizukuHelper.Status.NOT_RUNNING -> showShizukuNotRunningDialog()
            ShizukuHelper.Status.NEED_PERMISSION -> {
                AlertDialog.Builder(this)
                    .setTitle("Shizuku 权限申请")
                    .setMessage("点击「申请」后会弹出 Shizuku 授权对话框，请选择「允许」。")
                    .setPositiveButton("申请") { _, _ -> ShizukuHelper.requestPermission() }
                    .setNegativeButton("取消", null)
                    .show()
            }
            ShizukuHelper.Status.READY -> bindShizukuAndLoad()
        }
    }

    private fun showShizukuNotInstalledDialog() {
        AlertDialog.Builder(this)
            .setTitle("未安装 Shizuku")
            .setMessage(
                "Shizuku 是一个开源的「特权服务桥梁」工具，本 APP 通过它访问 Android/data。\n\n" +
                        "安装步骤：\n" +
                        "1. 打开 ${ShizukuHelper.DOWNLOAD_URL}\n" +
                        "2. 下载最新版 APK 并安装\n" +
                        "3. 打开 Shizuku APP\n" +
                        "4. 按提示用 ADB 启动服务（Shizuku 内有详细说明）\n" +
                        "5. 回到本 APP，再次点击「Shizuku」按钮"
            )
            .setPositiveButton("复制下载链接") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("Shizuku Download", ShizukuHelper.DOWNLOAD_URL)
                )
                Toast.makeText(this, "下载链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("打开浏览器") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuHelper.DOWNLOAD_URL)))
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showShizukuNotRunningDialog() {
        AlertDialog.Builder(this)
            .setTitle("Shizuku 服务未启动")
            .setMessage(
                "Shizuku APP 已安装，但服务未运行。\n\n" +
                        "启动方法（无 root 设备）：\n" +
                        "1. 打开 Shizuku APP\n" +
                        "2. 按 APP 内提示连接电脑并启用「USB 调试」\n" +
                        "3. 电脑端执行 Shizuku 给出的 adb 命令\n" +
                        "   （类似：adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh）\n\n" +
                        "启动方法（root 设备）：\n" +
                        "Shizuku APP 内点击「启动」即可\n\n" +
                        "注：手机重启后需要重新启动 Shizuku 服务"
            )
            .setPositiveButton("打开 Shizuku") { _, _ ->
                try {
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent)
                    else Toast.makeText(this, "无法打开 Shizuku APP", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开 Shizuku APP", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun bindShizukuAndLoad() {
        tvStatus.text = "正在启动 Shizuku 远程服务..."
        setLoadingState(true)
        try {
            ShizukuHelper.bindUserService(this, userServiceConnection)
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku 服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            setLoadingState(false)
            showInitialState()
        }
    }

    private fun loadVideosShizuku() {
        val biliPath = "/sdcard/$BILIBILI_RELATIVE_PATH"
        btnAccessShizuku.visibility = View.GONE
        btnAccessManage.visibility = View.GONE
        btnAccessSAF.visibility = View.GONE
        btnAccessCustom.visibility = View.GONE
        tvStatus.text = "正在通过 Shizuku 扫描 B 站下载目录...\n$biliPath"
        setLoadingState(true)

        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                val svc = userService ?: return@withContext emptyList<BiliVideo>()
                scanShizukuFolder(svc, biliPath)
            }
            updateVideoList(videos, mode = "Shizuku", path = biliPath)
        }
    }

    private fun scanShizukuFolder(svc: IUserService, basePath: String): List<BiliVideo> {
        val episodeList = try {
            svc.listEpisodeFolders(basePath)
        } catch (_: Exception) {
            emptyList<String>()
        }
        return episodeList.mapNotNull { episodePath ->
            try {
                val seriesPath = episodePath.substringBeforeLast('/')
                val (title, partName) = parseEntryJsonShizuku(svc, episodePath)
                val quality = findBestQualityShizuku(svc, episodePath)
                val lastMod = try { svc.lastModified(episodePath) } catch (_: Exception) { 0L }
                BiliVideo(
                    source = VideoSource.Shizuku(episodePath, seriesPath),
                    title = title,
                    partName = partName,
                    quality = quality,
                    lastModified = lastMod
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseEntryJsonShizuku(svc: IUserService, episodePath: String): Pair<String, String> {
        val jsonPath = "$episodePath/entry.json"
        if (!svc.exists(jsonPath)) {
            val name = episodePath.substringAfterLast('/')
            return name to name
        }
        return try {
            val content = svc.readTextFile(jsonPath)
            if (content.isEmpty()) {
                val name = episodePath.substringAfterLast('/')
                name to name
            } else {
                val json = JSONObject(content)
                val title = json.optString("title", episodePath.substringAfterLast('/'))
                val part = try {
                    json.getJSONObject("page_data").getString("part").takeIf { it.isNotBlank() } ?: title
                } catch (_: Exception) {
                    title
                }
                title to part
            }
        } catch (_: Exception) {
            val name = episodePath.substringAfterLast('/')
            name to name
        }
    }

    private fun findBestQualityShizuku(svc: IUserService, episodePath: String): String {
        for (q in QUALITY_PRIORITY) {
            if (svc.exists("$episodePath/$q")) return q
        }
        val children = try { svc.listDir(episodePath) } catch (_: Exception) { emptyList<String>() }
        return children
            .firstOrNull { it.startsWith("[D] ") }
            ?.removePrefix("[D] ")
            ?: "unknown"
    }

    // ===== 申请权限 =====

    private fun requestManageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("申请完全文件访问权限")
                .setMessage(
                    "即将打开系统设置页面\n\n" +
                            "请按这两步操作：\n" +
                            "1. 在权限页面将开关打开（可能叫「访问所有文件」「管理所有文件」「不限制」）\n" +
                            "2. 返回本 APP（按返回键或左上角箭头），视频列表会自动加载\n\n" +
                            "注意：\n" +
                            "• MIUI 13/14 通常允许访问 Android/data\n" +
                            "• Android 13 原生系统可能仍然无法访问\n" +
                            "• 如果此方案无效，请改用方案①或方案③"
                )
                .setPositiveButton("前往设置") { _, _ -> openManageAllFilesSettings() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            storagePermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** 打开"管理所有文件"权限设置页（带 fallback） */
    private fun openManageAllFilesSettings() {
        val candidates = listOf(
            // 优先：直接定位到本 APP 的开关页
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Fallback 1：系统级权限列表页（用户需自己滚动找到本 APP）
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Fallback 2：APP 详情页（用户需手动点击权限管理）
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "无法打开权限设置页，请手动到「设置 → 应用 → B站下载导出 → 权限」开启", Toast.LENGTH_LONG).show()
    }

    private fun showSAFGuideAndRequest() {
        AlertDialog.Builder(this)
            .setTitle("SAF 直达 download 目录")
            .setMessage(
                "接下来文件选择器会直接打开 B 站的 download 目录，\n" +
                        "你不需要手动导航！\n\n" +
                        "请按以下步骤操作：\n" +
                        "1. 直接点击屏幕底部的「使用此文件夹」按钮\n" +
                        "   （按钮文字也可能是「选择此文件夹」「使用」「确定」）\n" +
                        "2. 弹出确认时点「允许」即可\n\n" +
                        "提示：\n" +
                        "• 如果显示空目录，说明你还没用 B 站下载过视频\n" +
                        "• 如果提示无权访问/打开失败，请改用方案③"
            )
            .setPositiveButton("打开文件选择器") { _, _ -> launchSAFPicker() }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 关键技巧：通过 StorageManager.primaryStorageVolume 获取 INITIAL_URI，
     * 然后改写 URI 让选择器直接跳转到 Android/data/tv.danmaku.bili/download 目录，
     * 跳过 Android 11+ 隐藏 Android/data 的限制。
     *
     * 此技巧在 MIUI、原生 Android 11/12 上有效。
     * Android 13 (API 33) 可能仍然有效，但 13 QPR1 之后部分场景被堵。
     */
    private fun launchSAFPicker() {
        try {
            val intent: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val sm = getSystemService(STORAGE_SERVICE) as StorageManager
                @Suppress("DEPRECATION")
                val baseIntent = sm.primaryStorageVolume.createOpenDocumentTreeIntent()

                // 替换 INITIAL_URI 为目标 download 目录
                val originalUri: Uri? = baseIntent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI)
                val targetUri = if (originalUri != null) {
                    val originalStr = originalUri.toString()
                    // /tree/primary%3A → /document/primary%3AAndroid%2Fdata%2Ftv.danmaku.bili%2Fdownload
                    val docPath = "Android%2Fdata%2F$BILIBILI_PACKAGE%2Fdownload"
                    val rewritten = originalStr
                        .replace("/root/", "/document/")
                        .replace("/tree/", "/document/")
                        .let { if (it.endsWith("%3A")) "$it$docPath" else "$it%3A$docPath" }
                    Uri.parse(rewritten)
                } else {
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Android/data/$BILIBILI_PACKAGE/download"
                    )
                }
                baseIntent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, targetUri)
                baseIntent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                baseIntent
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
            }
            safLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打开文件选择器失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 方案①：中转复制方案。
     * 用户先用 MIUI 文件管理器把 download 复制到 /sdcard/Download/ 等普通位置，
     * 再用本 APP 选择该位置进行扫描——绕过所有 Android/data 的限制。
     * 这是 Android 13+ 唯一可行的方案。
     */
    private fun showCustomFolderGuideAndRequest() {
        AlertDialog.Builder(this)
            .setTitle("方案①：中转复制")
            .setMessage(
                "Android 13+ 唯一可行方案，操作步骤：\n\n" +
                        "1. 点击下面「打开 MIUI 文件管理」\n" +
                        "   （如未安装会自动跳过此步）\n" +
                        "2. 在文件管理器中进入：\n" +
                        "   内部存储 → Android → data → tv.danmaku.bili → download\n" +
                        "3. 长按 download 文件夹 → 复制 → 粘贴到 /Download/\n" +
                        "4. 回到本 APP，点「直接选择已复制目录」\n" +
                        "5. 导航到 /Download/ 下，选择刚才粘贴的 download 文件夹\n\n" +
                        "提示：\n" +
                        "• MIUI 自带的文件管理器有特殊权限可访问 Android/data\n" +
                        "• 复制大文件需要等一会儿"
            )
            .setPositiveButton("打开 MIUI 文件管理") { _, _ -> openMiuiFileExplorer() }
            .setNeutralButton("直接选择已复制目录") { _, _ -> launchSAFPickerGeneric() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 尝试启动 MIUI 自带的文件管理器，定位到 Android/data/tv.danmaku.bili */
    private fun openMiuiFileExplorer() {
        // 优先尝试已知 MIUI 文件管理器包名
        val miuiPackages = listOf(
            "com.android.fileexplorer",      // MIUI 国行/国际
            "com.mi.android.globalFileexplorer" // 部分国际版
        )
        for (pkg in miuiPackages) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    Toast.makeText(
                        this,
                        "已打开 MIUI 文件管理\n请手动进入 Android/data/tv.danmaku.bili/download",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            } catch (_: Exception) {
            }
        }
        // Fallback：通过 VIEW Intent 触发系统默认文件管理器
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                    "vnd.android.document/directory"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "请手动找到并打开 MIUI 文件管理", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "未找到 MIUI 文件管理器\n请到桌面手动打开「文件管理」",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 通用 SAF 选择器，初始化定位到 /sdcard/Download */
    private fun launchSAFPickerGeneric() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                // 定位到 Download 目录，方便用户找到刚粘贴的文件夹
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val initialUri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Download"
                    )
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
            }
            safLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打开文件选择器失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ===== 加载视频列表 =====

    private fun loadVideosDirect() {
        val biliDir = File(
            Environment.getExternalStorageDirectory(),
            BILIBILI_RELATIVE_PATH
        )
        btnAccessShizuku.visibility = View.GONE
        btnAccessManage.visibility = View.GONE
        btnAccessSAF.visibility = View.GONE
        btnAccessCustom.visibility = View.GONE
        tvStatus.text = "正在扫描 B 站下载目录...\n${biliDir.absolutePath}"
        setLoadingState(true)

        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                if (!biliDir.exists() || !biliDir.canRead()) emptyList()
                else scanDirectFolder(biliDir)
            }
            updateVideoList(videos, mode = "直接访问", path = biliDir.absolutePath)
        }
    }

    private fun loadVideosSAF(treeUri: Uri) {
        btnAccessShizuku.visibility = View.GONE
        btnAccessManage.visibility = View.GONE
        btnAccessSAF.visibility = View.GONE
        btnAccessCustom.visibility = View.GONE
        tvStatus.text = "正在扫描 B 站下载目录（SAF 模式）..."
        setLoadingState(true)

        lifecycleScope.launch {
            val docFile = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
            val videos = withContext(Dispatchers.IO) {
                if (docFile == null || !docFile.exists()) emptyList()
                else scanSAFFolder(docFile)
            }
            updateVideoList(videos, mode = "SAF", path = treeUri.toString())
        }
    }

    private var currentModeLabel: String = ""

    private fun updateVideoList(videos: List<BiliVideo>, mode: String, path: String) {
        val groups = buildGroups(videos)
        groupList.clear()
        groupList.addAll(groups)
        adapter.setGroups(groupList)
        adapter.applySort(currentSortMode)
        currentModeLabel = mode
        setLoadingState(false)

        if (videos.isEmpty()) {
            tvStatus.text = "[$mode 模式] 未找到 B 站下载视频\n\n" +
                    "路径：$path\n\n" +
                    "可能原因：\n" +
                    "1. B 站 APP 没下载过视频\n" +
                    "2. 选择的目录不正确（需要选 download 文件夹或其父目录）\n" +
                    "3. 当前授权模式没权限读取此目录\n\n" +
                    "可重试其他方案↓"
            btnAccessShizuku.visibility = View.VISIBLE
            btnAccessManage.visibility = View.VISIBLE
            btnAccessSAF.visibility = View.VISIBLE
            btnAccessCustom.visibility = View.VISIBLE
            toolbarSort.visibility = View.GONE
            toolbarSearch.visibility = View.GONE
            btnToggleToolbar.visibility = View.GONE
            btnExportCompact.visibility = View.GONE
        } else {
            btnSelectAll.visibility = View.VISIBLE
            btnExportSelected.visibility = View.VISIBLE
            toolbarSort.visibility = View.VISIBLE
            toolbarSearch.visibility = View.VISIBLE
            btnToggleToolbar.visibility = View.VISIBLE
            btnExportCompact.visibility = View.VISIBLE
            refreshListStatusLine()
        }
    }

    /** 根据搜索状态刷新顶部 tvStatus 显示。 */
    private fun refreshListStatusLine() {
        if (groupList.isEmpty()) return
        val totalGroups = groupList.size
        val totalVideos = groupList.sumOf { it.videos.size }
        tvStatus.text = if (adapter.hasActiveFilter()) {
            "搜索结果：${adapter.getDisplayedGroupCount()} 个系列 / ${adapter.getDisplayedVideoCount()} 集" +
                    "  (全部 $totalGroups 系列 / $totalVideos 集)  [$currentModeLabel 模式]"
        } else {
            "共 $totalGroups 个系列 / $totalVideos 集视频  [$currentModeLabel 模式]"
        }
    }

    /** 按 seriesFolderName 把扫描得到的扁平视频列表分组为 VideoGroup */
    private fun buildGroups(videos: List<BiliVideo>): List<VideoGroup> {
        if (videos.isEmpty()) return emptyList()
        val byName = videos.groupBy { it.seriesFolderName }
        return byName.map { (name, vs) ->
            val title = vs.firstOrNull { it.title.isNotBlank() }?.title ?: name
            val lastMod = vs.maxOfOrNull { it.lastModified } ?: 0L
            VideoGroup(
                seriesFolderName = name,
                seriesTitle = title,
                videos = vs.toMutableList(),
                lastModified = lastMod,
                expanded = true
            )
        }
    }

    // ===== 扫描文件夹 =====

    private fun scanDirectFolder(baseDir: File): List<BiliVideo> {
        val videos = mutableListOf<BiliVideo>()
        scanDirectRecursive(baseDir, null, videos)
        return videos
    }

    /**
     * 识别"集数文件夹"：目录里**含 `entry.json` 文件**即视为集数。
     * 兼容 B 站新老缓存格式（c_12345678 / c_116401868112562 / 纯数字 / 其他）。
     */
    private fun isEpisodeFolderDirect(dir: File): Boolean =
        File(dir, "entry.json").isFile

    private fun scanDirectRecursive(
        dir: File,
        seriesFolder: File?,
        videos: MutableList<BiliVideo>
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (!child.isDirectory) continue
            if (isEpisodeFolderDirect(child)) {
                val (title, partName) = readEntryJsonDirect(child)
                val quality = findBestQualityDirect(child)
                videos.add(
                    BiliVideo(
                        source = VideoSource.Direct(
                            episodeFolder = child,
                            seriesFolder = seriesFolder ?: dir
                        ),
                        title = title,
                        partName = partName,
                        quality = quality,
                        lastModified = child.lastModified()
                    )
                )
            } else {
                scanDirectRecursive(child, child, videos)
            }
        }
    }

    private fun readEntryJsonDirect(episodeFolder: File): Pair<String, String> {
        val jsonFile = File(episodeFolder, "entry.json")
        if (!jsonFile.exists()) return episodeFolder.name to episodeFolder.name
        return try {
            val json = JSONObject(jsonFile.readText(Charsets.UTF_8))
            val title = json.optString("title", episodeFolder.name)
            val part = try {
                json.getJSONObject("page_data").getString("part").takeIf { it.isNotBlank() } ?: title
            } catch (_: Exception) {
                title
            }
            title to part
        } catch (_: Exception) {
            episodeFolder.name to episodeFolder.name
        }
    }

    private fun findBestQualityDirect(episodeFolder: File): String {
        for (q in QUALITY_PRIORITY) {
            if (File(episodeFolder, q).isDirectory) return q
        }
        return episodeFolder.listFiles()?.firstOrNull { it.isDirectory }?.name ?: "unknown"
    }

    private fun scanSAFFolder(docFile: DocumentFile): List<BiliVideo> {
        val videos = mutableListOf<BiliVideo>()
        scanSAFRecursive(docFile, null, videos)
        return videos
    }

    /** SAF 模式下识别集数：listFiles 中存在 entry.json。 */
    private fun isEpisodeFolderSAF(docFile: DocumentFile): Boolean {
        val entry = docFile.findFile("entry.json")
        return entry != null && entry.isFile
    }

    private fun scanSAFRecursive(
        docFile: DocumentFile,
        seriesDoc: DocumentFile?,
        videos: MutableList<BiliVideo>
    ) {
        val children = docFile.listFiles()
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name == null) continue
            if (isEpisodeFolderSAF(child)) {
                val (title, partName) = readEntryJsonSAF(child)
                val quality = findBestQualitySAF(child)
                videos.add(
                    BiliVideo(
                        source = VideoSource.SAF(
                            episodeDoc = child,
                            seriesDoc = seriesDoc ?: docFile
                        ),
                        title = title,
                        partName = partName,
                        quality = quality,
                        lastModified = child.lastModified()
                    )
                )
            } else {
                scanSAFRecursive(child, child, videos)
            }
        }
    }

    private fun readEntryJsonSAF(episodeDoc: DocumentFile): Pair<String, String> {
        val entryFile = episodeDoc.findFile("entry.json")
            ?: return (episodeDoc.name ?: "unknown") to (episodeDoc.name ?: "unknown")
        return try {
            val content = contentResolver.openInputStream(entryFile.uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return "unknown" to "unknown"
            val json = JSONObject(content)
            val title = json.optString("title", episodeDoc.name ?: "unknown")
            val part = try {
                json.getJSONObject("page_data").getString("part").takeIf { it.isNotBlank() } ?: title
            } catch (_: Exception) {
                title
            }
            title to part
        } catch (_: Exception) {
            (episodeDoc.name ?: "unknown") to (episodeDoc.name ?: "unknown")
        }
    }

    private fun findBestQualitySAF(episodeDoc: DocumentFile): String {
        val children = episodeDoc.listFiles()
        for (q in QUALITY_PRIORITY) {
            if (children.any { it.name == q && it.isDirectory }) return q
        }
        return children.firstOrNull { it.isDirectory }?.name ?: "unknown"
    }

    // ===== 导出 =====

    /** 导出选项 */
    data class ExportOptions(
        val merge: Boolean,            // 合并为 MP4
        val deleteOriginal: Boolean,   // 合并后删除 B 站源缓存（Android/data 下的 c_XXX）
        val deleteExportedM4s: Boolean,// 合并后删除导出目录下的 c_XXX 副本
        val renameToTitle: Boolean     // 用视频标题命名系列文件夹
    )

    private fun confirmAndExport() {
        val selected = adapter.getSelectedVideos()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选要导出的视频", Toast.LENGTH_SHORT).show()
            return
        }

        val seriesCount = selected.map { it.seriesFolderName }.toSet().size
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            EXPORT_DIR_NAME
        )

        val dialogView = layoutInflater.inflate(R.layout.dialog_export_options, null)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tvSummary)
        val cbMerge = dialogView.findViewById<android.widget.CheckBox>(R.id.cbMerge)
        val cbDeleteOriginal = dialogView.findViewById<android.widget.CheckBox>(R.id.cbDeleteOriginal)
        val cbDeleteExportedM4s = dialogView.findViewById<android.widget.CheckBox>(R.id.cbDeleteExportedM4s)
        val cbRenameToTitle = dialogView.findViewById<android.widget.CheckBox>(R.id.cbRenameToTitle)

        tvSummary.text = "将导出 ${selected.size} 集视频（共 $seriesCount 个系列）\n" +
                "导出目录：${exportDir.absolutePath}"

        // 联动：取消"合并"则禁用两种删除选项（删除依赖合并成功）
        cbMerge.setOnCheckedChangeListener { _, checked ->
            cbDeleteOriginal.isEnabled = checked
            cbDeleteExportedM4s.isEnabled = checked
            if (!checked) {
                cbDeleteOriginal.isChecked = false
                cbDeleteExportedM4s.isChecked = false
            }
        }

        AlertDialog.Builder(this)
            .setTitle("导出选项")
            .setView(dialogView)
            .setPositiveButton("开始导出") { _, _ ->
                val deleteSource = cbDeleteOriginal.isChecked && cbMerge.isChecked
                val deleteExported = cbDeleteExportedM4s.isChecked && cbMerge.isChecked
                val opts = ExportOptions(
                    merge = cbMerge.isChecked,
                    deleteOriginal = deleteSource,
                    deleteExportedM4s = deleteExported,
                    renameToTitle = cbRenameToTitle.isChecked
                )
                if (deleteSource) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠ 危险操作确认")
                        .setMessage(
                            "你勾选了「删除 B 站缓存源文件夹」。\n\n" +
                                    "合并 MP4 成功后会从 B 站缓存目录\n" +
                                    "Android/data/tv.danmaku.bili/download/\n" +
                                    "中删除选中的 ${selected.size} 集 c_XXX 文件夹。\n\n" +
                                    "B 站 APP 之后将无法再播放这些已下载视频\n" +
                                    "（但 MP4 文件保留在导出目录）。\n\n" +
                                    "确认继续？"
                        )
                        .setPositiveButton("确认删除") { _, _ -> doExport(selected, exportDir, opts) }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    doExport(selected, exportDir, opts)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExport(videos: List<BiliVideo>, exportDir: File, options: ExportOptions) {
        setLoadingState(true)
        tvProgress.visibility = View.VISIBLE
        progressExport.visibility = View.VISIBLE
        progressExport.progress = 0

        // 防止屏幕熄灭/CPU休眠导致导出被打断
        keepDeviceAwake(true)

        // 总进度单元 = 每个系列复制 1 单位 + 每个视频合并 1 单位（如选中合并）
        val seriesMap = videos.groupBy { it.seriesFolderName }
        val totalUnits = seriesMap.size + (if (options.merge) videos.size else 0)
        var doneUnits = 0
        progressExport.max = totalUnits.coerceAtLeast(1)

        suspend fun pushProgress(stageText: String) {
            withContext(Dispatchers.Main) {
                progressExport.progress = doneUnits
                val pct = if (totalUnits > 0) (doneUnits * 100 / totalUnits) else 0
                tvProgress.text = "[$pct%] $stageText"
            }
        }

        lifecycleScope.launch {
            var copiedCount = 0
            var mergedCount = 0
            var failedCount = 0
            val errors = mutableListOf<String>()
            val producedMp4s = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                exportDir.mkdirs()

                seriesMap.entries.forEachIndexed { index, (seriesName, seriesVideos) ->
                    // 决定本系列输出文件夹名（标题 or 原 ID）
                    val seriesTitle = seriesVideos.firstOrNull()?.title?.takeIf { it.isNotBlank() }
                    val targetDirName =
                        if (options.renameToTitle && !seriesTitle.isNullOrBlank())
                            Mp4Merger.sanitizeFilename(seriesTitle)
                        else seriesName
                    val destSeriesDir = File(exportDir, targetDirName)

                    pushProgress("复制系列 ${index + 1}/${seriesMap.size}：$targetDirName")
                    try {
                        // === 第一阶段：复制原始数据到导出目录 ===
                        copySeriesToDest(seriesVideos.first().source, destSeriesDir)
                        copiedCount += seriesVideos.size
                        doneUnits++
                        pushProgress("已复制 [$targetDirName]")

                        // === 第二阶段：合并 MP4 ===
                        if (options.merge) {
                            seriesVideos.forEachIndexed eachVideo@{ vIdx, video ->
                                pushProgress("合并 [$targetDirName] ${vIdx + 1}/${seriesVideos.size}: ${video.partName}")
                                val episodeDirName = video.episodeFolderName
                                val episodeDir = File(destSeriesDir, episodeDirName)
                                val mp4Name = Mp4Merger.sanitizeFilename(
                                    video.partName.ifBlank { video.title.ifBlank { episodeDirName } }
                                ) + ".mp4"
                                val outputMp4 = File(destSeriesDir, mp4Name)

                                val m4sPair = findM4sFiles(episodeDir)
                                if (m4sPair == null) {
                                    failedCount++
                                    errors.add("[$targetDirName/${video.partName}] 找不到 m4s 文件")
                                    return@eachVideo
                                }
                                val (audio, videoStream) = m4sPair

                                val err = Mp4Merger.mergeAudioVideo(
                                    audioFile = audio,
                                    videoFile = videoStream,
                                    outputFile = outputMp4,
                                    onProgress = null
                                )
                                if (err == null) {
                                    mergedCount++
                                    producedMp4s.add(outputMp4.absolutePath)
                                    // === 第三阶段：删除 B 站源缓存（Android/data 下的 c_XXX）===
                                    // 用户的目的是释放手机存储空间（B 站缓存可能占几个 GB）。
                                    if (options.deleteOriginal) {
                                        val (ok, detail) = deleteSourceEpisode(video.source)
                                        if (!ok) {
                                            errors.add("[$targetDirName/${video.partName}] 删除 B 站源缓存失败: $detail")
                                        }
                                    }
                                    // === 第四阶段：删除导出目录中的 c_XXX 副本 ===
                                    // 用户可选保留（含 entry.json/弹幕等）或删除（只留 MP4）
                                    if (options.deleteExportedM4s) {
                                        val ok = tryDeleteRecursively(episodeDir)
                                        if (!ok) {
                                            errors.add("[$targetDirName/${video.partName}] 删除导出目录 c_XXX 副本失败")
                                        }
                                    }
                                } else {
                                    failedCount++
                                    errors.add("[$targetDirName/${video.partName}] 合并失败: $err")
                                }
                                doneUnits++
                                pushProgress("合并 ${vIdx + 1}/${seriesVideos.size} 完成 [$targetDirName]")
                            }
                        }
                    } catch (e: Exception) {
                        failedCount += seriesVideos.size
                        errors.add("$targetDirName: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            // 收尾：进度拉到 100%、释放唤醒锁
            doneUnits = totalUnits
            progressExport.progress = totalUnits
            keepDeviceAwake(false)
            setLoadingState(false)
            tvProgress.visibility = View.GONE
            progressExport.visibility = View.GONE

            val msg = buildString {
                appendLine("导出完成！")
                appendLine("✓ 已复制：$copiedCount 集")
                if (options.merge) appendLine("✓ 已合并 MP4：$mergedCount 集")
                if (failedCount > 0) appendLine("✗ 失败：$failedCount 集")
                appendLine()
                appendLine("导出目录：")
                appendLine(exportDir.absolutePath)
                if (errors.isNotEmpty()) {
                    appendLine()
                    appendLine("错误详情（共 ${errors.size} 条，显示前 10 条）：")
                    errors.take(10).forEach { appendLine("• $it") }
                }
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("导出结果")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /** 根据 VideoSource 把整个系列文件夹复制到目标位置 */
    private fun copySeriesToDest(source: VideoSource, destSeriesDir: File) {
        destSeriesDir.parentFile?.mkdirs()
        when (source) {
            is VideoSource.Direct -> {
                source.seriesFolder.copyRecursively(destSeriesDir, overwrite = true)
            }
            is VideoSource.SAF -> {
                copyDocumentDirTo(source.seriesDoc, destSeriesDir)
            }
            is VideoSource.Shizuku -> {
                val svc = userService ?: throw RuntimeException("Shizuku 服务未绑定")
                val ok = svc.copyDirectory(source.seriesPath, destSeriesDir.absolutePath)
                if (!ok) throw RuntimeException("Shizuku 复制目录失败")
            }
        }
    }

    /**
     * 在集数文件夹下查找 audio.m4s 和 video.m4s。
     * 优先尝试已知清晰度子目录（80/64 等），失败时递归搜索。
     */
    private fun findM4sFiles(episodeDir: File): Pair<File, File>? {
        if (!episodeDir.isDirectory) return null
        // 优先尝试清晰度目录
        for (q in QUALITY_PRIORITY) {
            val sub = File(episodeDir, q)
            val audio = File(sub, "audio.m4s")
            val video = File(sub, "video.m4s")
            if (audio.exists() && video.exists()) return audio to video
        }
        // 兜底：递归找第一个含 audio.m4s + video.m4s 的目录
        episodeDir.walkTopDown().forEach { f ->
            if (f.isDirectory) {
                val audio = File(f, "audio.m4s")
                val video = File(f, "video.m4s")
                if (audio.exists() && video.exists()) return audio to video
            }
        }
        return null
    }

    /**
     * 删除 B 站缓存源目录下的 c_XXX 集数文件夹。
     *
     * 三种 VideoSource 走不同删除路径：
     *  - Direct：APP 进程直接 `deleteRecursively()`，要求有 MANAGE_EXTERNAL_STORAGE
     *  - SAF：通过 DocumentFile.delete() 删除（SAF 授权时已带写权限）
     *  - Shizuku：通过 IUserService.deleteRecursively() 由 shell UID 删
     *
     * @return Pair(成功标志, 失败原因/补充说明)
     */
    private fun deleteSourceEpisode(source: VideoSource): Pair<Boolean, String> {
        return when (source) {
            is VideoSource.Direct -> {
                val f = source.episodeFolder
                if (!f.exists()) return true to "已不存在"
                val ok = tryDeleteRecursively(f)
                if (ok) true to "OK"
                else false to "APP 进程无法 unlink，可能需要 MANAGE_EXTERNAL_STORAGE 权限"
            }
            is VideoSource.SAF -> {
                val doc = source.episodeDoc
                if (!doc.exists()) return true to "已不存在"
                try {
                    val ok = doc.delete()
                    if (ok || !doc.exists()) true to "OK"
                    else false to "DocumentFile.delete() 返回 false，SAF 可能无写权限"
                } catch (e: Exception) {
                    false to "${e.javaClass.simpleName}: ${e.message}"
                }
            }
            is VideoSource.Shizuku -> {
                val svc = userService
                    ?: return false to "Shizuku 服务未绑定（请重新加载列表）"
                try {
                    val exists = try { svc.exists(source.episodePath) } catch (_: Exception) { true }
                    if (!exists) return true to "已不存在"
                    val ok = svc.deleteRecursively(source.episodePath)
                    val stillExists = try { svc.exists(source.episodePath) } catch (_: Exception) { false }
                    if (ok && !stillExists) true to "OK"
                    else false to "Shizuku deleteRecursively 返回 false，stillExists=$stillExists"
                } catch (e: Exception) {
                    false to "${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    /**
     * 通用递归删除（带 Shizuku fallback）。用于删除导出目录中的文件等场景。
     *
     * 在 Shizuku 模式下，导出目录的文件由 shell UID（uid=2000）的 UserService 复制，
     * 即便 chmod 0755/0644，APP 进程也可能因为属主不同而无法 unlink。
     * 因此本地删除失败时，会再尝试通过 Shizuku 服务以 shell 身份删除。
     */
    private fun tryDeleteRecursively(target: File): Boolean {
        if (!target.exists()) return true
        // 第一步：尝试 APP 进程直接删除
        val locallyDeleted = try {
            target.deleteRecursively()
        } catch (_: Exception) {
            false
        }
        if (locallyDeleted && !target.exists()) return true

        // 第二步：Shizuku 服务（shell UID）兜底
        val svc = userService
        if (svc != null) {
            try {
                val ok = svc.deleteRecursively(target.absolutePath)
                if (ok || !target.exists()) return true
            } catch (_: Exception) {
            }
        }

        // 第三步：手工逐文件删除（应对 deleteRecursively 部分失败的情况）
        try {
            if (target.isDirectory) {
                target.walkBottomUp().forEach { f ->
                    try { f.delete() } catch (_: Exception) {}
                }
            } else {
                target.delete()
            }
        } catch (_: Exception) {
        }
        return !target.exists()
    }

    /** 递归将 SAF DocumentFile 目录复制到目标 File 目录 */
    private fun copyDocumentDirTo(srcDoc: DocumentFile, destDir: File) {
        if (srcDoc.isDirectory) {
            destDir.mkdirs()
            for (child in srcDoc.listFiles()) {
                copyDocumentDirTo(child, File(destDir, child.name ?: "unknown"))
            }
        } else {
            destDir.parentFile?.mkdirs()
            contentResolver.openInputStream(srcDoc.uri)?.use { input ->
                destDir.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 512 * 1024)
                }
            }
        }
    }

    // ===== 工具方法 =====

    private fun setLoadingState(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnAccessShizuku.isEnabled = !loading
        btnAccessManage.isEnabled = !loading
        btnAccessSAF.isEnabled = !loading
        btnAccessCustom.isEnabled = !loading
        btnExportSelected.isEnabled = !loading
        btnExportCompact.isEnabled = !loading
        btnSelectAll.isEnabled = !loading
        spinnerSort.isEnabled = !loading
        btnExpandAll.isEnabled = !loading
        btnCollapseAll.isEnabled = !loading
        etSearch.isEnabled = !loading
        btnToggleToolbar.isEnabled = !loading
    }

    private fun saveSAFUri(uri: Uri) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putString(PREF_TREE_URI, uri.toString())
            .apply()
    }

    private fun getSavedSAFUri(): Uri? {
        val str = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .getString(PREF_TREE_URI, null) ?: return null
        return Uri.parse(str)
    }

    private fun hasPersistentPermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
