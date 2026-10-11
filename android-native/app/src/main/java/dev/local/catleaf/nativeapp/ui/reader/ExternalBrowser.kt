package dev.local.catleaf.nativeapp.ui.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy

/**
 * 外链交给系统浏览器（契约第 7 节：正文 WebView 不留外站，也不打开原站页面）。
 * 没有可用浏览器时返回可读原因，而不是静默失败。
 */
object ExternalBrowser {

    /** @return null 表示已交给系统；否则是给用户看的原因 */
    fun open(context: Context, url: String): String? {
        if (!EndpointPolicy.isSafePublicUrl(url)) {
            return "这个链接不是 http/https 地址，已阻止打开"
        }
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) {
            // 从非 Activity 上下文启动需要新任务栈
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "没有找到可用的浏览器，无法打开原网页"
        }
    }
}