package dev.local.catleaf.nativeapp.data.importexport

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 状态文件往返（org.json 只在设备上有真实实现，故 instrumented）。
 * 钉：格式/版本拒绝、上限拒绝（不生成不完整文件）、恶意字段不连坐、特殊字符原样往返。
 */
@RunWith(AndroidJUnit4::class)
class StateFileTest {

    private val feeds = listOf(
        StateFeed("https://a.example/feed.xml", "源 A", "科技 / 前端"),
        StateFeed("https://b.example/feed.xml", "带\"引号\"和\\反斜杠", ""),
    )
    private val articles = listOf(
        StateArticle("https://a.example/feed.xml", "g1", "https://a.example/1", "多行\n标题", "源 A", isRead = true, isStarred = false),
        StateArticle("https://b.example/feed.xml", null, "https://b.example/2", "t2", "源 B", isRead = false, isStarred = true),
        StateArticle("https://b.example/feed.xml", "g3", null, "t3", "源 B", isRead = true, isStarred = true),
    )

    @Test
    fun roundtrip_preserves_every_field() {
        val bytes = StateFile.write("instance", "https://catleaf.pages.dev", 1727000000000L, feeds, articles)
        val doc = StateFile.read(bytes).getOrThrow()
        assertEquals("https://catleaf.pages.dev", doc.sourceOrigin)
        assertEquals(1727000000000L, doc.exportedAt)
        assertEquals(feeds, doc.feeds)
        assertEquals("特殊字符必须逐字段原样往返", articles, doc.articles)
        assertEquals("干净文件不该有无效条目", emptyList<String>(), doc.invalidItems)
    }

    @Test
    fun wrong_format_or_version_is_rejected_wholesale() {
        assertTrue(StateFile.read("""{"format":"other"}""".toByteArray()).isFailure)
        assertTrue(StateFile.read("""{"format":"catweb-state","version":2}""".toByteArray()).isFailure)
        assertTrue(StateFile.read("not json at all".toByteArray()).isFailure)
    }

    @Test
    fun entry_cap_and_bytes_reject_without_partial_file() {
        val many = (1..StateFile.MAX_ENTRIES + 1).map { StateArticle("https://a.example/f.xml", "g$it", null, "t", "f", true, false) }
        val writeError = runCatching { StateFile.write("instance", "https://x", 1, emptyList(), many) }.exceptionOrNull()
        assertTrue("超条目数必须整份拒绝", writeError is StateLimitExceeded)
        assertTrue(
            "读侧同规则",
            StateFile.read(StateFile.write("instance", "https://x", 1, emptyList(), articles.take(1)).let {
                val root = JSONObject(String(it))
                val arr = root.getJSONArray("articles")
                while (arr.length() <= StateFile.MAX_ENTRIES) arr.put(JSONObject().put("feedUrl", "https://a.example/f.xml").put("guid", "pad-${arr.length()}").put("read", true))
                root.toString().toByteArray()
            }).exceptionOrNull() is StateLimitExceeded,
        )
    }

    @Test
    fun malicious_entries_are_isolated_not_fatal() {
        val json = """
        {"format":"catweb-state","version":1,"exportedAt":1,"sourceOrigin":"https://x",
         "feeds":[{"url":"javascript:alert(1)","title":"坏源"},{"url":"https://good.example/f.xml","title":"好源"}],
         "articles":[
            {"feedUrl":"https://good.example/f.xml","read":true},
            {"feedUrl":"https://good.example/f.xml","guid":"ok1","starred":true,"evil":{"nested":[{"do":"this"}]}},
            {"feedUrl":"javascript:void0","guid":"ok2","read":true}
         ]}
        """.trimIndent()
        val doc = StateFile.read(json.toByteArray()).getOrThrow()
        assertEquals("非 HTTPS 源被剔出并记录原因", 1, doc.feeds.size)
        // 无效两条：坏源地址 1 + guid/link 全缺的文章 1（文章 feedUrl 只在恢复期做映射，文件级不判）
        assertEquals(2, doc.invalidItems.size)
        assertEquals("未知字段（evil）被忽略而不是整份失败", 2, doc.articles.filter { it.guid != null }.size)
    }
}
