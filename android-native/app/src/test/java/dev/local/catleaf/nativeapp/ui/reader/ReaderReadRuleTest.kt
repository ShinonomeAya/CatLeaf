package dev.local.catleaf.nativeapp.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验收①的核心规则：正文成功显示才自动标已读，而**用户手动设为未读后不能被同一次渲染改回去**。
 * 单独钉住这条规则，因为它只靠界面观察很难判断“到底有没有被覆盖”。
 */
class ReaderReadRuleTest {

    @Test
    fun marks_read_when_body_was_shown_and_still_unread() {
        assertEquals(
            "正文显示后应自动标已读",
            true,
            shouldAutoMarkRead(bodyShown = true, isRead = false, manuallyUnread = false),
        )
    }

    @Test
    fun does_not_re_mark_after_manual_unread() {
        assertEquals(
            "手动设为未读后，同一次打开不得再自动改回已读",
            false,
            shouldAutoMarkRead(bodyShown = true, isRead = false, manuallyUnread = true),
        )
    }

    @Test
    fun does_not_write_when_already_read() {
        assertEquals(
            "已经是已读就不必再写库",
            false,
            shouldAutoMarkRead(bodyShown = true, isRead = true, manuallyUnread = false),
        )
    }

    @Test
    fun does_not_mark_read_without_body() {
        assertEquals(
            "没有正文可显示时不算读过（例如只剩链接的书签）",
            false,
            shouldAutoMarkRead(bodyShown = false, isRead = false, manuallyUnread = false),
        )
    }
}