package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.data.network.AiClient
import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 测试连接的三种结果。成功与失败都只描述这一次请求：首版不自动重试（契约 5.1）。
 */
sealed class AiTestState {
    object Untested : AiTestState()
    object Running : AiTestState()
    data class Ok(val reply: String) : AiTestState()
    data class Failed(val reason: String) : AiTestState()
}

data class AiSettingsUiState(
    val baseUrl: String = "",
    val model: String = "",
    /** 本次输入框里的新 Key（只在内存里，保存时才进 Keystore 密文） */
    val keyInput: String = "",
    /** 已经保存过 Key（界面只显示掩码与「删除」，不回显完整值） */
    val keySaved: Boolean = false,
    val keyVisible: Boolean = false,
    val testing: Boolean = false,
    val test: AiTestState = AiTestState.Untested,
    val saving: Boolean = false,
    val message: String? = null,
) {
    /** 界面要显示的接收方主机名：让用户知道内容会发到哪里（P07） */
    val destinationHost: String?
        get() = EndpointPolicy.originOf(baseUrl.trim())?.removePrefix("https://")

    val hasKey: Boolean get() = keyInput.isNotBlank() || keySaved
}

/**
 * 本地能判定就别发请求（契约 5.1：未配置、输入不合法时本地拦截）。
 * 返回 null 表示可以发。
 */
internal fun aiConfigBlockReason(baseUrl: String, model: String, key: String): String? = when {
    baseUrl.isBlank() -> "请先填写服务地址"
    EndpointPolicy.normalizeAiBaseUrl(baseUrl).isFailure ->
        "服务地址不合法：${EndpointPolicy.normalizeAiBaseUrl(baseUrl).exceptionOrNull()?.message ?: "格式不正确"}"
    model.isBlank() -> "请先填写模型 ID"
    key.isBlank() -> "请先填写 API Key"
    else -> null
}

/**
 * P07 的 AI 分组（首版只做一套 Chat Completions 配置）。
 *
 * 三条硬规则：
 * - **Key 只在内存与 Keystore 密文里**：输入框内容不落 DataStore/Room，界面保存后只显示已保存状态；
 * - **写盘成功才显示成功**：保存要等 DataStore 与密文都写完；
 * - **测试只发一次**：显式点击才发，失败就是失败，不自动重试、不写成功缓存。
 */
class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val credentials = CredentialStore(application)
    private val client = AiClient()

    private val _state = MutableStateFlow(AiSettingsUiState())
    val state: StateFlow<AiSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snapshot = settings.current()
            _state.value = _state.value.copy(
                baseUrl = snapshot.aiBaseUrl.orEmpty(),
                model = snapshot.aiModel.orEmpty(),
                keySaved = credentials.exists(CredentialStore.AI_KEY),
            )
        }
    }

    fun onBaseUrlChange(value: String) {
        _state.value = _state.value.copy(baseUrl = value, message = null, test = AiTestState.Untested)
    }

    fun onModelChange(value: String) {
        _state.value = _state.value.copy(model = value, message = null, test = AiTestState.Untested)
    }

    fun onKeyChange(value: String) {
        _state.value = _state.value.copy(keyInput = value, message = null, test = AiTestState.Untested)
    }

    fun toggleKeyVisible() {
        _state.value = _state.value.copy(keyVisible = !_state.value.keyVisible)
    }

    /** 保存配置：先校验、再写地址与模型、最后写 Key；**任何一步失败都不显示成功** */
    fun save() {
        val snapshot = _state.value
        if (snapshot.saving) return
        val normalized = EndpointPolicy.normalizeAiBaseUrl(snapshot.baseUrl).getOrElse { error ->
            _state.value = snapshot.copy(message = error.message ?: "服务地址不合法")
            return
        }
        if (snapshot.model.isBlank()) {
            _state.value = snapshot.copy(message = "请先填写模型 ID")
            return
        }
        if (snapshot.keyInput.isBlank() && !snapshot.keySaved) {
            _state.value = snapshot.copy(message = "请先填写 API Key")
            return
        }

        _state.value = snapshot.copy(saving = true, message = null)
        viewModelScope.launch {
            val ok = runCatching {
                if (snapshot.keyInput.isNotBlank()) {
                    credentials.put(CredentialStore.AI_KEY, snapshot.keyInput.trim())
                }
                settings.setAiConfig(normalized, snapshot.model.trim())
                true
            }.getOrElse { false }

            _state.value = _state.value.copy(
                saving = false,
                keyInput = "",
                keySaved = snapshot.keyInput.isNotBlank() || snapshot.keySaved,
                message = if (ok) "已保存（Key 保存在系统密钥库里）" else "保存失败，配置未生效",
            )
        }
    }

    /** 删除 AI 配置：地址、模型与密文一起清掉；未配置不影响阅读（P07） */
    fun delete() {
        viewModelScope.launch {
            val ok = runCatching {
                credentials.delete(CredentialStore.AI_KEY)
                settings.setAiConfig(null, null)
                true
            }.getOrElse { false }

            _state.value = _state.value.copy(
                baseUrl = if (ok) "" else _state.value.baseUrl,
                model = if (ok) "" else _state.value.model,
                keyInput = "",
                keySaved = !ok && _state.value.keySaved,
                test = AiTestState.Untested,
                message = if (ok) "已删除 AI 配置" else "删除失败，请重试",
            )
        }
    }

    /** 测试连接：显式触发、只发一次最小文本；结果只描述这一次 */
    fun testConnection() {
        val snapshot = _state.value
        if (snapshot.testing) return

        val key = snapshot.keyInput.trim().ifBlank {
            runCatching { credentials.get(CredentialStore.AI_KEY) }.getOrNull().orEmpty()
        }
        aiConfigBlockReason(snapshot.baseUrl, snapshot.model, key)?.let { reason ->
            _state.value = snapshot.copy(test = AiTestState.Failed(reason))
            return
        }

        _state.value = snapshot.copy(testing = true, test = AiTestState.Running, message = null)
        viewModelScope.launch {
            val result = client.chat(snapshot.baseUrl, snapshot.model.trim(), key, TEST_PROMPT)
            _state.value = _state.value.copy(
                testing = false,
                test = result.fold(
                    onSuccess = { AiTestState.Ok(it.content.trim()) },
                    onFailure = { AiTestState.Failed(describe(it)) },
                ),
            )
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is EndpointError.Unauthorized -> "密钥无效或已失效"
        is EndpointError.Timeout -> "请求超时（未自动重试，可再点一次测试）"
        is EndpointError.Unreachable -> error.message ?: "无法连接"
        is EndpointError.Invalid -> error.message ?: "配置不合法"
        else -> error.message ?: "测试失败"
    }

    private companion object {
        /** 最小测试文本：明确会发送少量内容并可能计费（契约 5.1） */
        const val TEST_PROMPT = "只回复两个字：收到"
    }
}