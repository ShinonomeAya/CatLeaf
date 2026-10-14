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
    private var savedKeyOrigin: String? = null
    private var storedKeyExists = false

    private val _state = MutableStateFlow(AiSettingsUiState())
    val state: StateFlow<AiSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snapshot = settings.current()
            val binding = runCatching { credentials.get(CredentialStore.AI_KEY_ORIGIN) }
            savedKeyOrigin = binding.getOrNull()
                ?: EndpointPolicy.originOf(snapshot.aiBaseUrl.orEmpty())
            storedKeyExists = credentials.exists(CredentialStore.AI_KEY) && binding.isSuccess
            _state.value = _state.value.copy(
                baseUrl = snapshot.aiBaseUrl.orEmpty(),
                model = snapshot.aiModel.orEmpty(),
                keySaved = storedKeyExists && EndpointPolicy.originOf(snapshot.aiBaseUrl.orEmpty()) == savedKeyOrigin,
            )
        }
    }

    fun onBaseUrlChange(value: String) {
        if (_state.value.saving || _state.value.testing) return
        _state.value = _state.value.copy(baseUrl = value, keySaved = storedKeyExists && EndpointPolicy.originOf(value) == savedKeyOrigin,
            message = null, test = AiTestState.Untested)
    }

    fun useDeepSeek() {
        if (_state.value.saving || _state.value.testing) return
        val sameProvider = EndpointPolicy.originOf(_state.value.baseUrl) == DEEPSEEK_BASE
        onBaseUrlChange(DEEPSEEK_BASE)
        _state.value = _state.value.copy(model = DEEPSEEK_MODEL, keyInput = if (sameProvider) _state.value.keyInput else "",
            keyVisible = false, message = "已填入DeepSeek地址和模型；请核对该供应商的Key后保存。")
    }

    fun onModelChange(value: String) {
        if (_state.value.saving || _state.value.testing) return
        _state.value = _state.value.copy(model = value, message = null, test = AiTestState.Untested)
    }

    fun onKeyChange(value: String) {
        if (_state.value.saving || _state.value.testing) return
        _state.value = _state.value.copy(keyInput = value, message = null, test = AiTestState.Untested)
    }

    fun toggleKeyVisible() {
        _state.value = _state.value.copy(keyVisible = !_state.value.keyVisible)
    }

    /** 密文与配置都写盘成功才显示成功；失败恢复原密文。 */
    fun save() {
        val snapshot = _state.value
        if (snapshot.saving || snapshot.testing) return
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
            val oldKey = runCatching { credentials.ciphertext(CredentialStore.AI_KEY) }
            val oldOrigin = runCatching { credentials.ciphertext(CredentialStore.AI_KEY_ORIGIN) }
            if (oldKey.isFailure || oldOrigin.isFailure) {
                _state.value = snapshot.copy(saving = false, message = "无法保留原AI配置，本次未保存")
                return@launch
            }
            val ok = runCatching {
                if (snapshot.keyInput.isNotBlank()) {
                    credentials.putAiKey(normalized, snapshot.keyInput.trim())
                } else if (!credentials.exists(CredentialStore.AI_KEY_ORIGIN)) {
                    credentials.put(CredentialStore.AI_KEY_ORIGIN, EndpointPolicy.originOf(normalized)!!)
                }
                settings.setAiConfig(normalized, snapshot.model.trim())
                true
            }.getOrElse { false }
            val restored = ok || runCatching {
                credentials.restoreCiphertext(CredentialStore.AI_KEY, oldKey.getOrThrow())
                credentials.restoreCiphertext(CredentialStore.AI_KEY_ORIGIN, oldOrigin.getOrThrow())
                true
            }.getOrElse { false }

            if (ok) { savedKeyOrigin = EndpointPolicy.originOf(normalized); storedKeyExists = true }
            _state.value = _state.value.copy(
                saving = false,
                keyInput = if (ok) "" else snapshot.keyInput,
                keySaved = if (ok) true else snapshot.keySaved && restored,
                message = if (ok) "已保存（Key已加密保存在本机）" else if (restored) "保存失败，原AI凭据已保留" else "保存失败，凭据恢复未完成，请重新配置AI",
            )
        }
    }

    /** 删除 AI 配置：地址、模型与密文一起清掉；未配置不影响阅读（P07） */
    fun delete() {
        if (_state.value.saving || _state.value.testing) return
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch {
            val ok = runCatching {
                credentials.delete(CredentialStore.AI_KEY)
                credentials.delete(CredentialStore.AI_KEY_ORIGIN)
                settings.setAiConfig(null, null)
                true
            }.getOrElse { false }

            _state.value = _state.value.copy(
                saving = false,
                baseUrl = if (ok) "" else _state.value.baseUrl,
                model = if (ok) "" else _state.value.model,
                keyInput = "",
                keySaved = !ok && _state.value.keySaved,
                test = AiTestState.Untested,
                message = if (ok) "已删除 AI 配置" else "删除失败，请重试",
            )
            if (ok) { savedKeyOrigin = null; storedKeyExists = false }
        }
    }

    /** 测试连接：显式触发、只发一次最小文本；结果只描述这一次 */
    fun testConnection() {
        val snapshot = _state.value
        if (snapshot.testing || snapshot.saving) return
        if (!snapshot.hasKey) {
            _state.value = snapshot.copy(test = AiTestState.Failed("请先填写当前供应商的API Key"))
            return
        }

        val key = snapshot.keyInput.trim().ifBlank {
            runCatching { credentials.getAiKey(snapshot.baseUrl) }.getOrNull().orEmpty()
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
        const val DEEPSEEK_BASE = "https://api.deepseek.com"
        const val DEEPSEEK_MODEL = "deepseek-flash"
        /** 最小测试文本：明确会发送少量内容并可能计费（契约 5.1） */
        const val TEST_PROMPT = "只回复两个字：收到"
    }
}
