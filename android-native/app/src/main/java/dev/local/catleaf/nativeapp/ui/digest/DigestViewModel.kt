package dev.local.catleaf.nativeapp.ui.digest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.data.ai.DigestSummarizer
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.data.settings.currentScopeKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.util.TimeZone

enum class DigestStep { Scope, Result }

/** 范围预览里的一篇候选 */
data class DigestItemView(
    val localId: String,
    val title: String,
    val chars: Int,
    /** true = 正文；false = 仅摘要（预览逐项标记，契约 P05） */
    val hasBody: Boolean,
    val selected: Boolean,
)

data class DigestPointView(val text: String, val sourceIds: List<Int>, val topic: String = "")

data class DigestSourceView(val no: Int, val localId: String, val title: String, val link: String?)

data class DigestResultView(
    val title: String,
    val overview: String,
    val points: List<DigestPointView>,
    /** 冻结表：链接只从这张表生成；「查看范围」与来源跳转都用它 */
    val sources: List<DigestSourceView>,
    val generatedAt: Long,
    val fromCache: Boolean,
    /** 非 null = 结果不完整（临时展示，未写成功缓存），界面要与正常成功清楚区分 */
    val incompleteReason: String?,
)

data class DigestUiState(
    val loading: Boolean = true,
    val step: DigestStep = DigestStep.Scope,
    val feedTitle: String = "",
    /** 选定的设备本地日期 yyyy-MM-dd；null = 还没选（引导先选日期，不能提交） */
    val localDate: String? = null,
    val zoneLabel: String = "",
    val items: List<DigestItemView> = emptyList(),
    val selectedCount: Int = 0,
    val selectedChars: Int = 0,
    val overItems: Boolean = false,
    val overChars: Boolean = false,
    /** AI 端点主机与模型（提交前显示「会发送到哪、用什么模型」）；null = 未配置 */
    val aiHost: String? = null,
    val aiModel: String? = null,
    /** 已有该范围摘要、但本地集合在其生成后发生了变化：只提示，不自动重算（契约 5.3） */
    val newContentNotice: Boolean = false,
    val generating: Boolean = false,
    val notice: String? = null,
    val result: DigestResultView? = null,
) {
    val canGenerate: Boolean
        get() = !loading && localDate != null && selectedCount > 0 && !overItems && !overChars && !generating
}

/** 来源点击的去向（P05：点来源进已缓存文章；无法离线打开时给原因与原链接） */
sealed class SourceAction {
    data class OpenReader(val localId: String) : SourceAction()
    data class OpenExternal(val url: String) : SourceAction()
    data class Unavailable(val reason: String) : SourceAction()
}

/**
 * P05 日摘要：两步（范围预览 → 结果）。
 *
 * 归属规则与 ReaderViewModel 同款（[updateIfCurrent]）：**发起时冻结 namespace**
 * （实例|源|日期），回包到达时 namespace 变过（换日期/换源/换实例）就丢弃内存状态——
 * 结果只归属原任务，不写到用户后来切换的源/日期，也不自动重试计费。
 * 已经完整成功的结果在 Room 缓存里，回到原范围时优先打开，不浪费这次请求。
 */
class DigestViewModel @JvmOverloads constructor(
    application: Application,
    private val db: CatLeafDatabase = CatLeafDatabase.get(application),
) : AndroidViewModel(application) {
    companion object { const val ALL_FEEDS = "__all_subscriptions__" }

    private val settings = SettingsStore(application)

    private var summarizer: DigestSummarizer? = null

    private var instanceKey: String? = null
    private var feedId: String? = null
    private var baseUrl: String = ""
    private var model: String = ""

    /** 当前任务的归属键；与 generate 启动时快照不一致的回包一律丢弃 */
    private var token = ""

    private var allCandidates: List<DigestScope.Candidate> = emptyList()
    private var selectedIds: Set<String> = emptySet()
    private var rowsCache: List<DigestScope.Row> = emptyList()

    private val _state = MutableStateFlow(DigestUiState())
    val state: StateFlow<DigestUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { settings.snapshot.collect {
            if (instanceKey != null && settings.currentScopeKey() != instanceKey) {
                token = "scope-changed-${System.nanoTime()}"
                allCandidates = emptyList()
                selectedIds = emptySet()
                rowsCache = emptyList()
                _state.value = _state.value.copy(loading = false, generating = false, result = null,
                    items = emptyList(), selectedCount = 0, selectedChars = 0, overItems = false, overChars = false,
                    newContentNotice = false, notice = "使用范围已切换，请重新打开摘要页")
            }
        } }
    }

    /** 打开摘要页：源固定为进入时的当前源；日期由列表筛选带来（没带就先选日期） */
    fun open(newFeedId: String, seedDate: String?) {
        val opening = "opening-${System.nanoTime()}".also { token = it }
        viewModelScope.launch {
            val snapshot = settings.current()
            if (token != opening) return@launch
            // 范围键（契约 8.1）：本机=local:v1，实例=origin 哈希。AI 配置独立于实例（契约 8.3），
            // 只要有文章与有效 AI 配置，本机范围同样可生成摘要。空键=实例指针悬空（走修复）。
            instanceKey = settings.currentScopeKey().ifEmpty { null }
            feedId = newFeedId
            baseUrl = snapshot.aiBaseUrl.orEmpty()
            model = snapshot.aiModel.orEmpty()
            val key = instanceKey
            if (key == null) {
                _state.value = DigestUiState(loading = false, notice = "尚未连接实例，无法生成摘要")
                return@launch
            }
            val feedTitle = if (newFeedId == ALL_FEEDS) "全部订阅" else db.feeds().list(key).firstOrNull { it.feedId == newFeedId }?.title.orEmpty()
            if (token != opening || feedId != newFeedId || settings.currentScopeKey() != key) return@launch

            _state.value = DigestUiState(
                loading = false,
                feedTitle = feedTitle,
                zoneLabel = TimeZone.getDefault().id,
                aiHost = EndpointPolicy.originOf(baseUrl),
                aiModel = model.ifBlank { null },
            )
            applyDate(seedDate)
        }
    }

    /** 换日期：重算候选与勾选（默认全选），旧任务的在途回包从此失去归属 */
    fun setDate(date: String?) {
        viewModelScope.launch { applyDate(date) }
    }

    fun toggleSelected(localId: String) {
        if (_state.value.generating || _state.value.loading) return
        token = "selection-${System.nanoTime()}"
        val next = selectedIds.toMutableSet()
        if (!next.remove(localId)) next += localId
        selectedIds = next
        publishScopeState()
    }

    fun clearSelection() {
        if (_state.value.generating || _state.value.loading) return
        token = "selection-${System.nanoTime()}"
        selectedIds = emptySet()
        publishScopeState()
    }

    /** 结果页回范围预览：顺手重新核对一次「有新增内容」（本地查询，不发请求） */
    fun showScope() {
        if (_state.value.generating) return
        _state.value = _state.value.copy(step = DigestStep.Scope, result = null, notice = null)
        viewModelScope.launch { checkNewContent() }
    }

    /** @param force 手动「重新生成」：跳过完全匹配缓存，真的再请求一次（界面已说明会再次请求） */
    fun generate(force: Boolean = false) {
        val current = _state.value
        if (current.generating || current.loading) return
        val date = current.localDate ?: run {
            _state.value = current.copy(notice = "请先选择日期")
            return
        }
        val selection = DigestScope.selection(allCandidates, selectedIds)
        when {
            selection.selected.isEmpty() -> {
                _state.value = current.copy(notice = "没有勾选任何文章，不能提交")
                return
            }
            selection.overItems -> {
                _state.value = current.copy(notice = "已选 ${selection.selected.size} 篇，超过 ${DigestSummarizer.MAX_ITEMS} 篇上限：请取消勾选（不会自动截断）")
                return
            }
            selection.overChars -> {
                _state.value = current.copy(notice = "已选内容 ${selection.totalChars} 字符，超过 ${DigestSummarizer.MAX_CHARS} 字符上限：请取消勾选（不会自动截断）")
                return
            }
        }

        // 冻结：勾选集合按稳定顺序重新编号 1…N，文本随范围一起定死
        val sources = selection.selected.mapIndexed { i, c -> DigestSummarizer.Source(i + 1, c.localId, c.title, c.link) }
        val texts = selection.selected.map { it.text }
        val taskToken = token
        val taskScope = instanceKey.orEmpty()
        val taskFeed = feedId.orEmpty()
        val taskBase = baseUrl
        val taskModel = model
        _state.value = current.copy(generating = true, notice = null)

        viewModelScope.launch {
            val config = settings.current()
            if (token != taskToken || settings.currentScopeKey() != taskScope) return@launch
            if (config.aiBaseUrl != taskBase || config.aiModel != taskModel) {
                updateIfCurrent(taskToken) { it.copy(generating = false, notice = "AI配置已改变，请重新打开摘要页") }
                return@launch
            }
            val apiKey = runCatching { CredentialStore(getApplication()).getAiKey(taskBase) }
                .getOrNull().orEmpty()
            if (token != taskToken || settings.currentScopeKey() != taskScope) return@launch
            if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
                updateIfCurrent(taskToken) {
                    it.copy(generating = false, notice = "尚未配置 AI（设置 → AI 配置）")
                }
                return@launch
            }
            val request = DigestSummarizer.Request(
                instanceKey = taskScope,
                feedId = taskFeed,
                feedTitle = current.feedTitle,
                localDate = date,
                zoneId = TimeZone.getDefault().id,
                sources = sources,
                texts = texts,
                baseUrl = taskBase,
                model = taskModel,
                apiKey = apiKey,
            )
            val outcome = try { ensureSummarizer().summarize(request, bypassCache = force) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                updateIfCurrent(taskToken) { it.copy(generating = false, notice = "摘要暂不可用，原有结果已保留") }
                return@launch
            }
            when (outcome) {
                is DigestSummarizer.Outcome.Cached -> updateIfCurrent(taskToken) {
                    it.copy(
                        generating = false,
                        step = DigestStep.Result,
                        result = resultView(outcome.digest, outcome.sources, outcome.createdAt, fromCache = true, null),
                    )
                }

                is DigestSummarizer.Outcome.Fresh -> updateIfCurrent(taskToken) {
                    it.copy(
                        generating = false,
                        step = DigestStep.Result,
                        result = resultView(outcome.digest, sources, outcome.createdAt, fromCache = false, null),
                    )
                }

                // 不完整：有可展示内容就临时展示并说明原因（不写成功缓存）；没有就留在范围页
                is DigestSummarizer.Outcome.Incomplete -> updateIfCurrent(taskToken) {
                    if (outcome.digest == null) {
                        it.copy(generating = false, notice = outcome.reason + "；未自动重试")
                    } else {
                        it.copy(
                            generating = false,
                            step = DigestStep.Result,
                            result = resultView(outcome.digest, sources, System.currentTimeMillis(), false, outcome.reason),
                        )
                    }
                }

                is DigestSummarizer.Outcome.Blocked -> updateIfCurrent(taskToken) {
                    it.copy(generating = false, notice = outcome.reason)
                }

                is DigestSummarizer.Outcome.Failed -> updateIfCurrent(taskToken) {
                    it.copy(generating = false, notice = "摘要生成失败：${outcome.reason}")
                }
            }
        }
    }

    /** 点结果里的来源：进已缓存文章；文章不在了用冻结表里的原链接兜底；都没有就给原因 */
    fun resolveSource(no: Int, onResolved: (SourceAction) -> Unit) {
        val source = _state.value.result?.sources?.firstOrNull { it.no == no } ?: return
        val taskToken = token
        val scope = instanceKey ?: return
        viewModelScope.launch {
            val article = try { db.articles().get(source.localId) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isCurrent(taskToken, scope)) onResolved(SourceAction.Unavailable("无法读取本地原文，请稍后重试"))
                return@launch
            }
            if (!isCurrent(taskToken, scope)) return@launch
            val target = when {
                article?.instanceKey == scope -> SourceAction.OpenReader(source.localId)
                source.link != null -> SourceAction.OpenExternal(source.link)
                else -> SourceAction.Unavailable("原文已不在本地，且没有可用的安全链接")
            }
            onResolved(target)
        }
    }

    private suspend fun applyDate(date: String?) {
        val taskToken = "$instanceKey|$feedId|$date|${System.nanoTime()}".also { token = it }
        val scope = instanceKey ?: return
        val source = feedId?.takeUnless { it == ALL_FEEDS }
        allCandidates = emptyList()
        selectedIds = emptySet()
        rowsCache = emptyList()
        _state.value = _state.value.copy(localDate = date, loading = true, generating = false, result = null,
            step = DigestStep.Scope, items = emptyList(), selectedCount = 0, selectedChars = 0,
            overItems = false, overChars = false, newContentNotice = false, notice = null)
        val read = runCatching {
            withContext(Dispatchers.IO) {
                val day = date?.let(java.time.LocalDate::parse)
                val zone = java.time.ZoneId.systemDefault()
                val loaded = if (day == null) emptyList() else db.articles().listForDigest(scope, source,
                    day.atStartOfDay(zone).toInstant().toString(), day.plusDays(1).atStartOfDay(zone).toInstant().toString())
                loaded.map { entity ->
                    val link = entity.link?.takeIf { EndpointPolicy.isSafePublicUrl(it) }
                    val stored = db.articles().getContent(entity.localId)
                    DigestScope.Row(entity.localId, entity.title, entity.publishedAt, link,
                        stored?.content?.let { ArticleSanitizer.sanitize(it, link).plainText }?.ifBlank { null },
                        stored?.summary?.let { ArticleSanitizer.sanitize(it, link).plainText }?.ifBlank { null })
                }
            }
        }
        (read.exceptionOrNull() as? CancellationException)?.let { throw it }
        if (!isCurrent(taskToken, scope)) return
        if (read.isFailure) {
            _state.value = _state.value.copy(loading = false, notice = "无法读取本地摘要范围，请重试")
            return
        }
        rowsCache = read.getOrThrow()
        _state.value = _state.value.copy(loading = false, notice = null)
        allCandidates = DigestScope.candidates(rowsCache, date)
        selectedIds = allCandidates.map { it.localId }.toSet()
        publishScopeState()
        if (date == null) return

        // 完全匹配缓存优先打开（A06 同款：命中零新增请求）
        if (baseUrl.isNotBlank() && model.isNotBlank()) {
            val key = cacheKeyFor(allCandidates, date)
            if (key != null) {
                val row = try {
                    db.aiCache().get(key)?.takeIf { it.complete }
                        ?: db.aiCache().get(DigestSummarizer.legacyCacheKey(key))?.takeIf { it.complete }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (isCurrent(taskToken, scope)) _state.value = _state.value.copy(notice = "无法读取AI缓存，本次未请求供应商")
                    return
                }
                if (!isCurrent(taskToken, scope)) return
                if (row != null) {
                    val parsed = DigestSummarizer.parse(row.result)
                    val (digest, reason) = DigestSummarizer.validate(parsed, parsed.sources.size)
                    if (digest != null && reason == null && parsed.sources.isNotEmpty()) {
                        _state.value = _state.value.copy(
                            step = DigestStep.Result,
                            result = resultView(digest, parsed.sources, row.createdAt, fromCache = true, null),
                        )
                        return
                    }
                }
            }
        }
        checkNewContent()
    }

    /**
     * 「有新增内容」：同实例|源|日期|时区已有完整摘要，且其冻结表比当前候选**短**
     * （生成后又加载了该日文章）。只比条数、不比键——换模型/端点同样换键，
     * 但那不是"新增内容"，误报会误导用户再花一次钱。不自动重算（契约 5.3）。
     */
    private suspend fun checkNewContent() {
        val date = _state.value.localDate ?: return
        val taskToken = token
        val scope = instanceKey ?: return
        val prefix = "$instanceKey|$feedId|$date|${TimeZone.getDefault().id}|"
        val latest = try { db.aiCache().latestCompleteByPrefix(prefix) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (isCurrent(taskToken, scope)) _state.value = _state.value.copy(notice = "无法读取AI缓存，本次未请求供应商")
            return
        } ?: return
        if (!isCurrent(taskToken, scope)) return
        val cachedCount = DigestSummarizer.parse(latest.result).sources.size
        _state.value = _state.value.copy(newContentNotice = cachedCount < allCandidates.size)
    }

    private fun cacheKeyFor(candidates: List<DigestScope.Candidate>, date: String): String? {
        val instance = instanceKey ?: return null
        val sources = candidates.mapIndexed { i, c -> DigestSummarizer.Source(i + 1, c.localId, c.title, c.link) }
        return DigestSummarizer.cacheKey(
            DigestSummarizer.Request(
                instanceKey = instance,
                feedId = feedId.orEmpty(),
                feedTitle = _state.value.feedTitle,
                localDate = date,
                zoneId = TimeZone.getDefault().id,
                sources = sources,
                texts = candidates.map { it.text },
                baseUrl = baseUrl,
                model = model,
                apiKey = "", // 不参与键
            ),
        )
    }

    private fun publishScopeState() {
        val selection = DigestScope.selection(allCandidates, selectedIds)
        val ids = selectedIds
        _state.value = _state.value.copy(
            step = DigestStep.Scope,
            result = null,
            items = allCandidates.map {
                DigestItemView(it.localId, it.title, it.text.length, it.hasBody, it.localId in ids)
            },
            selectedCount = selection.selected.size,
            selectedChars = selection.totalChars,
            overItems = selection.overItems,
            overChars = selection.overChars,
            notice = null,
        )
    }

    private fun resultView(
        digest: DigestSummarizer.Digest,
        sources: List<DigestSummarizer.Source>,
        generatedAt: Long,
        fromCache: Boolean,
        incompleteReason: String?,
    ): DigestResultView = DigestResultView(
        title = digest.title,
        overview = digest.overview,
        points = digest.points.map { DigestPointView(it.text, it.sourceIds, it.topic) },
        sources = sources.map { DigestSourceView(it.no, it.localId, it.title, it.link) },
        generatedAt = generatedAt,
        fromCache = fromCache,
        incompleteReason = incompleteReason,
    )

    private fun ensureSummarizer(): DigestSummarizer {
        summarizer?.let { return it }
        return DigestSummarizer(dao = db.aiCache()).also { summarizer = it }
    }

    /** namespace 变过（换日期/换源/换实例）就丢弃这次回包：结果只归属发起时的任务 */
    private inline fun updateIfCurrent(expected: String, transform: (DigestUiState) -> DigestUiState) {
        if (token == expected) _state.value = transform(_state.value)
    }

    private suspend fun isCurrent(expected: String, scope: String): Boolean =
        settings.currentScopeKey() == scope && token == expected
}
