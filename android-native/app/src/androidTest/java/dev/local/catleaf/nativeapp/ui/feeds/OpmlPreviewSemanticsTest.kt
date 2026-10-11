package dev.local.catleaf.nativeapp.ui.feeds

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.catleaf.nativeapp.data.opml.Opml
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VR6：OPML 预览每行是一个带名称的复选框（标题 + 原因合并），禁用行仍能读出跳过原因；
 * 原先复选框单独成节点、没有名称，读屏只念「复选框」。
 */
@RunWith(AndroidJUnit4::class)
class OpmlPreviewSemanticsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun each_row_is_one_named_checkbox_and_skipped_rows_keep_their_reason() {
        val doc = Opml.Document(
            title = "隔离",
            entries = listOf(
                Opml.Entry(title = "隔离导入源 A", xmlUrl = "https://example.invalid/a.xml", group = "技术"),
                Opml.Entry(title = "隔离本机地址", xmlUrl = "https://localhost/x.xml", invalidReason = "不接受本机或内网地址"),
            ),
            invalidCount = 1,
        )
        var selected by mutableStateOf(setOf(0))
        rule.setContent {
            CatLeafTheme {
                OpmlPreviewSurface(
                    document = doc, selected = selected, importing = false, progressDone = 0, progressTotal = 0,
                    localOnly = true,
                    onToggle = { i -> selected = if (i in selected) selected - i else selected + i },
                    onToggleAll = {}, onStart = {}, onDismiss = {},
                )
            }
        }

        val rowA = rule.onNode(isToggleable() and hasText("隔离导入源 A", substring = true))
        rowA.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        rowA.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        rowA.assertIsEnabled()
        rowA.performClick()
        rowA.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        // 禁用行：同一节点带标题与跳过原因，但不可切换
        rule.onNode(isToggleable() and hasText("隔离本机地址", substring = true) and hasText("将跳过", substring = true))
            .assertIsNotEnabled()
    }
}
