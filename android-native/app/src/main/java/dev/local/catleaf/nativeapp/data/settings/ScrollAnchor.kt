package dev.local.catleaf.nativeapp.data.settings

/**
 * 位置锚点（契约 §7）：列表位置用「稳定文章 id + 像素偏移」恢复，**不能只记数组序号**
 * （序号在筛选、刷新、加载更多之后会指向另一篇文章）。
 *
 * 正文位置多记一个内容指纹与内容高度：正文换过（例如点了「获取全文」）时按比例回退，
 * 而不是跳到一个毫不相干的位置。纯函数 + 单测集中在这里，因为格式要在 DataStore、
 * 界面恢复和测试三处保持一致。
 */
object ScrollAnchor {

    private const val LIST_SEPARATOR = '|'
    private const val READER_SEPARATOR = '\u0001'

    data class ListAnchor(val localId: String, val offset: Int)

    /**
     * @param scrollY 离开时的滚动位置（设备像素）
     * @param contentHeight 当时的文档高度（`WebView.contentHeight`，CSS 像素）。它与 scrollY 单位不同，
     *   所以只能用来算比例，不能和 scrollY 直接比较或夹取（上一版就是这么把位置截到文章中部的）
     */
    data class ReaderAnchor(
        val localId: String,
        val contentHash: String,
        val scrollY: Int,
        val contentHeight: Int,
    )

    fun encodeList(localId: String, offset: Int): String = "$localId$LIST_SEPARATOR$offset"

    fun decodeList(raw: String?): ListAnchor? {
        if (raw.isNullOrBlank()) return null
        val at = raw.lastIndexOf(LIST_SEPARATOR)
        if (at <= 0) return null
        val offset = raw.substring(at + 1).toIntOrNull() ?: return null
        return ListAnchor(raw.substring(0, at), offset)
    }

    fun encodeReader(localId: String, contentHash: String, scrollY: Int, contentHeight: Int): String =
        listOf(localId, contentHash, scrollY.toString(), contentHeight.toString()).joinToString("$READER_SEPARATOR")

    fun decodeReader(raw: String?): ReaderAnchor? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(READER_SEPARATOR)
        if (parts.size != 4) return null
        val scrollY = parts[2].toIntOrNull() ?: return null
        val height = parts[3].toIntOrNull() ?: return null
        return ReaderAnchor(parts[0], parts[1], scrollY, height)
    }

    /** 在当前列表里找锚点文章：找不到（被清掉或筛选变了）返回 null，界面从顶部显示 */
    fun indexOfArticle(localIds: List<String>, anchorLocalId: String): Int? =
        localIds.indexOfFirst { it == anchorLocalId }.takeIf { it >= 0 }

    /**
     * 正文恢复位置：按「位置 / 可滚动范围」的比例换算，换了一篇文章则从顶部开始。
     *
     * 为什么用比例而不是绝对像素：正文图片是异步取的，刚打开时文档比读完时矮，
     * 同一个绝对像素值落在完全不同的段落上（实测会跑到文章末尾）。比例在图片补进来、
     * 文档变高的过程中保持指向同一段文字。字号改变时同理，属于契约 §7 的“合理位置回退”。
     *
     * 注意单位：`scrollY` 是设备像素、`contentHeight` 是 CSS 像素。公式里位置只乘比值，
     * 所以结果仍是设备像素；**不要**用 contentHeight 去夹取 scrollY（早期版本那样做，
     * 把恢复位置截到了文章中部，实测才发现）。
     */
    fun readerScrollTarget(anchor: ReaderAnchor?, localId: String, currentContentHeight: Int): Int {
        if (anchor == null || anchor.localId != localId) return 0
        if (currentContentHeight <= 0 || anchor.contentHeight <= 0) return 0
        val ratio = anchor.scrollY.toFloat() / anchor.contentHeight
        return (ratio * currentContentHeight).toInt().coerceAtLeast(0)
    }
}