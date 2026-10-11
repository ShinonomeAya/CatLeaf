package dev.local.catleaf.nativeapp.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * UIR04（原型 16）：「已保存，立即生效」只能在写盘成功后出现；失败必须恢复原设置并如实报错。
 */
class PreferenceSaveTest {

    @Test
    fun success_keeps_new_value_and_reports_saved() = runBlocking {
        var shown = 18
        var persisted: Int? = null
        val status = saveWithRollback(previous = 18, next = 20, apply = { shown = it }, persist = { persisted = it })
        assertEquals(SaveStatus.Saved, status)
        assertEquals("界面保持新值", 20, shown)
        assertEquals("写入的是新值", 20, persisted)
    }

    @Test
    fun failure_restores_previous_value_and_reports_failed() = runBlocking {
        val applied = mutableListOf<Int>()
        val status = saveWithRollback(
            previous = 18,
            next = 22,
            apply = { applied += it },
            persist = { throw IOException("disk full") },
        )
        assertEquals(SaveStatus.Failed, status)
        assertEquals("先预览新值，失败后恢复原值", listOf(22, 18), applied)
    }

    @Test
    fun cancellation_is_rethrown_not_reported_as_failure() = runBlocking {
        val applied = mutableListOf<Int>()
        try {
            saveWithRollback(previous = 18, next = 16, apply = { applied += it }, persist = { throw CancellationException("left page") })
            fail("取消必须继续抛出")
        } catch (e: CancellationException) {
            assertEquals("取消不回滚、不显示失败", listOf(16), applied)
        }
    }
}
