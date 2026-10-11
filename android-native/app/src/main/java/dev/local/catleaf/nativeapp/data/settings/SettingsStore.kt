package dev.local.catleaf.nativeapp.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 普通设置：主题、正文字号、当前实例、上次阅读位置。
 * 这里**不放任何密钥**（契约 4.1）；Key 由 [dev.local.catleaf.nativeapp.data.security.CredentialStore] 负责。
 */
class SettingsStore(private val context: Context) {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    data class Snapshot(
        val themeMode: ThemeMode,
        val bodyFontSizeSp: Int,
        val currentInstanceKey: String?,
        val lastFeedId: String?,
        val lastFilter: String?,
        /** 阅读列表位置锚点（稳定文章 id + 偏移，见 [ScrollAnchor]） */
        val lastScrollAnchor: String?,
        /** 收藏列表位置锚点 */
        val favoritesAnchor: String?,
        /** 上次读到的正文位置（文章 id + 内容指纹 + 滚动位置 + 内容高度） */
        val readerPosition: String?,
        /** AI 服务地址（按供应商文档填写，客户端只追加 /chat/completions） */
        val aiBaseUrl: String?,
        /** AI 模型 id（手填，不依赖供应商有 /models） */
        val aiModel: String?,
        /**
         * 活动范围（契约 8.1）："local" / "instance"。旧版本无此字段（null），
         * 由 [ActivityScopeRules] 按既有数据推断并落盘，推断规则见 [ActivityScopeResolver]。
         */
        val activeScopeKind: String?,
        /** 新用户引导已处理（老用户升级补 true，不重复引导；完成/跳过引导由 L04 写入） */
        val onboardingDone: Boolean,
        val lastReadingScopeKey: String? = null,
        val lastReadingAllFeeds: Boolean = false,
        val lastReadingLoadedCount: Int = 200,
    )

    val snapshot: Flow<Snapshot> = context.settingsDataStore.data.map { prefs ->
        Snapshot(
            themeMode = prefs[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            bodyFontSizeSp = prefs[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE_SP,
            currentInstanceKey = prefs[KEY_INSTANCE],
            lastFeedId = prefs[KEY_LAST_FEED],
            lastFilter = prefs[KEY_LAST_FILTER],
            lastScrollAnchor = prefs[KEY_SCROLL_ANCHOR],
            favoritesAnchor = prefs[KEY_FAVORITES_ANCHOR],
            readerPosition = prefs[KEY_READER_POSITION],
            aiBaseUrl = prefs[KEY_AI_BASE_URL],
            aiModel = prefs[KEY_AI_MODEL],
            activeScopeKind = prefs[KEY_ACTIVE_SCOPE],
            onboardingDone = prefs[KEY_ONBOARDING_DONE] ?: false,
            lastReadingScopeKey = prefs[KEY_READING_SCOPE],
            lastReadingAllFeeds = prefs[KEY_READING_ALL] ?: false,
            lastReadingLoadedCount = prefs[KEY_READING_COUNT] ?: 200,
        )
    }

    suspend fun current(): Snapshot = snapshot.first()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME] = mode.name }

    /** 正文可选 16/18/20/22（设计规范）；越界夹到范围，不写坏值 */
    suspend fun setBodyFontSize(sp: Int) =
        edit { it[KEY_FONT_SIZE] = sp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP) }

    suspend fun setCurrentInstanceKey(instanceKey: String?) = edit {
        if (instanceKey == null) it.remove(KEY_INSTANCE) else it[KEY_INSTANCE] = instanceKey
    }

    /** 活动范围只由 [ActivityScopeResolver]（迁移/推断）与连接实例（InstanceConnector）写入；null 供测试回到旧格式 */
    suspend fun setActiveScopeKind(kind: String?) = edit {
        if (kind == SCOPE_KIND_LOCAL || kind == SCOPE_KIND_INSTANCE) it[KEY_ACTIVE_SCOPE] = kind
        else it.remove(KEY_ACTIVE_SCOPE)
    }

    suspend fun setOnboardingDone(done: Boolean) = edit {
        if (done) it[KEY_ONBOARDING_DONE] = true else it.remove(KEY_ONBOARDING_DONE)
    }

    suspend fun setLastReadingPosition(feedId: String?, filter: String?, scrollAnchor: String?, scopeKey: String? = null, allFeeds: Boolean = false, loadedCount: Int = 200) = edit {
        if (feedId == null) it.remove(KEY_LAST_FEED) else it[KEY_LAST_FEED] = feedId
        if (filter == null) it.remove(KEY_LAST_FILTER) else it[KEY_LAST_FILTER] = filter
        if (scrollAnchor == null) it.remove(KEY_SCROLL_ANCHOR) else it[KEY_SCROLL_ANCHOR] = scrollAnchor
        if (scopeKey == null) it.remove(KEY_READING_SCOPE) else it[KEY_READING_SCOPE] = scopeKey
        it[KEY_READING_ALL] = allFeeds
        it[KEY_READING_COUNT] = loadedCount.coerceAtLeast(200)
    }

    /** AI 的服务地址与模型 id 是普通设置；**Key 不在这里**（走 Keystore 密文，契约 4.1/5.1） */
    suspend fun setAiConfig(baseUrl: String?, model: String?) = edit {
        if (baseUrl == null) it.remove(KEY_AI_BASE_URL) else it[KEY_AI_BASE_URL] = baseUrl
        if (model == null) it.remove(KEY_AI_MODEL) else it[KEY_AI_MODEL] = model
    }

    suspend fun setFavoritesAnchor(anchor: String?) = edit {
        if (anchor == null) it.remove(KEY_FAVORITES_ANCHOR) else it[KEY_FAVORITES_ANCHOR] = anchor
    }

    suspend fun setReaderPosition(position: String?) = edit {
        if (position == null) it.remove(KEY_READER_POSITION) else it[KEY_READER_POSITION] = position
    }

    /** “重置此设备数据”用：只清普通设置，不碰凭据与数据库 */
    suspend fun clear(): Unit {
        context.settingsDataStore.edit { it.clear() }
    }

    /** 仪器测试用：把活动范围与引导标记回到「旧版本格式」，隔离各用例互不污染 */
    suspend fun clearScopeForTest() = edit {
        it.remove(KEY_ACTIVE_SCOPE)
        it.remove(KEY_ONBOARDING_DONE)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    /** 供验收核对：普通设置里不应出现任何 Key 明文 */
    fun storagePath(): String = context.filesDir.resolve("datastore/settings.preferences_pb").absolutePath

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 18
        const val MIN_FONT_SIZE_SP = 16
        const val MAX_FONT_SIZE_SP = 22

        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_FONT_SIZE = intPreferencesKey("body_font_size_sp")
        private val KEY_INSTANCE = stringPreferencesKey("current_instance_key")
        private val KEY_LAST_FEED = stringPreferencesKey("last_feed_id")
        private val KEY_READING_SCOPE = stringPreferencesKey("last_reading_scope_key")
        private val KEY_READING_ALL = booleanPreferencesKey("last_reading_all_feeds")
        private val KEY_READING_COUNT = intPreferencesKey("last_reading_loaded_count")
        private val KEY_LAST_FILTER = stringPreferencesKey("last_filter")
        private val KEY_SCROLL_ANCHOR = stringPreferencesKey("last_scroll_anchor")
        private val KEY_FAVORITES_ANCHOR = stringPreferencesKey("favorites_scroll_anchor")
        private val KEY_READER_POSITION = stringPreferencesKey("reader_position")
        private val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
        private val KEY_AI_MODEL = stringPreferencesKey("ai_model")
        private val KEY_ACTIVE_SCOPE = stringPreferencesKey("active_scope_kind")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }
}
