package dev.local.catleaf.nativeapp.ui.theme

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VR6：LabeledField 的标签画在输入框外，输入框本身要带同名无障碍名称，
 * 否则读屏只会念「编辑框」（原 UI 树里 EditText 的 text/content-desc 都为空）。
 */
@RunWith(AndroidJUnit4::class)
class LabeledFieldSemanticsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun input_carries_visible_label_as_accessible_name() {
        var value = ""
        rule.setContent {
            CatLeafTheme {
                LabeledField(label = "订阅地址", value = value, onValueChange = { value = it }, placeholder = "https://example.com/feed.xml")
            }
        }
        val field = rule.onNode(hasSetTextAction() and hasContentDescription("订阅地址"))
        field.assertExists()
        field.performTextInput("https://a.example/feed")
        rule.waitForIdle()
        org.junit.Assert.assertEquals("https://a.example/feed", value)
    }
}
