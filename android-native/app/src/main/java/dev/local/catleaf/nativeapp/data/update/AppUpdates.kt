package dev.local.catleaf.nativeapp.data.update

import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import org.json.JSONObject
import org.json.JSONTokener

data class UpdateRelease(val version: String, val code: Long, val downloadUrl: String, val notes: String) {
    fun compareWith(installedCode: Long): UpdateCheck =
        if (code > installedCode) UpdateCheck.Available(this) else UpdateCheck.Latest
}

sealed class UpdateCheck {
    data object Idle : UpdateCheck()
    data object Checking : UpdateCheck()
    data object NotConfigured : UpdateCheck()
    data object Latest : UpdateCheck()
    data class Available(val release: UpdateRelease) : UpdateCheck()
    data class Failed(val reason: String) : UpdateCheck()
}

/** Manual, credential-free metadata check; APK download stays in the system browser. */
class AppUpdates(private val fetch: PublicFetch = PublicFetch()) {
    fun check(url: String, packageId: String, installedCode: Long): UpdateCheck {
        if (url.isBlank()) return UpdateCheck.NotConfigured
        val response = fetch.fetch(url, MAX_MANIFEST_BYTES).getOrElse {
            return UpdateCheck.Failed(it.message ?: "网络不可用，请稍后再试")
        }
        return runCatching {
            val tokener = JSONTokener(response.bytes.decodeToString())
            val json = tokener.nextValue() as? JSONObject ?: error("无效对象")
            require(tokener.nextClean() == '\u0000')
            require(json.opt("packageId") == packageId)
            require(json.opt("published") == true)
            val code = json.opt("versionCode")
            require(code is Int || code is Long)
            val versionCode = (code as Number).toLong()
            require(versionCode in 1..Int.MAX_VALUE.toLong())
            val version = json.opt("version") as? String ?: error("版本缺失")
            require(version.isNotBlank() && version.length <= 80)
            val urlValue = json.opt("downloadUrl") as? String ?: error("下载地址缺失")
            val downloadUrl = EndpointPolicy.requirePublicFetchTarget(urlValue).getOrThrow()
            val notes = json.opt("releaseNotes")
            require(notes == null || notes == JSONObject.NULL || notes is String)
            UpdateRelease(version, versionCode, downloadUrl, (notes as? String).orEmpty().take(2000))
                .compareWith(installedCode)
        }.getOrElse { UpdateCheck.Failed("更新信息无效或尚未公开，请稍后再试") }
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
