package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.data.FeedRegistry
import dev.local.catleaf.nativeapp.data.FeedStore
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.describeVerifyError
import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.network.FeedApiError
import dev.local.catleaf.nativeapp.data.opml.OpmlImporter
import dev.local.catleaf.nativeapp.data.settings.ScopeKind
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FeedManagerUiState(
    val loading: Boolean = false,
    val feeds: List<FeedEntity> = emptyList(),
    /** 正在处理的源 id（删除/重试中），用于禁用重复操作 */
    val busyFeedId: String? = null,
    /** 顶部一次性提示（新增结果、失败原因） */
    val message: String? = null,
    val errorMessage: String? = null,
    /** 本机范围（契约 8.2）：只读展示本机镜像，增删改/验证/OPML 入口隐藏（本机订阅任务接入后开放） */
    val localOnly: Boolean = false,
) {
    val groupCount: Int get() = feeds.map { it.category }.distinct().size

    /** 按分组聚合，空分组名显示为“未分组”（契约 3.2：category 为空时未分组） */
    fun grouped(): List<Pair<String, List<FeedEntity>>> =
        feeds.groupBy { it.category.ifBlank { UNGROUPED } }
            .toList()
            .sortedBy { it.first }

    companion object {
        const val UNGROUPED = "未分组"
    }
}

/** 添加/编辑表单状态。编辑时 URL 只读（契约：编辑不能换地址） */
data class FeedFormState(
    val editing: Boolean = false,
    val feedId: String = "",
    val url: String = "",
    val customTitle: String = "",
    val category: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
) {
    /** URL 形状预校验（L06）：只放行可解析的 HTTPS 地址，表单不再对任意非空串发 HTTP */
    val urlValid: Boolean
        get() = dev.local.catleaf.nativeapp.data.network.EndpointPolicy.normalizeFeedUrl(url) != null

    val canSubmit: Boolean
        get() = urlValid && !submitting &&
            customTitle.length <= MAX_NAME && category.length <= MAX_CATEGORY

    companion object {
        const val MAX_NAME = 120
        const val MAX_CATEGORY = 80
    }
}

class FeedManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CatLeafDatabase.get(application)
    private val settings = SettingsStore(application)

    private var store: FeedStore? = null
    private var localStore: dev.local.catleaf.nativeapp.data.LocalFeedStore? = null
    private var api: FeedApi? = null
    private var instanceKey: String? = null
    private var instanceSecret: String? = null

    private val scopeResolver = dev.local.catleaf.nativeapp.data.settings.ActivityScopeResolver(settings, db)

    private val _state = MutableStateFlow(FeedManagerUiState())
    val state: StateFlow<FeedManagerUiState> = _state.asStateFlow()

    private val _form = MutableStateFlow<FeedFormState?>(null)
    val form: StateFlow<FeedFormState?> = _form.asStateFlow()

    /** 已有分组，供表单下拉复用 */
    private val _knownCategories = MutableStateFlow<List<String>>(emptyList())
    val knownCategories: StateFlow<List<String>> = _knownCategories.asStateFlow()

    /** 新增后的结果页（原型 34）：非 null 时整屏显示，关闭后回列表或调用方 */
    private val _addResult = MutableStateFlow<FeedAddResult?>(null)
    val addResult: StateFlow<FeedAddResult?> = _addResult.asStateFlow()

    private val network = dev.local.catleaf.nativeapp.data.network.NetworkMonitor(application)

    /** 本机范围的登记入口（Room 是权威，契约 8.2）；进程内复用 */
    private fun ensureLocalStore(): dev.local.catleaf.nativeapp.data.LocalFeedStore {
        localStore?.let { return it }
        return dev.local.catleaf.nativeapp.data.LocalFeedStore(
            db = db,
            scopeKey = dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY,
            cleanup = dev.local.catleaf.nativeapp.data.local.CacheCleanup(db, getApplication()),
        ).also { localStore = it }
    }

    private suspend fun ensureStore(): FeedStore? {
        val scope = scopeResolver.resolve()
        if (scope.kind == ScopeKind.LOCAL) {
            // 本机范围（契约 8.2）：走 [LocalFeedStore]，本机的增删改/验证/OPML 都直接写 Room
            store = null
            api = null
            instanceKey = null
            instanceSecret = null
            val feeds = ensureLocalStore().local()
            _knownCategories.value = categoriesOf(feeds)
            _state.value = FeedManagerUiState(feeds = feeds, localOnly = true)
            _form.value = null
            return null
        }
        localStore = null
        val key = scope.key
        val instance = if (scope.instanceReady) db.instances().get(key) else null
        val secret = if (scope.instanceReady) {
            runCatching {
                dev.local.catleaf.nativeapp.data.security.CredentialStore(getApplication())
                    .get("instance_secret_$key")
            }.getOrNull()
        } else null
        if (key == null || instance == null || secret == null) {
            store = null
            api = null
            instanceKey = null
            instanceSecret = null
            _state.value = FeedManagerUiState()
            _form.value = null
            return null
        }
        if (key == instanceKey && secret == instanceSecret) store?.let { return it }
        if (store != null) {
            _state.value = FeedManagerUiState()
            _form.value = null
        }
        instanceKey = key
        instanceSecret = secret
        val builtApi = FeedApi(instance.origin, secret)
        api = builtApi
        return FeedStore(db, builtApi, key).also { store = it }
    }

    /**
     * 把当前范围的 registry 与 OPML 导入器交给 OPML 流程（本机/实例同一接口）。
     * 返回 null 表示当前范围不可用（实例未连接）。
     */
    suspend fun opmlDependencies(): Pair<dev.local.catleaf.nativeapp.data.FeedRegistry, OpmlImporter>? {
        val scope = scopeResolver.resolve()
        return when (scope.kind) {
            ScopeKind.LOCAL -> {
                val local = ensureLocalStore()
                local to OpmlImporter(local)
            }
            ScopeKind.INSTANCE -> {
                val s = ensureStore() ?: return null
                val a = api ?: return null
                s to OpmlImporter(s)
            }
        }
    }

    private fun categoriesOf(feeds: List<FeedEntity>): List<String> =
        feeds.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    fun refresh(message: String? = null, errorMessage: String? = null) {
        viewModelScope.launch {
            val scope = scopeResolver.resolve()
            if (scope.kind == ScopeKind.LOCAL) {
                val feeds = ensureLocalStore().local()
                // 本机范围也要给表单已有分组 chip（VR3 实测：之前只在实例刷新成功时填，本机表单一个 chip 都没有）
                _knownCategories.value = categoriesOf(feeds)
                _state.value = FeedManagerUiState(
                    feeds = feeds,
                    message = message,
                    errorMessage = errorMessage,
                    localOnly = true,
                )
                return@launch
            }
            val s = ensureStore()
            if (s == null) {
                _state.value = _state.value.copy(loading = false, errorMessage = "尚未连接实例")
                return@launch
            }
            _state.value = _state.value.copy(loading = true, errorMessage = errorMessage)
            s.refresh().fold(
                onSuccess = { feeds ->
                    if (store !== s) return@fold
                    _knownCategories.value = categoriesOf(feeds)
                    _state.value = FeedManagerUiState(feeds = feeds, message = message, errorMessage = errorMessage)
                },
                onFailure = { e ->
                    if (store !== s) return@fold
                    // 失败保留本地镜像（契约：源失败不清空旧数据）
                    _state.value = _state.value.copy(loading = false, errorMessage = errorMessage ?: describe(e))
                },
            )
        }
    }

    fun openAddForm() {
        _form.value = FeedFormState()
    }

    /** 发现页预填（L04）：名称/分组可改、URL 预填；提交仍走共同新增路径 */
    fun openPrefilledAddForm(url: String, title: String, category: String) {
        _form.value = FeedFormState(url = url, customTitle = title, category = category)
    }

    fun openEditForm(feed: FeedEntity) {
        _form.value = FeedFormState(
            editing = true,
            feedId = feed.feedId,
            url = feed.url,
            customTitle = feed.title,
            category = feed.category,
        )
    }

    fun closeForm() {
        _form.value = null
    }

    fun onUrlChange(value: String) {
        _form.value = _form.value?.copy(url = value, errorMessage = null)
    }

    fun onTitleChange(value: String) {
        _form.value = _form.value?.copy(customTitle = value, errorMessage = null)
    }

    fun onCategoryChange(value: String) {
        _form.value = _form.value?.copy(category = value, errorMessage = null)
    }

    fun submitForm() {
        val form = _form.value ?: return
        if (!form.canSubmit) return
        _form.value = form.copy(submitting = true, errorMessage = null)

        viewModelScope.launch {
            // 登记与验证结果分开报（契约 8.2，L06）：本机与实例同一组合逻辑
            val localBranch = scopeResolver.resolve().kind == ScopeKind.LOCAL
            val result = if (localBranch) {
                val local = ensureLocalStore()
                if (form.editing) {
                    local.updateMeta(form.feedId, form.category, form.customTitle)
                        .map { FeedRegistry.AddResult(form.feedId, verified = true) }
                } else {
                    local.add(form.url.trim(), form.category.trim(), form.customTitle.trim())
                }
            } else {
                val s = ensureStore()
                if (s == null) {
                    _form.value = _form.value?.copy(submitting = false, errorMessage = "尚未连接实例")
                    return@launch
                }
                if (_form.value == null) return@launch
                val r = if (form.editing) {
                    s.updateMeta(form.feedId, form.category, form.customTitle)
                        .map { FeedRegistry.AddResult(form.feedId, verified = true) }
                } else {
                    s.add(form.url.trim(), form.category.trim(), form.customTitle.trim())
                }
                if (store !== s) return@launch
                r
            }

            result.fold(
                onSuccess = { added ->
                    if (form.editing) {
                        _form.value = null
                        // 消息随刷新一起写入：refresh 会整体替换状态，先 copy 的提示会被冲掉（VR5 实测）
                        refresh(message = "已保存")
                        return@fold
                    }
                    val addResult = buildAddResult(added, form.url.trim(), localBranch)
                    _form.value = null
                    _addResult.value = addResult
                    refresh()
                },
                onFailure = { e ->
                    // 保留用户输入，不关闭表单（页面说明：失败不清空输入）
                    _form.value = _form.value?.copy(submitting = false, errorMessage = describe(e))
                },
            )
        }
    }

    fun delete(feed: FeedEntity) {
        viewModelScope.launch {
            // 本机范围：直接删 Room 行；收藏与已读不受影响（契约 4.3）
            if (scopeResolver.resolve().kind == ScopeKind.LOCAL) {
                ensureLocalStore().delete(feed.feedId).fold(
                    onSuccess = {
                        refresh(message = "已删除「${feed.title}」")
                    },
                    onFailure = { e ->
                        _state.value = _state.value.copy(busyFeedId = null, errorMessage = describe(e))
                    },
                )
                return@launch
            }
            _state.value = _state.value.copy(busyFeedId = feed.feedId, message = null, errorMessage = null)
            val s = ensureStore()
            if (s == null) {
                _state.value = _state.value.copy(busyFeedId = null, errorMessage = "尚未连接实例")
                return@launch
            }
            if (feed.instanceKey != instanceKey) return@launch
            s.delete(feed.feedId).fold(
                onSuccess = {
                    if (store !== s) return@fold
                    _state.value = _state.value.copy(busyFeedId = null)
                    refresh(message = "已删除「${feed.title}」")
                },
                onFailure = { e ->
                    if (store !== s) return@fold
                    // 远端失败不得在界面上假装已删除
                    _state.value = _state.value.copy(busyFeedId = null, errorMessage = describe(e))
                },
            )
        }
    }

    fun retry(feed: FeedEntity) {
        viewModelScope.launch {
            // 本机范围：重新公开抓取并合并（失败保留源与原因，契约 8.2）
            if (scopeResolver.resolve().kind == ScopeKind.LOCAL) {
                _state.value = _state.value.copy(busyFeedId = feed.feedId, message = null, errorMessage = null)
                ensureLocalStore().verify(feed.feedId).fold(
                    onSuccess = {
                        _state.value = _state.value.copy(busyFeedId = null, message = "「${feed.title}」可阅读")
                        refresh(message = "「${feed.title}」可阅读")
                    },
                    onFailure = { e ->
                        val errorMessage = "「${feed.title}」验证失败：${describe(e)}"
                        _state.value = _state.value.copy(busyFeedId = null, errorMessage = errorMessage)
                        refresh(errorMessage = errorMessage)
                    },
                )
                return@launch
            }
            _state.value = _state.value.copy(busyFeedId = feed.feedId, message = null, errorMessage = null)
            val s = ensureStore()
            if (s == null) {
                _state.value = _state.value.copy(busyFeedId = null, errorMessage = "尚未连接实例")
                return@launch
            }
            if (feed.instanceKey != instanceKey) return@launch
            s.verify(feed.feedId).fold(
                onSuccess = {
                    if (store !== s) return@fold
                    _state.value = _state.value.copy(busyFeedId = null, message = "「${feed.title}」可阅读")
                    refresh(message = "「${feed.title}」可阅读")
                },
                onFailure = { e ->
                    if (store !== s) return@fold
                    val errorMessage = "「${feed.title}」验证失败：${describe(e)}"
                    _state.value = _state.value.copy(busyFeedId = null, errorMessage = errorMessage)
                    refresh(errorMessage = errorMessage)
                },
            )
        }
    }

    private suspend fun buildAddResult(added: FeedRegistry.AddResult, typedUrl: String, local: Boolean): FeedAddResult {
        val scopeKey = if (local) dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY else instanceKey
        val row = scopeKey?.let { key -> db.feeds().list(key).firstOrNull { it.feedId == added.feedId } }
        val outcome = feedAddOutcomeOf(added.verified, added.verifyError, network.isOnlineNow())
        return FeedAddResult(
            feedId = added.feedId,
            title = row?.title?.ifBlank { null } ?: typedUrl,
            url = row?.url ?: typedUrl,
            outcome = outcome,
            reason = if (outcome == FeedAddOutcome.Verified) null else describeVerifyError(added.verifyError),
        )
    }

    /** 结果页「重新验证」：只对同一 feedId 再抓一次，不重新登记（页面说明：重新验证不新增源） */
    fun retryAdded() {
        val current = _addResult.value ?: return
        if (current.retrying) return
        _addResult.value = current.copy(retrying = true)
        viewModelScope.launch {
            val local = scopeResolver.resolve().kind == ScopeKind.LOCAL
            val registry: FeedRegistry? = if (local) ensureLocalStore() else ensureStore()
            if (registry == null) {
                _addResult.value = _addResult.value?.copy(retrying = false, reason = "尚未连接实例")
                return@launch
            }
            val verify = registry.verify(current.feedId)
            // 用户已离开结果页：不回写
            if (_addResult.value?.feedId != current.feedId) return@launch
            val error = verify.exceptionOrNull()
            val outcome = feedAddOutcomeOf(verify.isSuccess, error, network.isOnlineNow())
            _addResult.value = current.copy(
                outcome = outcome,
                reason = if (outcome == FeedAddOutcome.Verified) null else describeVerifyError(error),
                retrying = false,
            )
            refresh()
        }
    }

    fun closeAddResult() {
        _addResult.value = null
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, errorMessage = null)
    }

    private fun describe(error: Throwable): String = when (error) {
        is FeedApiError.Conflict -> error.message ?: "操作被拒绝"
        is FeedApiError.Invalid -> error.message ?: "输入不合法"
        is FeedApiError.NotFound -> "源不存在，可能已在别处删除"
        is EndpointError.Unauthorized -> "密钥无效或已失效"
        is EndpointError.Forbidden -> "后端不允许抓取这个地址（403）"
        is EndpointError.NotConfigured -> "后端未配置密钥（503）"
        is EndpointError.Timeout -> "请求超时，请重试"
        is EndpointError.Unreachable -> error.message ?: "网络不可用"
        else -> error.message ?: "操作失败"
    }
}
