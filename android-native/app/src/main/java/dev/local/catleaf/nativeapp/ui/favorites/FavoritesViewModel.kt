package dev.local.catleaf.nativeapp.ui.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.StarredRow
import dev.local.catleaf.nativeapp.data.settings.ActivityScopeResolver
import dev.local.catleaf.nativeapp.data.settings.ScopeKind
import dev.local.catleaf.nativeapp.data.settings.ScrollAnchor
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 收藏列表的一行：文章 + 文本状态 + 来源是否还在本地镜像里 */
data class FavoriteRow(
    val localId: String,
    val title: String,
    val feedTitle: String,
    val starredAt: Long?,
    /** 已保存正文（可离线读） */
    val hasText: Boolean,
    /** 来源已从订阅里删除：仍要能打开，身份靠文章行的快照 */
    val sourceDeleted: Boolean,
)

data class FavoritesUiState(
    val loading: Boolean = true,
    val rows: List<FavoriteRow> = emptyList(),
    val notConnected: Boolean = false,
    val error: String? = null,
) {
    val savedTextCount: Int get() = rows.count { it.hasText }
}

/**
 * 收藏列表的纯映射（单独成函数便于单测）：只保留当前实例的收藏，
 * 并把「来源已不在本地源镜像里」的行标出来——删源不移除收藏（契约 4.3）。
 */
internal fun favoriteRowsOf(
    rows: List<StarredRow>,
    knownFeedIds: Set<String>,
    instanceKey: String,
): List<FavoriteRow> = rows
    .filter { it.article.instanceKey == instanceKey }
    .map { row ->
        FavoriteRow(
            localId = row.article.localId,
            title = row.article.title,
            feedTitle = row.article.feedTitle,
            starredAt = row.article.starredAt,
            hasText = row.hasText,
            sourceDeleted = row.article.feedId !in knownFeedIds,
        )
    }

/**
 * P06 收藏列表。数据全部来自本机：收藏不依赖网络，离线冷启动也要能读（契约 4.3）。
 * 按收藏时间倒序（`starredAt`，查询里已排序），来源是否已删除查本地源镜像。
 */
class FavoritesViewModel @JvmOverloads constructor(
    application: Application,
    private val db: CatLeafDatabase = CatLeafDatabase.get(application),
) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val scope = ActivityScopeResolver(settings, db).resolve()
                if (scope.kind == ScopeKind.INSTANCE && !scope.instanceReady) {
                    // 实例指针悬空：不建空范围覆盖，给连接指引（契约 8.1）
                    _state.value = FavoritesUiState(loading = false, notConnected = true)
                    return@launch
                }
                val key = scope.key
                // 源镜像缺失只说明“本地没有这个源了”（删源或还没同步），收藏本身照常显示
                val knownFeeds = db.feeds().list(key).map { it.feedId }.toSet()
                val rows = favoriteRowsOf(db.articles().listStarredWithText(), knownFeeds, key)

                _state.value = FavoritesUiState(loading = false, rows = rows)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.value = _state.value.copy(loading = false, error = "收藏读取失败，请重试")
            }
        }
    }

    /** P06 列表也要能回到原处（契约 §7：稳定文章 id + 偏移） */
    suspend fun savedScrollAnchor(): ScrollAnchor.ListAnchor? =
        ScrollAnchor.decodeList(settings.current().favoritesAnchor)

    fun rememberScrollAnchor(localId: String, offset: Int) {
        viewModelScope.launch {
            settings.setFavoritesAnchor(ScrollAnchor.encodeList(localId, offset))
        }
    }
}
