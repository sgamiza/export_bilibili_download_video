package com.biliexport.downloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分组列表 Adapter：
 *  - 一级：VideoGroup（系列文件夹），可展开/折叠，可整组选中
 *  - 二级：BiliVideo（单集视频），展开时显示
 *
 * 数据源 = List<VideoGroup>，内部扁平化成 flat list 给 RecyclerView。
 */
class VideoAdapter(
    private var allGroups: List<VideoGroup> = emptyList()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_VIDEO = 1
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    /** 扁平条目：要么是 Group header，要么是 Video（且其 group 是展开状态） */
    private sealed class FlatItem {
        data class Header(val group: VideoGroup) : FlatItem()
        data class Video(val video: BiliVideo, val group: VideoGroup) : FlatItem()
    }

    private var groups: MutableList<VideoGroup> = mutableListOf()  // 当前显示（filter + sort 后）
    private var flat: List<FlatItem> = emptyList()
    private var currentFilter: String = ""
    private var currentSort: SortMode = SortMode.DEFAULT

    /** 视频选中状态变化时的回调（用于外部 UI 刷新已选数显示） */
    var onSelectionChanged: (() -> Unit)? = null

    private fun fireSelectionChanged() {
        onSelectionChanged?.invoke()
    }

    init {
        applyFilterAndSort()
    }

    fun setGroups(newGroups: List<VideoGroup>) {
        allGroups = newGroups
        applyFilterAndSort()
    }

    /** 设置搜索关键字（实时过滤）。空字符串 = 清空过滤显示全部。 */
    fun setFilter(keyword: String) {
        currentFilter = keyword.trim()
        applyFilterAndSort()
    }

    private fun applyFilterAndSort() {
        val filtered = filterGroups(allGroups, currentFilter)
        val sorted = sortGroups(filtered, currentSort)
        groups = sorted.toMutableList()
        rebuildFlat()
        notifyDataSetChanged()
    }

    /**
     * 过滤逻辑：
     *  - 系列名（seriesTitle / seriesFolderName）匹配 → 整组保留
     *  - 系列名不匹配但有视频的 title/partName 匹配 → 创建临时 group 只含匹配的视频
     *  - BiliVideo 实例本身被原样传递（不复制），勾选状态自动同步
     */
    private fun filterGroups(source: List<VideoGroup>, keyword: String): List<VideoGroup> {
        if (keyword.isEmpty()) return source
        val kw = keyword.lowercase(Locale.getDefault())
        return source.mapNotNull { g ->
            val seriesMatch = g.seriesTitle.lowercase(Locale.getDefault()).contains(kw) ||
                    g.seriesFolderName.lowercase(Locale.getDefault()).contains(kw)
            if (seriesMatch) {
                g
            } else {
                val matched = g.videos.filter { v ->
                    v.title.lowercase(Locale.getDefault()).contains(kw) ||
                            v.partName.lowercase(Locale.getDefault()).contains(kw)
                }
                if (matched.isEmpty()) null
                else g.copy(videos = matched.toMutableList(), expanded = true)
            }
        }
    }

    private fun sortGroups(source: List<VideoGroup>, mode: SortMode): List<VideoGroup> {
        source.forEach { g -> sortVideosInPlace(g.videos, mode) }
        return source.sortedWith(
            when (mode) {
                SortMode.TIME_DESC -> compareByDescending { it.lastModified }
                SortMode.TIME_ASC -> compareBy { it.lastModified }
                SortMode.NAME_ASC -> compareBy { it.seriesTitle.ifBlank { it.seriesFolderName } }
                SortMode.NAME_DESC -> compareByDescending { it.seriesTitle.ifBlank { it.seriesFolderName } }
            }
        )
    }

    private fun rebuildFlat() {
        val list = mutableListOf<FlatItem>()
        groups.forEach { g ->
            list.add(FlatItem.Header(g))
            if (g.expanded) {
                g.videos.forEach { v -> list.add(FlatItem.Video(v, g)) }
            }
        }
        flat = list
    }

    // ===== Group ViewHolder =====
    class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbGroupSelect: CheckBox = itemView.findViewById(R.id.cbGroupSelect)
        val tvExpand: TextView = itemView.findViewById(R.id.tvExpand)
        val tvGroupTitle: TextView = itemView.findViewById(R.id.tvGroupTitle)
        val tvGroupInfo: TextView = itemView.findViewById(R.id.tvGroupInfo)
    }

    // ===== Video ViewHolder =====
    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvInfo: TextView = itemView.findViewById(R.id.tvInfo)
    }

    override fun getItemViewType(position: Int): Int = when (flat[position]) {
        is FlatItem.Header -> TYPE_GROUP
        is FlatItem.Video -> TYPE_VIDEO
    }

    override fun getItemCount(): Int = flat.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP -> GroupViewHolder(inflater.inflate(R.layout.item_group_header, parent, false))
            else -> VideoViewHolder(inflater.inflate(R.layout.item_video, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = flat[position]) {
            is FlatItem.Header -> bindGroup(holder as GroupViewHolder, item.group)
            is FlatItem.Video -> bindVideo(holder as VideoViewHolder, item.video, item.group)
        }
    }

    private fun bindGroup(holder: GroupViewHolder, group: VideoGroup) {
        holder.tvGroupTitle.text = group.seriesTitle.ifBlank { group.seriesFolderName }
        val timeStr = if (group.lastModified > 0) DATE_FMT.format(Date(group.lastModified)) else "未知时间"
        val selectedHint = if (group.selectedCount > 0) "  •  已选 ${group.selectedCount}" else ""
        holder.tvGroupInfo.text = "${group.videos.size} 集  •  $timeStr$selectedHint  •  ${group.seriesFolderName}"
        holder.tvExpand.text = if (group.expanded) "▼" else "▶"

        // 三态 checkbox（全选 / 半选 / 未选）
        holder.cbGroupSelect.setOnCheckedChangeListener(null)
        holder.cbGroupSelect.isChecked = group.isAllSelected

        // 整行点击：展开/折叠
        holder.itemView.setOnClickListener {
            group.expanded = !group.expanded
            rebuildFlat()
            notifyDataSetChanged()
        }

        // checkbox 点击：整组选中/取消
        holder.cbGroupSelect.setOnClickListener {
            val newState = !group.isAllSelected
            group.videos.forEach { it.isSelected = newState }
            holder.cbGroupSelect.isChecked = newState
            // 通知所有同组的 child 更新
            notifyDataSetChanged()
            fireSelectionChanged()
        }
    }

    private fun bindVideo(holder: VideoViewHolder, video: BiliVideo, parentGroup: VideoGroup) {
        holder.tvTitle.text = video.partName.ifBlank { video.title }
        val timeStr = if (video.lastModified > 0) DATE_FMT.format(Date(video.lastModified)) else ""
        holder.tvInfo.text = "清晰度: ${video.quality}  •  $timeStr"

        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = video.isSelected
        holder.cbSelect.setOnCheckedChangeListener { _, checked ->
            video.isSelected = checked
            // 选中状态变化可能影响父 group 的全选状态，刷新 group header
            val groupIndex = flat.indexOfFirst { it is FlatItem.Header && it.group === parentGroup }
            if (groupIndex >= 0) notifyItemChanged(groupIndex)
            fireSelectionChanged()
        }
        holder.itemView.setOnClickListener {
            holder.cbSelect.isChecked = !holder.cbSelect.isChecked
        }
    }

    // ===== 公共方法 =====

    /** 全部展开 */
    fun expandAll() {
        groups.forEach { it.expanded = true }
        rebuildFlat()
        notifyDataSetChanged()
    }

    /** 全部折叠 */
    fun collapseAll() {
        groups.forEach { it.expanded = false }
        rebuildFlat()
        notifyDataSetChanged()
    }

    /** 选中**当前可见**的全部视频（搜索过滤时只影响可见项，不误选过滤掉的） */
    fun selectAll() {
        groups.forEach { g -> g.videos.forEach { it.isSelected = true } }
        notifyDataSetChanged()
        fireSelectionChanged()
    }

    /** 取消选中**当前可见**的全部视频 */
    fun deselectAll() {
        groups.forEach { g -> g.videos.forEach { it.isSelected = false } }
        notifyDataSetChanged()
        fireSelectionChanged()
    }

    /** 返回**所有已选**视频（不论是否被过滤隐藏），用于导出 */
    fun getSelectedVideos(): List<BiliVideo> =
        allGroups.flatMap { g -> g.videos.filter { it.isSelected } }

    /** 当前可见视频数（与"全选"按钮对应） */
    fun getTotalVideos(): Int = getDisplayedVideoCount()

    /** 切换排序方式（会综合当前 filter 状态重新计算显示）。*/
    fun applySort(mode: SortMode) {
        currentSort = mode
        applyFilterAndSort()
    }

    /** 当前是否处于搜索过滤状态。 */
    fun hasActiveFilter(): Boolean = currentFilter.isNotEmpty()

    /** 当前过滤后显示的系列数。 */
    fun getDisplayedGroupCount(): Int = groups.size

    /** 当前过滤后显示的视频总数。 */
    fun getDisplayedVideoCount(): Int = groups.sumOf { it.videos.size }

    /** 当前过滤后显示的视频中已选中的数量。 */
    fun getDisplayedSelectedCount(): Int = groups.sumOf { g -> g.videos.count { it.isSelected } }

    private fun sortVideosInPlace(list: MutableList<BiliVideo>, mode: SortMode) {
        val sorted = when (mode) {
            SortMode.TIME_DESC -> list.sortedByDescending { it.lastModified }
            SortMode.TIME_ASC -> list.sortedBy { it.lastModified }
            SortMode.NAME_ASC -> list.sortedBy { it.partName.ifBlank { it.title } }
            SortMode.NAME_DESC -> list.sortedByDescending { it.partName.ifBlank { it.title } }
        }
        list.clear()
        list.addAll(sorted)
    }
}
