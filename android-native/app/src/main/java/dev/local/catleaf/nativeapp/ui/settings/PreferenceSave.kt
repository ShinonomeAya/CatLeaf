package dev.local.catleaf.nativeapp.ui.settings

import kotlinx.coroutines.CancellationException

/** 阅读偏好保存反馈（UIR04，原型 16）：只在真实写盘结果出来后显示 */
enum class SaveStatus { Idle, Saved, Failed }

/**
 * 先即时预览（[apply] 新值），再落盘；落盘失败把界面值恢复为 [previous] 并报 [SaveStatus.Failed]。
 * 取消照常抛出，不当作失败，也不回滚（页面离开时协程被取消，已预览值由快照回流校正）。
 */
suspend fun <T> saveWithRollback(
    previous: T,
    next: T,
    apply: (T) -> Unit,
    persist: suspend (T) -> Unit,
): SaveStatus {
    apply(next)
    return try {
        persist(next)
        SaveStatus.Saved
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        apply(previous)
        SaveStatus.Failed
    }
}
