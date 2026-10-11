package dev.local.catleaf.nativeapp.ui.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dev.local.catleaf.nativeapp.data.ai.Translator
import dev.local.catleaf.nativeapp.data.ai.BilingualArticle
import dev.local.catleaf.nativeapp.data.article.ArticleIdentity
import dev.local.catleaf.nativeapp.data.article.ArticleMerger
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.article.FullTextExtractor
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.network.NetworkMonitor
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.ScrollAnchor
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.data.settings.currentScopeKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** 正文来源（契约 P04）：界面对三种来源要能区分显示 */
enum class ContentSource {
    /** User-frozen device-only package. */
    Offline,
    /** RSS 自带正文 */
    Rss,

    /** 用户点击「获取全文」后提取并保存的正文 */
    Extracted,

    /** 没有正文，只有摘要 */
    Summary,

    /** 既无正文也无摘要：只给「原网页」入口 */
    None,
}

data class ReaderUiState(
    val loading: Boolean = true,
    /** 本地找不到这篇文章（例如数据被清理） */
    val missing: Boolean = false,
    val title: String = "",
    val feedTitle: String = "",
    /** ISO-8601 UTC；null 显示“发布时间未知” */
    val publishedAt: String? = null,
    /** 已验证的原文链接；null 时禁用「原网页」，不合成地址 */
    val link: String? = null,
    /** 已净化的正文片段；null 表示当前没有任何可显示内容 */
    val bodyHtml: String? = null,
    val bodyPreview: String = "",
    /**
     * 文章封面（T22 F02：图片 enclosure/thumbnail 贯通展示）：
     * 已通过公开抓取核验的 https 地址；正文里已含同图时为 null（去重，不重复插）。
     */
    val coverUrl: String? = null,
    val source: ContentSource = ContentSource.None,
    val extracting: Boolean = false,
    /** 获取全文的失败原因或提示：显示后正文仍是原来的内容 */
    val notice: String? = null,
    /** 提取到的作者：只在本次会话显示，不新增数据库字段 */
    val byline: String? = null,
    /** 正文内容指纹：判断上次的阅读位置还能不能对得上（契约 §7） */
    val bodyHash: String = "",
    /** 正文字号 16/18/20/22（P07 阅读分组）：随设置即时变化 */
    val bodyFontSizeSp: Int = 18,
    /** 上次的正文阅读位置；只在属于当前文章时给出，由 WebView 在布局就绪后按内容高度换算 */
    val scrollAnchor: ScrollAnchor.ReaderAnchor? = null,
    /** 网络可用性（T22 V02）：离线时禁用需要网络的新操作并说明原因；已存正文/缓存译文照常可读 */
    val isOnline: Boolean = true,
    /** 可翻译的正文字符数（P04：翻译前先显示字符数与来源） */
    val translatableChars: Int = 0,
    /** 已有译文（缓存或本次生成）；null 表示还没有 */
    val translation: String? = null,
    /** Null only for readable legacy whole-text caches; new results carry verified block ids. */
    val translationSegments: Map<String, String>? = null,
    /** 显示的译文来自缓存（用于「AI 译文 · 已缓存」标识） */
    val translationCached: Boolean = false,
    /** true = 看译文，false = 看原文 */
    val showTranslation: Boolean = false,
    val translating: Boolean = false,
    /** 已读状态（数据库权威，界面只跟随） */
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    /** true 表示这篇只有书签、没有正文（P04/P06 要明确说明，不能假装可离线） */
    val bookmarkOnly: Boolean = false,
    /** 书签里还算有摘要可读：说明文案要区分「只存了摘要」与「只存了链接」 */
    val summarySaved: Boolean = false,
    /** 一次性的操作回执（收藏/未读）：显示后由界面 consume，不进数据库 */
    val feedback: ReaderFeedback? = null,
    val offlineSnapshot: OfflinePackageStore.Snapshot? = null,
    val savingOffline: Boolean = false,
) {
    /** 有正文可以离线读 */
    val hasText: Boolean get() = bodyHtml != null && source != ContentSource.None

    /** 有译文才能切换原文/译文（P04） */
    val canToggleTranslation: Boolean get() = !translation.isNullOrBlank()
}

/** 操作回执：文案 + 是否需要给「撤销」（P04：取消收藏可直接操作并提供短时撤销） */
data class ReaderFeedback(
    val text: String,
    val action: FeedbackAction = FeedbackAction.None,
)

enum class FeedbackAction { None, UndoUnstar }

/**
 * 是否该在正文成功显示后自动标已读（契约 P04）。
 *
 * 拆成纯函数是因为这条规则有个反直觉的地方：**用户手动设为未读之后，
 * 同一次打开的渲染不能再把它改回已读**（否则按钮像是坏了）。
 * 单独用 `manuallyUnread` 记住用户的选择，而不是靠“打开过就跳过”——
 * 后者会让用户离开再回来时永远读不到已读状态。
 */
internal fun shouldAutoMarkRead(
    bodyShown: Boolean,
    isRead: Boolean,
    manuallyUnread: Boolean,
): Boolean = bodyShown && !isRead && !manuallyUnread

/**
 * 详情页该不该插封面（T22 F02）：
 * - 只有安全可抓的 https 图片才用（与图片拦截同一规则，脏数据也进不了页面）；
 * - 封面始终放在页首；净化入口只移除正文开头的同图，保留中途图片。
 */
internal fun readerCoverUrl(thumbnail: String?): String? {
    val raw = thumbnail?.takeIf { it.isNotBlank() } ?: return null
    return EndpointPolicy.requirePublicFetchTarget(raw).getOrNull()
}

/**
 * P04 文章详情：T12 的安全阅读与全文兜底 + T13 的已读/收藏。
 *
 * 已读：正文成功显示后写一次 `isRead = true`；用户点「设为未读」后本次会话内不再自动改回。
 * 收藏：写盘成功（受影响行数 > 0）才更新界面与提示；失败明确说“尚未离线保存”。
 */
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val db: CatLeafDatabase = CatLeafDatabase.get(application),
    private val fullTextExtractor: FullTextExtractor? = null,
    private val translationService: Translator? = null,
) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val offlineStore = OfflinePackageStore(application, db)
    private var offlineJob: Job? = null
    private var offlineSaveScope: String? = null

    private var extractor: FullTextExtractor? = null
    private var extractorKey: String? = null
    private var extractorSecret: String? = null

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init {
        // 字号设置即时生效（P07）：开页/读文章都不用重进
        viewModelScope.launch {
            settings.snapshot.map { it.bodyFontSizeSp }
                .collect { size -> _state.value = _state.value.copy(bodyFontSizeSp = size) }
        }
        // 离线状态（T22 V02）：断网/回网即时反映到界面，动作在 VM 侧同样拦（根因防线）
        val monitor = NetworkMonitor(getApplication())
        viewModelScope.launch { monitor.online.collect { on ->
            _state.value = _state.value.copy(isOnline = on)
        } }
        _state.value = _state.value.copy(isOnline = monitor.isOnlineNow())
        viewModelScope.launch {
            settings.snapshot.collect { snap ->
                val key = if (dev.local.catleaf.nativeapp.data.settings.ActivityScopeRules.decideKind(
                    snap.activeScopeKind, snap.currentInstanceKey,
                ) == dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE) {
                    snap.currentInstanceKey.orEmpty()
                } else dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
                if (offlineSaveScope != null && offlineSaveScope != key) offlineJob?.cancel()
            }
        }
    }

    /** 当前显示的文章；回包只写回发起时的那一篇 */
    private var currentLocalId: String? = null
    private var currentScope: String? = null
    private var readerRevision = 0L

    /** 当前正文的可见字数：用于判断提取结果是否比原文还少 */
    private var currentTextLength: Int = 0

    /** 当前正文的纯文本：翻译的输入（契约 5.2：输入选当前已展示正文的纯文本） */
    private var currentPlainText: String = ""

    private var translator: Translator? = null

    /** 本机范围的直连全文提取器（无实例兜底），进程内复用 */
    private var localExtractor: FullTextExtractor? = null

    /**
     * 用户手动设为未读的文章：本次会话内不再自动标已读（P04）。
     * 进程重启后清空——那时重新打开这篇文章按其自然行为（显示正文→已读）。
     */
    private val manuallyUnread = mutableSetOf<String>()

    /**
     * 最近一次的正文阅读位置，只放内存：返回列表再进来要**马上**拿到它。
     * 写 DataStore 是异步的，紧接着读会读到上一份旧值（实测过），所以持久化那份只服务进程恢复。
     */
    private var lastKnownPosition: ScrollAnchor.ReaderAnchor? = null

    private suspend fun ensureExtractor(): FullTextExtractor? {
        fullTextExtractor?.let { return it }
        // 本机范围：直连提取独立工作（契约 8.3），无实例兜底
        if (settings.currentScopeKey() == dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY) {
            if (localExtractor == null) localExtractor = FullTextExtractor(null)
            return localExtractor
        }
        val key = settings.current().currentInstanceKey ?: return null
        val instance = db.instances().get(key) ?: return null
        val secret = runCatching {
            CredentialStore(getApplication()).get("instance_secret_$key")
        }.getOrNull() ?: return null
        if (key == extractorKey && secret == extractorSecret) extractor?.let { return it }
        extractorKey = key
        extractorSecret = secret
        return FullTextExtractor(FeedApi(instance.origin, secret)).also { extractor = it }
    }

    /** 打开一篇文章：正文与状态全部来自本地，不发网络请求 */
    fun open(localId: String) {
        val revision = ++readerRevision
        currentLocalId = localId
        currentScope = null
        currentPlainText = ""
        currentTextLength = 0
        _state.value = ReaderUiState(
            bodyFontSizeSp = _state.value.bodyFontSizeSp,
            isOnline = _state.value.isOnline,
        )
        // 「避开当前正文」（契约 4.3）：淘汰/清缓存都不得删正在读的这篇
        dev.local.catleaf.nativeapp.data.local.CurrentBody.localId = localId
        viewModelScope.launch {
            val scope = settings.currentScopeKey()
            if (readerRevision != revision) return@launch
            currentScope = scope
            val article = db.articles().get(localId)?.takeIf { it.instanceKey == scope }
            if (article == null) {
                if (currentLocalId == localId) {
                    dev.local.catleaf.nativeapp.data.local.CurrentBody.localId = null
                    if (settings.currentScopeKey() == scope) _state.value = _state.value.copy(loading = false, missing = true)
                }
                return@launch
            }
            // 读到即"最近访问"：否则预算会先淘汰用户刚翻过的文章（T18）
            db.articles().touchContent(localId, System.currentTimeMillis())

            val link = article.link?.takeIf { EndpointPolicy.isSafePublicUrl(it) }
            val stored = db.articles().getContent(localId)
            val offline = offlineStore.load(article.instanceKey, localId)

            // 所有正文都经同一入口净化：RSS 正文、提取结果、摘要都一样
            val coverUrl = offline?.coverUrl ?: readerCoverUrl(stored?.thumbnail)
            val content = ArticleSanitizer.sanitize(stored?.content, link, listOf(article.title), coverUrl)
            val summary = ArticleSanitizer.sanitize(stored?.summary, link, listOf(article.title))

            val source = when {
                offline != null -> ContentSource.Offline
                !content.isEmpty && stored?.contentSource == SOURCE_EXTRACTED -> ContentSource.Extracted
                !content.isEmpty -> ContentSource.Rss
                !summary.isEmpty -> ContentSource.Summary
                else -> ContentSource.None
            }
            val body = when {
                offline != null -> ArticleSanitizer.sanitize(offline.bodyHtml, link, listOf(article.title), coverUrl).html
                !content.isEmpty -> content.html
                !summary.isEmpty -> summary.html
                else -> null
            }
            val plainText = ArticleSanitizer.sanitize(body, link).plainText

            // 「仅书签」= 没有真正保存下来的正文（只有摘要也算），P04/P06 都要说清楚
            val bookmarkOnly = content.isEmpty && offline == null

            var isRead = article.isRead
            if (shouldAutoMarkRead(body != null, isRead, localId in manuallyUnread)) {
                // 受影响行数为 0 说明文章已不在库里：不假装已读
                if (db.articles().setRead(localId, isRead = true, now = System.currentTimeMillis()) > 0) {
                    isRead = true
                }
            }

            // 上次读到的位置：同一篇且内容指纹一致就回到原处，内容换过则按比例回退
            val bodyHash = ArticleIdentity.contentFingerprint(body)
            val savedPosition = (lastKnownPosition ?: ScrollAnchor.decodeReader(settings.current().readerPosition))
                ?.takeIf { body != null && it.localId == localId }

            // 封面：来自解析时已核验的图片附件；正文含同图则不重复插（T22 F02）
            // 已有缓存就先把切换准备好：这是本地查询，不发请求（A06：相同输入零新增请求）
            val cached = cachedTranslation(localId, plainText, body, article.title)
            if (!isCurrent(localId, scope, revision)) return@launch
            currentTextLength = plainText.length
            currentPlainText = plainText
            _state.value = ReaderUiState(
                loading = false,
                bodyFontSizeSp = _state.value.bodyFontSizeSp,
                translation = cached?.text,
                translationSegments = cached?.segments,
                translationCached = cached != null,
                translatableChars = currentPlainText.length,
                title = article.title,
                feedTitle = article.feedTitle,
                publishedAt = article.publishedAt,
                link = link,
                bodyHtml = body,
                bodyPreview = currentPlainText.take(120),
                coverUrl = coverUrl,
                source = source,
                isRead = isRead,
                isStarred = article.isStarred,
                bookmarkOnly = bookmarkOnly,
                summarySaved = !summary.isEmpty,
                bodyHash = bodyHash,
                scrollAnchor = savedPosition,
                // 新建整份 state 时保留运行中的网络态（T22 V02），否则断网中重开会被重置成在线
                isOnline = _state.value.isOnline,
                offlineSnapshot = offline,
                savingOffline = offlineJob?.isActive == true && offlineSaveScope == article.instanceKey,
            )
        }
    }

    /** Only a confirmed single-article action starts network image requests. */
    fun saveOffline(includeImages: Boolean) {
        val targetId = currentLocalId ?: return
        val snapshot = _state.value
        if (snapshot.savingOffline || snapshot.bookmarkOnly) return
        offlineJob = viewModelScope.launch {
            val scope = settings.currentScopeKey()
            offlineSaveScope = scope
            updateIfCurrent(targetId) { it.copy(savingOffline = true, notice = "正在保存离线副本") }
            val result = offlineStore.save(scope, targetId, includeImages)
            offlineSaveScope = null
            if (settings.currentScopeKey() != scope) return@launch
            val message = result.fold(
                onSuccess = { pkg -> when (pkg.status) {
                    "text" -> "仅正文已保存"
                    "complete" -> "所选内容已保存（${pkg.imageSaved}/${pkg.imageTotal} 张图片）"
                    else -> "正文已保存，图片未齐（${pkg.imageSaved}/${pkg.imageTotal}）"
                } },
                onFailure = { "离线保存未完成：${it.message ?: "请检查存储空间或网络"}" },
            )
            if (currentLocalId == targetId) {
                val saved = offlineStore.load(scope, targetId)
                updateIfCurrent(targetId) { current -> current.copy(
                    savingOffline = false, notice = message, offlineSnapshot = saved,
                    bodyHtml = saved?.bodyHtml ?: current.bodyHtml,
                    bodyPreview = saved?.record?.searchBodyText?.take(120) ?: current.bodyPreview,
                    coverUrl = saved?.coverUrl ?: current.coverUrl,
                    bookmarkOnly = if (saved != null) false else current.bookmarkOnly,
                    bodyHash = saved?.record?.bodyHash ?: current.bodyHash,
                    source = if (saved != null) ContentSource.Offline else current.source,
                ) }
            }
        }
    }

    fun cancelOffline() {
        offlineJob?.cancel()
        val targetId = currentLocalId ?: return
        updateIfCurrent(targetId) { it.copy(savingOffline = false, notice = "已取消后续离线请求，已写入的内容会在下次打开时核对") }
    }

    fun retryOfflineImages() {
        val targetId = currentLocalId ?: return
        if (_state.value.savingOffline) return
        offlineJob = viewModelScope.launch {
            val scope = settings.currentScopeKey()
            offlineSaveScope = scope
            updateIfCurrent(targetId) { it.copy(savingOffline = true, notice = "正在补存未完成的图片") }
            val result = offlineStore.retryMissing(scope, targetId)
            offlineSaveScope = null
            if (settings.currentScopeKey() != scope) return@launch
            val saved = offlineStore.load(scope, targetId)
            updateIfCurrent(targetId) { it.copy(
                savingOffline = false, offlineSnapshot = saved,
                notice = result.fold(
                    { pkg -> "图片已保存 ${pkg.imageSaved}/${pkg.imageTotal} 张" },
                    { error -> "重试未完成：${error.message ?: "请检查网络和空间"}" },
                ),
            ) }
        }
    }

    /** 手动设为未读（P04）：本次会话内不会被自动标已读覆盖 */
    fun setUnread() {
        val targetId = currentLocalId ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val changed = db.articles().setRead(targetId, isRead = false, now = now)
            if (changed <= 0) {
                updateIfCurrent(targetId) { it.copy(feedback = ReaderFeedback("设置未读失败：这篇文章已不在本地")) }
                return@launch
            }
            manuallyUnread += targetId
            updateIfCurrent(targetId) { it.copy(isRead = false, feedback = ReaderFeedback("已设为未读")) }
        }
    }

    /** 手动标为已读（设为未读的逆操作，避免用户在同一次会话里无路可回） */
    fun setRead() {
        val targetId = currentLocalId ?: return
        viewModelScope.launch {
            val changed = db.articles().setRead(targetId, isRead = true, now = System.currentTimeMillis())
            if (changed <= 0) {
                updateIfCurrent(targetId) { it.copy(feedback = ReaderFeedback("设置已读失败：这篇文章已不在本地")) }
                return@launch
            }
            manuallyUnread -= targetId
            updateIfCurrent(targetId) { it.copy(isRead = true, feedback = ReaderFeedback("已标为已读")) }
        }
    }

    /**
     * 收藏 / 取消收藏（P04）：**写盘成功才改界面**。
     * 取消收藏可直接操作，并给一次「撤销」（撤销会恢复原来的收藏时间，列表顺序不变）。
     */
    fun toggleStar() {
        val targetId = currentLocalId ?: return
        val snapshot = _state.value
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (snapshot.isStarred) {
                val previousStarredAt = db.articles().get(targetId)?.starredAt
                val changed = db.articles().setStarred(targetId, starred = false, starredAt = null, now = now)
                if (changed <= 0) {
                    updateIfCurrent(targetId) { it.copy(feedback = ReaderFeedback(SAVE_FAILED)) }
                    return@launch
                }
                lastUnstarred = targetId to previousStarredAt
                updateIfCurrent(targetId) {
                    it.copy(isStarred = false, feedback = ReaderFeedback("已取消收藏", FeedbackAction.UndoUnstar))
                }
            } else {
                val changed = db.articles().setStarred(targetId, starred = true, starredAt = now, now = now)
                if (changed <= 0) {
                    updateIfCurrent(targetId) { it.copy(feedback = ReaderFeedback(SAVE_FAILED)) }
                    return@launch
                }
                val text = if (snapshot.bookmarkOnly) {
                    "已收藏为书签：这次没有可离线保存的正文"
                } else {
                    "已收藏，已保存文本可离线阅读"
                }
                updateIfCurrent(targetId) { it.copy(isStarred = true, feedback = ReaderFeedback(text)) }
            }
        }
    }

    /** 撤销取消收藏：恢复原来的收藏时间，列表顺序不会因为一次误触而改变 */
    fun undoUnstar() {
        val (localId, starredAt) = lastUnstarred ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val changed = db.articles().setStarred(
                localId,
                starred = true,
                starredAt = starredAt ?: now,
                now = now,
            )
            if (changed <= 0) {
                updateIfCurrent(localId) { it.copy(feedback = ReaderFeedback(SAVE_FAILED)) }
                return@launch
            }
            lastUnstarred = null
            updateIfCurrent(localId) {
                it.copy(isStarred = true, feedback = ReaderFeedback("已恢复收藏"))
            }
        }
    }

    /**
     * 翻译为简体中文（P04：请求时原文继续可读、禁止重复提交；失败/取消都不覆盖已有译文）。
     * [force] = 用户点了「重新翻译」，明确会再请求一次（可能计费）。
     */
    fun translate(force: Boolean = false) {
        val targetId = currentLocalId ?: return
        val snapshot = _state.value
        val scope = currentScope ?: return
        val revision = readerRevision
        val sourceText = currentPlainText
        if (snapshot.translating) return
        if (!force && snapshot.canToggleTranslation) {
            // 已经有译文：切过去就行，不再请求（离线也可用，T22 V02）
            _state.value = snapshot.copy(showTranslation = true)
            return
        }
        // 需要新请求的分支才受网络约束：缓存命中在 Translator 内部先行返回
        if (!snapshot.isOnline) {
            _state.value = snapshot.copy(notice = "离线：可继续阅读已保存的内容；新的翻译要联网后进行。已有译文仍可查看。")
            return
        }

        _state.value = snapshot.copy(translating = true, notice = null)
        viewModelScope.launch {
            val policy = ensureAiConfig()
            val translator = ensureTranslator()
            if (!isCurrent(targetId, scope, revision)) return@launch
            if (policy == null || translator == null) {
                updateIfCurrent(targetId) {
                    it.copy(translating = false, notice = "尚未配置 AI（设置 → AI 配置）")
                }
                return@launch
            }
            val request = Translator.Request(
                instanceKey = policy.instanceKey,
                localId = targetId,
                sourceText = sourceText,
                baseUrl = policy.baseUrl,
                model = policy.model,
                apiKey = policy.apiKey,
                article = BilingualArticle.fromHtml(snapshot.bodyHtml.orEmpty(), snapshot.title),
            )

            // force = 手动「重新翻译」：跳过缓存、真的再请求一次
            val outcome = translator.translate(request, bypassCache = force)
            if (!isCurrent(targetId, scope, revision)) return@launch
            when (outcome) {
                is Translator.Outcome.Cached -> updateIfCurrent(targetId) {
                    it.copy(
                        translating = false,
                        translation = outcome.text,
                        translationSegments = outcome.segments,
                        translationCached = true,
                        showTranslation = true,
                    )
                }

                is Translator.Outcome.Fresh -> updateIfCurrent(targetId) {
                    it.copy(
                        translating = false,
                        translation = outcome.text,
                        translationSegments = outcome.segments,
                        translationCached = false,
                        showTranslation = true,
                    )
                }

                // 不完整的段落对应不能显示，更不能替换先前的完整译文。
                is Translator.Outcome.Incomplete -> updateIfCurrent(targetId) {
                    it.copy(
                        translating = false,
                        notice = outcome.reason,
                    )
                }

                is Translator.Outcome.Blocked -> updateIfCurrent(targetId) {
                    it.copy(translating = false, notice = outcome.reason)
                }

                is Translator.Outcome.Failed -> updateIfCurrent(targetId) {
                    it.copy(translating = false, notice = "翻译失败：${outcome.reason}")
                }
            }
        }
    }

    /** 原文 / 译文切换（P04：翻译成功后显示切换） */
    fun showOriginal() {
        _state.value = _state.value.copy(showTranslation = false)
    }

    fun showTranslation() {
        _state.value = _state.value.copy(showTranslation = true)
    }

    private suspend fun cachedTranslation(localId: String, plainText: String, body: String?, title: String): Translator.Outcome.Cached? {
        val config = ensureAiConfig() ?: return null
        return ensureTranslator()?.cached(Translator.Request(
            instanceKey = config.instanceKey,
            localId = localId,
            sourceText = plainText,
            baseUrl = config.baseUrl,
            model = config.model,
            apiKey = config.apiKey,
            article = BilingualArticle.fromHtml(body.orEmpty(), title),
        ))
    }

    private fun ensureTranslator(): Translator? {
        translationService?.let { return it }
        translator?.let { return it }
        return Translator(dao = db.aiCache()).also { translator = it }
    }

    /** 任务开始时冻结的 AI 配置（契约 5.1：端点与模型随任务冻结；契约 8.3：AI 独立于实例） */
    private suspend fun ensureAiConfig(): AiConfig? {
        // 范围键进缓存键（契约 8.1：AI 结果按范围隔离）；空=实例指针悬空（走修复，不给假键）
        val key = settings.currentScopeKey().ifEmpty { return null }
        val snapshot = settings.current()
        val baseUrl = snapshot.aiBaseUrl.orEmpty()
        val model = snapshot.aiModel.orEmpty()
        if (baseUrl.isBlank() || model.isBlank()) return null
        val apiKey = runCatching { CredentialStore(getApplication()).get(CredentialStore.AI_KEY) }
            .getOrNull()
            .orEmpty()
        if (apiKey.isBlank()) return null
        return AiConfig(key, baseUrl, model, apiKey)
    }

    private data class AiConfig(
        val instanceKey: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
    )

    /**
     * 记下正文滚动位置（离开详情时回报）。只留最近一篇：够用且不会无限增长
     * （契约 §7 要的是「记录文章 id、内容指纹与滚动位置」，不是每篇都留档）。
     */
    fun rememberScrollPosition(localId: String, scrollY: Int, contentHeight: Int) {
        if (currentLocalId != localId) return
        val hash = _state.value.bodyHash + if (_state.value.showTranslation && _state.value.translationSegments != null) ":bilingual" else ""
        if (contentHeight <= 0) return
        val anchor = ScrollAnchor.ReaderAnchor(localId, hash, scrollY, contentHeight)
        lastKnownPosition = anchor
        viewModelScope.launch {
            settings.setReaderPosition(
                ScrollAnchor.encodeReader(localId, hash, scrollY, contentHeight),
            )
        }
    }

    fun consumeFeedback() {
        _state.value = _state.value.copy(feedback = null)
    }

    /**
     * 按需获取全文。失败**不动**已有正文，只把原因放到提示里（契约 P04）。
     */
    fun fetchFullText() {
        val snapshot = _state.value
        val scope = currentScope ?: return
        val revision = readerRevision
        val textLength = currentTextLength
        if (snapshot.extracting) return
        val targetId = currentLocalId ?: return
        if (!snapshot.isOnline) {
            // T22 V02：离线时明确说明而不是发注定失败的请求
            _state.value = snapshot.copy(notice = "离线：正在显示已保存的内容；获取全文需要联网。")
            return
        }
        val link = snapshot.link ?: run {
            _state.value = snapshot.copy(notice = "这篇没有可用的原文链接，无法获取全文")
            return
        }

        _state.value = snapshot.copy(extracting = true, notice = null)
        viewModelScope.launch {
            val extractor = ensureExtractor()
            if (!isCurrent(targetId, scope, revision)) return@launch
            if (extractor == null) {
                val notice = if (settings.currentScopeKey() == dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY) {
                    // 本机范围：实例兜底不存在；本机直连抓取由后续任务接入（契约 8.3）
                    "本机模式暂无法在线获取全文，已保留现有内容"
                } else {
                    "尚未连接实例，无法获取全文"
                }
                updateIfCurrent(targetId) { it.copy(extracting = false, notice = notice) }
                return@launch
            }

            val outcome = extractor.extract(link, snapshot.title, textLength)
            if (!isCurrent(targetId, scope, revision)) return@launch
            when (outcome) {
                is FullTextExtractor.Outcome.Extracted -> {
                    val oldCover = runCatching { db.articles().getContent(targetId)?.thumbnail }.getOrNull()
                    val cover = readerCoverUrl(oldCover) ?: outcome.thumbnail
                    val sanitized = ArticleSanitizer.sanitize(outcome.html, link, listOfNotNull(snapshot.title, outcome.title), cover)
                    if (sanitized.isEmpty) {
                        updateIfCurrent(targetId) {
                            it.copy(extracting = false, notice = "提取到的正文为空，已保留原正文")
                        }
                        return@launch
                    }
                    if (!isCurrent(targetId, scope, revision)) return@launch
                    val saved = saveExtracted(targetId, scope, revision, sanitized.html, cover)
                    if (!isCurrent(targetId, scope, revision)) return@launch
                    if (!saved) {
                        // 腾空间失败（T18）：旧正文原样保留，这次提取结果不写
                        updateIfCurrent(targetId) {
                            it.copy(extracting = false, notice = "获取全文未保存：本地写入失败，已保留原正文")
                        }
                        return@launch
                    }
                    if (!isCurrent(targetId, scope, revision)) return@launch
                    val appliedRevision = ++readerRevision
                    currentPlainText = sanitized.plainText
                    currentTextLength = sanitized.textLength
                    val cached = cachedTranslation(targetId, sanitized.plainText, sanitized.html, snapshot.title)
                    if (!isCurrent(targetId, scope, appliedRevision)) return@launch
                    updateIfCurrent(targetId) {
                        it.copy(
                            extracting = false,
                            notice = null,
                            bodyHtml = sanitized.html,
                            bookmarkOnly = false,
                            bodyPreview = sanitized.plainText.take(120),
                            translatableChars = sanitized.plainText.length,
                            translation = cached?.text,
                            translationSegments = cached?.segments,
                            translationCached = cached != null,
                            showTranslation = cached != null && it.showTranslation,
                            translating = false,
                            offlineSnapshot = null,
                            bodyHash = ArticleIdentity.contentFingerprint(sanitized.html),
                            // 页首封面与正文开头的同图已在净化时去重。
                            coverUrl = cover,
                            source = ContentSource.Extracted,
                            byline = outcome.byline,
                        )
                    }
                }

                is FullTextExtractor.Outcome.Failed -> updateIfCurrent(targetId) {
                    it.copy(extracting = false, notice = "获取全文失败：${outcome.reason}。已保留原正文")
                }
            }
        }
    }

    /**
     * 写回提取结果：整行 upsert 前先读回旧行，保留摘要与缩略图，别把已存内容抹掉。
     * 契约 4.3：为新写入腾空间失败时**保留旧内容**——返回 false，调用方提示并维持原正文。
     */
    private suspend fun saveExtracted(localId: String, scope: String, revision: Long, html: String, thumbnail: String?): Boolean = runCatching {
        val cleanup = dev.local.catleaf.nativeapp.data.local.CacheCleanup(db, getApplication())
        cleanup.enforceBudget(keepLocalIds = setOf(localId))
        db.withTransaction {
            check(db.articles().get(localId)?.instanceKey == scope && isCurrent(localId, scope, revision))
            val old = db.articles().getContent(localId)
            db.articles().upsertContent(
                ArticleContentEntity(
                    localId = localId,
                    content = html,
                    summary = old?.summary,
                    thumbnail = thumbnail,
                    contentSource = SOURCE_EXTRACTED,
                    contentHash = ArticleIdentity.contentFingerprint(html),
                    searchText = dev.local.catleaf.nativeapp.data.article.ArticleFilter
                        .summaryPreview(old?.summary ?: html, limit = 500),
                    searchBodyText = dev.local.catleaf.nativeapp.data.article.SearchQuery.bodyText(html),
                    lastAccessedAt = System.currentTimeMillis(),
                ),
            )
        }
    }.isSuccess

    private suspend fun isCurrent(localId: String, scope: String, revision: Long): Boolean =
        settings.currentScopeKey() == scope && currentLocalId == localId && readerRevision == revision && currentScope == scope

    /** 切换过文章就丢弃旧回包：不能把上一篇的提取结果写到当前这一篇 */
    private inline fun updateIfCurrent(localId: String, transform: (ReaderUiState) -> ReaderUiState) {
        if (currentLocalId == localId) _state.value = transform(_state.value)
    }

    /** 最近一次取消收藏（文章 + 原收藏时间），供「撤销」恢复 */
    private var lastUnstarred: Pair<String, Long?>? = null

    private companion object {
        const val SOURCE_EXTRACTED = ArticleMerger.SOURCE_EXTRACTED
        const val SAVE_FAILED = "收藏未保存：本地写入失败，请不要把它当成已离线保存"
    }
}
