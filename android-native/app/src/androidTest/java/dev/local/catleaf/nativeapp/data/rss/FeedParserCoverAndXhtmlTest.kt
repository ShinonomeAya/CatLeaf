package dev.local.catleaf.nativeapp.data.rss

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T22 返修 R1 的最小回归样本（全部为合成地址，无真实凭据）：
 * - F02：图片 enclosure 映射为封面；音频/非图片/危险地址不映射；
 * - F19：Atom `content type="xhtml"` 保留子树标记，转义 html 与纯文本行为不变；
 *   子树消耗正确——后面的条目照常解析。
 */
@RunWith(AndroidJUnit4::class)
class FeedParserCoverAndXhtmlTest {
    @Test fun item_image_is_resolved_and_channel_icon_is_not_an_article_cover() {
        val xml = """<rss><channel><title>源</title><image><url>https://example.org/icon.png</url></image>
            <item><guid>1</guid><image>/cover.png</image></item><item><guid>2</guid></item>
            <item><guid>3</guid><image>https://127.0.0.1/a.png</image></item></channel></rss>"""
        val feed = FeedParser.parse(xml, "https://example.org/feed").getOrThrow()
        assertEquals("https://example.org/cover.png", feed.items[0].thumbnail)
        assertNull(feed.items[1].thumbnail)
        assertNull(feed.items[2].thumbnail)
    }

    @Test fun atom_xhtml_summary_preserves_markup_for_sanitizer() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>GitLab</title>
            <entry><id>summary-1</id><title>Activity</title>
              <summary type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
                <p>Summary <a href="https://example.org/story">link</a></p>
              </div></summary>
            </entry></feed>"""
        val item = FeedParser.parse(xml).getOrThrow().items.single()
        val clean = ArticleSanitizer.sanitize(item.description, "https://example.org/story")
        assertTrue(clean.html.contains("<p>"))
        assertTrue(clean.html.contains("<a href="))
        assertTrue(clean.plainText.contains("Summary link"))
    }

    private fun rss(itemsXml: String) = """
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel><title>t</title>
        $itemsXml
          </channel></rss>
    """.trimIndent()

    // ── F02：enclosure → 封面 ─────────────────────────────────────

    @Test
    fun image_enclosure_becomes_cover() {
        val xml = rss(
            """
            <item><title>带封面</title><guid>c1</guid>
              <content:encoded><![CDATA[<p>正文，没有内嵌图</p>]]></content:encoded>
              <enclosure url="https://blog.example.org/img/cover.png" type="image/png" length="55156" />
            </item>
            """.trimIndent(),
        )
        val item = FeedParser.parse(xml).getOrThrow().items[0]
        assertEquals("image/png enclosure 应映射为封面", "https://blog.example.org/img/cover.png", item.thumbnail)
    }

    @Test
    fun audio_and_video_enclosures_are_not_covers() {
        val xml = rss(
            """
            <item><title>播客</title><guid>a1</guid>
              <enclosure url="https://pod.example.org/ep.mp3" type="audio/mpeg" length="10" />
            </item>
            <item><title>视频</title><guid>a2</guid>
              <enclosure url="https://v.example.org/clip.gifv" type="video/mp4" length="10" />
            </item>
            """.trimIndent(),
        )
        val parsed = FeedParser.parse(xml).getOrThrow()
        assertNull("音频附件不能当图片", parsed.items[0].thumbnail)
        assertNull("视频附件不能当图片；扩展名像图也不行", parsed.items[1].thumbnail)
    }

    @Test
    fun enclosure_without_type_falls_back_to_extension() {
        val xml = rss(
            """
            <item><title>无类型</title><guid>n1</guid>
              <enclosure url="https://img.example.org/photo.jpg" />
            </item>
            <item><title>类型不是图片</title><guid>n2</guid>
              <enclosure url="https://img.example.org/thing.jpg" type="application/octet-stream" />
            </item>
            """.trimIndent(),
        )
        val parsed = FeedParser.parse(xml).getOrThrow()
        assertEquals("无 type 才允许看扩展名", "https://img.example.org/photo.jpg", parsed.items[0].thumbnail)
        assertNull("有 type 且非 image/* 时扩展名不算数", parsed.items[1].thumbnail)
    }

    @Test
    fun unsafe_cover_urls_are_dropped() {
        val xml = rss(
            """
            <item><title>http 图</title><guid>u1</guid>
              <enclosure url="http://insecure.example.org/a.png" type="image/png" /></item>
            <item><title>伪协议</title><guid>u2</guid>
              <enclosure url="javascript:alert(1)" type="image/png" /></item>
            <item><title>内网</title><guid>u3</guid>
              <media:thumbnail xmlns:media="http://search.yahoo.com/mrss/" url="https://127.0.0.1/x.png" /></item>
            """.trimIndent(),
        )
        val parsed = FeedParser.parse(xml).getOrThrow()
        assertNull("非 https 图片地址丢弃", parsed.items[0].thumbnail)
        assertNull("伪协议丢弃", parsed.items[1].thumbnail)
        assertNull("内网地址丢弃", parsed.items[2].thumbnail)
    }

    // ── F19：Atom content 的三种 type ─────────────────────────────

    private fun atom(contents: List<String>) = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Atom 源</title>
          ${contents.mapIndexed { i, body -> """
          <entry><title>条${i + 1}</title><id>tag:e,$i</id>
            <link href="https://e.example.org/$i" />$body</entry>
          """ }.joinToString("\n") }
        </feed>
    """.trimIndent()

    @Test
    fun atom_escaped_html_content_keeps_markup() {
        val xml = atom(
            listOf("""<content type="html">&lt;p&gt;段落&lt;/p&gt;&lt;img src="https://e.example.org/i.png"/&gt;</content>"""),
        )
        val content = FeedParser.parse(xml).getOrThrow().items[0].content!!
        assertTrue("转义 html 的标签要还原为标记：$content", content.contains("<p>段落</p>"))
        assertTrue("img 存活", content.contains("i.png"))
    }

    @Test
    fun atom_xhtml_content_keeps_subtree_and_stops_at_its_end() {
        val xml = atom(
            listOf(
                """
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
                  <p>第一段 &amp; 符号</p>
                  <p>带 <a href="https://e.example.org/go">链接</a> 的段落</p>
                  <img src="https://e.example.org/x.png" alt="说明" />
                  <br/>
                  <table><tr><td>表格</td></tr></table>
                </div></content>
                """.trimIndent(),
            ) + listOf("<summary>下一条的 summary</summary>"),
        )
        val parsed = FeedParser.parse(xml).getOrThrow()
        assertEquals("子树消耗后下一条照常解析", 2, parsed.items.size)

        val content = parsed.items[0].content!!
        assertTrue("内联段落保留标签：$content", content.contains("<p>第一段 &amp; 符号</p>"))
        assertTrue("链接保留：$content", content.contains("<a href=\"https://e.example.org/go\">链接</a>"))
        assertTrue("图片保留：$content", content.contains("src=\"https://e.example.org/x.png\""))
        assertTrue("表格保留：$content", content.contains("<td>表格</td>"))
        assertTrue("自闭合 br 保留为标记：$content", content.contains("<br"))
        assertTrue("命名空间声明不外泄", !content.contains("xmlns"))

        // 净化链：xhtml 子树交唯一净化入口后危险内容被剥掉、图片/链接存活
        val clean = ArticleSanitizer.sanitize(content, "https://e.example.org/1")
        assertTrue("净化后 img 存活", clean.html.contains("x.png"))
        assertTrue("净化后 a 存活", clean.html.contains("e.example.org/go"))
        assertTrue("文字完整", clean.html.contains("第一段 &amp; 符号"))
    }

    @Test
    fun atom_xhtml_dangerous_subtree_neutralized_by_sanitizer() {
        val xml = atom(
            listOf(
                """
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
                  <p>可读段落</p>
                  <script>alert(1)</script>
                  <img src="https://e.example.org/ok.png" onerror="steal()" />
                </div></content>
                """.trimIndent(),
            ),
        )
        val content = FeedParser.parse(xml).getOrThrow().items[0].content!!
        val clean = ArticleSanitizer.sanitize(content, "https://e.example.org/0")
        assertTrue("正文保留", clean.html.contains("可读段落"))
        assertTrue("script 连内容删除", !clean.html.contains("alert"))
        assertTrue("图片保留", clean.html.contains("ok.png"))
        assertTrue("事件属性被剥", !clean.html.contains("onerror"))
    }

    @Test
    fun atom_xhtml_with_namespace_prefixes_is_normalized_then_sanitized() {
        // V07（独立验收）：带前缀的 XHTML 子树——按 xmlns 声明识别命名空间，
        // 只有解析到 XHTML URI 的元素还原成本地名进白名单；其他前缀不盲目剥离。
        val xml = atom(
            listOf(
                """
                <content type="xhtml"><xhtml:div xmlns:xhtml="http://www.w3.org/1999/xhtml">
                  <xhtml:p>前缀段落 &amp; 符号</xhtml:p>
                  <xhtml:img src="https://e.example.org/p.png" alt="图"/>
                  <xhtml:a href="https://e.example.org/go">链接</xhtml:a>
                  <xhtml:table><xhtml:tr><xhtml:td>单元格</xhtml:td></xhtml:tr></xhtml:table>
                  <other:thing xmlns:other="urn:not-xhtml">未知命名空间</other:thing>
                </xhtml:div></content>
                """.trimIndent(),
            ) + listOf("<summary>下一条</summary>"),
        )
        val parsed = FeedParser.parse(xml).getOrThrow()
        assertEquals("子树消耗后下一条照常解析", 2, parsed.items.size)
        val content = parsed.items[0].content!!
        assertTrue("xhtml:p 还原为 p：$content", content.contains("<p>前缀段落 &amp; 符号</p>"))
        assertTrue("xhtml:img 还原为 img", content.contains("<img src=\"https://e.example.org/p.png\""))
        assertTrue("xhtml:table 还原为 table", content.contains("<td>单元格</td>"))
        assertTrue("非 XHTML 标签交给净化器删", content.contains("nonxhtml:thing"))

        val clean = ArticleSanitizer.sanitize(content, "https://e.example.org/0")
        assertTrue("净化后图片存活", clean.html.contains("p.png"))
        assertTrue("净化后链接存活", clean.html.contains("e.example.org/go"))
        assertTrue("净化后段落存活", clean.html.contains("前缀段落"))
        assertTrue("未知前缀元素被剥", !clean.html.contains("other"))
    }

    @Test
    fun atom_xhtml_inherits_and_restores_namespace_scopes() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:x="http://www.w3.org/1999/xhtml">
              <entry xmlns:y="http://www.w3.org/1999/xhtml"><id>one</id><title>one</title>
                <content type="xhtml"><x:div>
                  <x:p>ancestor</x:p><y:img src="https://e.example.org/ancestor.png"/>
                  <x:p xmlns:x="urn:other">foreign</x:p>
                  <x:p>restored</x:p>
                  <div xmlns="http://www.w3.org/1999/xhtml"><p>default xhtml</p></div>
                  <p>atom p must not become html p</p>
                </x:div></content>
              </entry>
              <entry><id>two</id><title>two</title><content type="html">&lt;p&gt;next&lt;/p&gt;</content></entry>
            </feed>
        """.trimIndent()
        val parsed = FeedParser.parse(xml).getOrThrow()
        assertEquals(2, parsed.items.size)
        val content = parsed.items[0].content!!
        val clean = ArticleSanitizer.sanitize(content, "https://e.example.org/one")
        assertTrue(clean.html.contains("<p>ancestor</p>"))
        assertTrue(clean.html.contains("ancestor.png"))
        assertTrue(clean.html.contains("<p>restored</p>"))
        assertTrue(clean.html.contains("<p>default xhtml</p>"))
        assertTrue("non-XHTML p is not a paragraph", !clean.html.contains("<p>atom p"))
        assertTrue("rebound prefix is not a paragraph", !clean.html.contains("<p>foreign"))
        assertEquals("<p>next</p>", parsed.items[1].content)
    }

    @Test
    fun atom_text_content_is_plain_and_other_fields_stay_text() {
        val xml = atom(
            listOf("""<content type="text">纯文本正文</content>"""),
        )
        val item = FeedParser.parse(xml).getOrThrow().items[0]
        assertEquals("type=text 按纯文本读", "纯文本正文", item.content)
        assertEquals("标题始终是文本", "条1", item.title)
    }
}
