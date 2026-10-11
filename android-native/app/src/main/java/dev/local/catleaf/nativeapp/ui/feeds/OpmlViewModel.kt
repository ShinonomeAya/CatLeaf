package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.data.opml.Opml
import dev.local.catleaf.nativeapp.data.opml.OpmlImporter
import dev.local.catleaf.nativeapp.data.opml.skipReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** OPML 预览/导入/导出的界面状态。 */
data class OpmlUiState(
    val parsing: Boolean = false,
    val document: Opml.Document? = null,
    /** 被勾选的条目在 [Opml.Document.entries] 中的下标 */
    val selected: Set<Int> = emptySet(),
    val importing: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val report: OpmlImporter.Report? = null,
    val errorMessage: String? = null,
    val exportMessage: String? = null,
) {
    val selectedEntries: List<Opml.Entry>
        get() = document?.entries?.filterIndexed { i, _ -> i in selected } ?: emptyList()
}

class OpmlViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(OpmlUiState())
    val state: StateFlow<OpmlUiState> = _state.asStateFlow()

    private var importer: OpmlImporter? = null
    private var registry: dev.local.catleaf.nativeapp.data.FeedRegistry? = null
    private var importTicket: Any? = null

    /** 由界面在打开时注入（实例或本机的 registry/importer），避免这里重复解析范围配置 */
    fun attach(registry: dev.local.catleaf.nativeapp.data.FeedRegistry, importer: OpmlImporter) {
        if (this.registry != null && this.registry !== registry) {
            importTicket = null
            _state.value = OpmlUiState()
        }
        this.registry = registry
        this.importer = importer
    }

    /**
     * 读取并解析用户选中的 OPML。解析与校验都在**写入前**完成（契约 §6）：
     * 超限、含 DOCTYPE/实体、非 HTTPS 条目都会在这里就给出结论。
     */
    fun loadDocument(uri: Uri) {
        val registryRef = registry
        viewModelScope.launch {
            _state.value = OpmlUiState(parsing = true)
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                if (registry !== registryRef) return@launch
                _state.value = OpmlUiState(errorMessage = "无法读取所选文件")
                return@launch
            }

            // 远端已有源参与去重；读不到时仍允许预览，只是不标远端重复
            val known = withContext(Dispatchers.IO) {
                runCatching { registryRef?.local()?.map { it.url } }.getOrNull().orEmpty()
            }
            if (registry !== registryRef) return@launch

            Opml.parse(bytes, knownUrls = known).fold(
                onSuccess = { doc ->
                    // 默认勾选“有效且未重复”的条目
                    val preselect = doc.entries.indices.filter { doc.entries[it].selectable }.toSet()
                    _state.value = OpmlUiState(document = doc, selected = preselect)
                },
                onFailure = { e ->
                    _state.value = OpmlUiState(errorMessage = e.message ?: "解析失败")
                },
            )
        }
    }

    fun toggle(index: Int) {
        val doc = _state.value.document ?: return
        if (!doc.entries[index].selectable) return
        val next = _state.value.selected.toMutableSet()
        if (!next.add(index)) next.remove(index)
        _state.value = _state.value.copy(selected = next)
    }

    fun selectAll() {
        val doc = _state.value.document ?: return
        val all = doc.entries.indices.filter { doc.entries[it].selectable }.toSet()
        val everySelected = all.isNotEmpty() && all.all { it in _state.value.selected }
        _state.value = _state.value.copy(selected = if (everySelected) emptySet() else all)
    }

    fun startImport() {
        val entries = _state.value.selectedEntries
        val imp = importer
        if (entries.isEmpty() || imp == null || _state.value.importing) return

        _state.value = _state.value.copy(
            importing = true,
            progressDone = 0,
            progressTotal = entries.size,
            errorMessage = null,
        )
        val ticket = Any().also { importTicket = it }
        val registryRef = registry

        viewModelScope.launch {
            val report = imp.import(
                entries = entries,
                onProgress = { _, done, total ->
                    if (importTicket === ticket) _state.value = _state.value.copy(progressDone = done, progressTotal = total)
                },
                isCancelled = { importTicket !== ticket },
            )
            withContext(Dispatchers.IO) { runCatching { registryRef?.refresh() } }
            if (importTicket !== ticket) return@launch
            _state.value = _state.value.copy(importing = false, report = report.withSkipped(skippedEntries))
        }
    }

    /** 预览里未进入导入的条目（无效/重复/未勾选）：结果页按原因计入“跳过”，不发请求 */
    private val skippedEntries: List<Opml.Entry>
        get() = _state.value.document?.entries.orEmpty()
            .filterIndexed { i, _ -> i !in _state.value.selected }

    private fun OpmlImporter.Report.withSkipped(entries: List<Opml.Entry>) = OpmlImporter.Report(
        items + entries.map { e -> OpmlImporter.ItemResult(e.title, e.xmlUrl, e.group, OpmlImporter.Outcome.SkippedInvalid(e.skipReason())) },
    )

    /** 重试失败或待确认条目：先核对远端列表再决定关联或新增（契约 §6） */
    fun retryUnresolved() {
        val report = _state.value.report ?: return
        val imp = importer ?: return
        if (_state.value.importing) return

        _state.value = _state.value.copy(importing = true)
        val ticket = Any().also { importTicket = it }
        val registryRef = registry
        viewModelScope.launch {
            val retried = imp.retryUnresolved(report, isCancelled = { importTicket !== ticket })
            withContext(Dispatchers.IO) { runCatching { registryRef?.refresh() } }
            if (importTicket !== ticket) return@launch
            _state.value = _state.value.copy(importing = false, report = retried)
        }
    }

    fun dismiss() {
        importTicket = null
        _state.value = OpmlUiState()
    }

    /**
     * 导出：写到应用私有 cache 下的 exports/，再交给系统分享/保存。
     * 文件里只有订阅与分组，不含任何密钥（契约 §6）。
     */
    fun buildExportIntent(feeds: List<Opml.ExportFeed>, fileName: String = "catleaf-subscriptions.opml"): Intent? {
        val context = getApplication<Application>()
        return runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(Opml.write(feeds), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/x-opml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "CatLeaf 订阅")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrElse {
            _state.value = _state.value.copy(errorMessage = "导出失败：${it.message}")
            null
        }
    }

    fun reportExportShared(count: Int) {
        _state.value = _state.value.copy(exportMessage = "已导出 $count 个订阅（仅订阅与分组，不含密钥）")
    }

    fun clearExportMessage() {
        _state.value = _state.value.copy(exportMessage = null)
    }
}

/** 便于界面对照的提示文案（契约 §6 的私密性提醒） */
fun Context.opmlPrivacyHint(): String = "导出仅含订阅与分组；订阅地址可能自带访问 token，文件请私密保存。"
