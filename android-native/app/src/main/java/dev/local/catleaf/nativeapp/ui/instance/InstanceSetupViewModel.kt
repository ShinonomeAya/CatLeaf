package dev.local.catleaf.nativeapp.ui.instance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.InstanceConnector
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.settings.ActivityScopeResolver
import dev.local.catleaf.nativeapp.data.settings.ScopeKind
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 进入意图（UIR10）：标题、状态卡与提交文案按意图区分，不再一律显示管理语境。
 * - [FirstRun]：实例指针悬空时的 P01 修复页（无返回）；
 * - [Connect]：本机使用中从「使用方式」连接自有实例（原型 30），成功才切换；
 * - [Manage]：正在使用实例时的实例管理（原型 17）：同址更新密钥 / 换地址切换。
 */
enum class InstanceIntent { FirstRun, Connect, Manage }

/**
 * 连接实例的界面状态。按页面说明 P01：区分地址不可达、TLS/网络、未授权、后端未配置，
 * 失败时保留用户已填内容，不覆盖旧配置。
 */
data class InstanceSetupUiState(
    val origin: String = "",
    val secret: String = "",
    val secretVisible: Boolean = false,
    val connecting: Boolean = false,
    val errorMessage: String? = null,
    val connectedOrigin: String? = null,
    val intent: InstanceIntent = InstanceIntent.FirstRun,
    /**
     * 管理模式：当前（活动）实例 origin，作只读展示与同址/跨址语义判断的参照；
     * 连接模式：曾保存但当前未使用的实例 origin（仅作预填与说明）。密钥始终不回显。
     */
    val managingFrom: String? = null,
    /** 管理模式状态卡：该实例上次连接成功时间（Room 实例行），null 表示未知 */
    val lastConnectedAt: Long? = null,
) {
    /** 管理模式（T22 F16 / 原型 17）：从「使用方式」在实例范围进入 */
    val managing: Boolean get() = intent == InstanceIntent.Manage

    /** 主按钮可用性：地址与密钥都非空且当前没有进行中的请求 */
    val canSubmit: Boolean get() = origin.isNotBlank() && secret.isNotBlank() && !connecting

    /** 管理模式下地址是否仍等于当前实例：true=同址换密钥（保留数据）；false=跨 origin 切换（数据按实例隔离） */
    val rotatingKey: Boolean get() = managing && origin.trim() == managingFrom?.trim()
}

class InstanceSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val _state = MutableStateFlow(InstanceSetupUiState())
    val state: StateFlow<InstanceSetupUiState> = _state.asStateFlow()
    private var openedSession: Int = Int.MIN_VALUE

    fun onOriginChange(value: String) {
        // 输入过程中清掉上一次的错误，但保留内容
        _state.value = _state.value.copy(origin = value, errorMessage = null)
    }

    fun onSecretChange(value: String) {
        _state.value = _state.value.copy(secret = value, errorMessage = null)
    }

    fun toggleSecretVisible() {
        _state.value = _state.value.copy(secretVisible = !_state.value.secretVisible)
    }

    fun connect() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.value = current.copy(connecting = true, errorMessage = null)

        viewModelScope.launch {
            val context = getApplication<Application>()
            val connector = EndpointConnectorHolder.get(context)
            val result = connector.connect(current.origin, current.secret)
            _state.value = result.fold(
                onSuccess = { connected ->
                    // 成功后清空密钥字段，界面不再保留明文；管理模式标志保留，避免结果页闪回首配文案
                    InstanceSetupUiState(
                        origin = connected.origin,
                        connectedOrigin = connected.origin,
                        intent = current.intent,
                        managingFrom = current.managingFrom,
                    )
                },
                onFailure = { error ->
                    current.copy(
                        connecting = false,
                        errorMessage = messageFor(error),
                    )
                },
            )
        }
    }

    /** 设置页复用本流程后清一次状态：下次进来不带上一回的「已连接」结果 */
    fun reset() {
        _state.value = InstanceSetupUiState()
    }

    /**
     * 按意图打开（UIR10）。先**同步**换成干净状态（不带上一回的 connectedOrigin/错误，
     * 避免返回判定误触发），再异步从 DataStore/Room 取已保存实例 origin 预填：
     * 管理模式预填当前实例并带上次连接时间；连接模式预填曾保存的地址（可改）。密钥保持空。
     */
    fun open(intent: InstanceIntent, session: Int) {
        // 同一次进入（旋转/重组）不重置已填内容；换 session 才是新的一次打开
        if (session == openedSession && _state.value.intent == intent) return
        openedSession = session
        _state.value = InstanceSetupUiState(intent = intent)
        if (intent == InstanceIntent.FirstRun) return
        viewModelScope.launch {
            val key = settings.current().currentInstanceKey
            val saved = key?.let { runCatching { CatLeafDatabase.get(getApplication()).instances().get(it) }.getOrNull() }
            val now = _state.value
            // 期间用户已开始输入或又切了意图就不覆盖
            if (now.intent != intent || now.origin.isNotEmpty() || saved == null) return@launch
            _state.value = now.copy(
                origin = saved.origin,
                managingFrom = saved.origin,
                lastConnectedAt = if (intent == InstanceIntent.Manage) saved.lastConnectedAt else null,
            )
        }
    }

    /**
     * 根导航判定（ADR 003，契约 8.1）：本机范围直接可用；实例范围要求实例行还在
     * （密钥暂不可读不算——缓存照读，修复提示在页面内）；实例指针悬空 → P01 修复。
     * 首次调用会按既有数据推断范围并落盘（老用户补「引导已处理」）。
     */
    /** 新用户引导是否待显示（L04）：仅本机范围且 onboardingDone 未落盘 */
    suspend fun onboardingPending(): Boolean {
        val scope = ActivityScopeResolver(settings, CatLeafDatabase.get(getApplication())).resolve()
        if (scope.kind != ScopeKind.LOCAL) return false
        return !settings.current().onboardingDone
    }

    suspend fun isScopeReady(): Boolean {
        val scope = ActivityScopeResolver(settings, CatLeafDatabase.get(getApplication())).resolve()
        return scope.kind == ScopeKind.LOCAL || scope.instanceReady
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is EndpointError.Invalid -> error.message ?: "地址不可用"
        is EndpointError.Unauthorized -> "密钥无效或已失效，请核对后重试。"
        is EndpointError.NotConfigured -> "该地址的后端尚未配置密钥（503）。"
        is EndpointError.Timeout -> "连接超时，请检查网络后重试。"
        is EndpointError.Unreachable -> error.message ?: "无法连接"
        else -> "连接失败：${error.message ?: "未知原因"}"
    }
}

/** 数据库与凭据存储按进程共享，避免每处各自构造 */
object EndpointConnectorHolder {
    @Volatile
    private var instance: InstanceConnector? = null

    fun get(context: android.content.Context): InstanceConnector =
        instance ?: synchronized(this) {
            instance ?: InstanceConnector(
                db = CatLeafDatabase.get(context),
                credentials = CredentialStore(context),
                settings = SettingsStore(context),
            ).also { instance = it }
        }
}