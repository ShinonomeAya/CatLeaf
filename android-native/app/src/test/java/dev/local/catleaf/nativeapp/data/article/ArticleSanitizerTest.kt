package dev.local.catleaf.nativeapp.data.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12 验收②的一半：恶意标签、事件属性、危险协议进入 [ArticleSanitizer] 后必须消失。
 * 每一条断言都写清期望，不用 `<= 上限` 这类弱断言——弱断言等于没测。
 *
 * 净化是安全边界，因此这里既测“恶意内容被去掉”，也测“正常内容没被误伤”
 * （误伤会让详情页看起来像提取失败，最后会被迫放宽规则）。
 */
class ArticleSanitizerTest {
    @Test fun wechat_http_proxy_uses_only_the_verified_https_qpic_target() {
        val image = "http://mmbiz.qpic.cn/mmbiz_jpg/public/640?wx_fmt=jpeg"
        val wrapped = "http://img2.jintiankansha.me/get?src=$image"
        val clean = ArticleSanitizer.sanitize("<img src='$wrapped'><p>图注</p>", null)
        assertTrue(clean.html.contains("https://mmbiz.qpic.cn/mmbiz_jpg/public/640?wx_fmt=jpeg"))
        assertTrue(clean.html.contains("图注"))
        assertEquals(clean.html, ArticleSanitizer.sanitize(clean.html, null).html)
        assertNull(ArticleSanitizer.wechatImageUrl("http://img2.jintiankansha.me/get?src=http://127.0.0.1/private"))
        assertNull(ArticleSanitizer.wechatImageUrl("http://img2.jintiankansha.me/get?src=http://mmbiz.qpic.cn.evil.test/mmbiz_jpg/a"))
        assertNull(ArticleSanitizer.wechatImageUrl("http://mmbiz.qpic.cn@evil.test/mmbiz_jpg/a"))
        assertNull(ArticleSanitizer.wechatImageUrl("http://mmbiz.qpic.cn:8080/mmbiz_jpg/a"))
        assertNull(ArticleSanitizer.wechatImageUrl("https://ordinary.example/image.jpg"))
        assertNull(ArticleSanitizer.wechatImageUrl("http://img2.jintiankansha.me.evil.test/get?src=$image"))
    }

    @Test fun wordpress_emoji_is_text_in_new_and_already_cleaned_rss() {
        val old = """<p><img src="https://s.w.org/images/core/emoji/11/72x72/1f4f1.png" alt="📱">手机</p>"""
        val raw = old.replace("alt=", "class=\"wp-smiley\" style=\"height:1em\" alt=")
        for (html in listOf(old, raw)) {
            val result = ArticleSanitizer.sanitize(html, "https://www.ifanr.com/1682292")
            assertEquals("📱手机", result.plainText)
            assertEquals(0, result.imageCount)
            assertEquals(result.html, ArticleSanitizer.sanitize(result.html, null).html)
        }
    }

    @Test fun emoji_variants_and_zwj_are_preserved_without_removing_normal_images() {
        val raw = """<p><img src="https://s.w.org/images/core/emoji/11/72x72/2764.png" alt="❤️">
            <img src="https://s.w.org/images/core/emoji/11/72x72/1f469-200d-1f4bb.png" alt="👩‍💻"></p>
            <figure><img src="https://example.org/photo.png" alt="照片"><figcaption>图注</figcaption></figure>"""
        val result = ArticleSanitizer.sanitize(raw, null)
        assertTrue(result.plainText.contains("❤️"))
        assertTrue(result.plainText.contains("👩‍💻"))
        assertTrue(result.plainText.contains("图注"))
        assertEquals(1, result.imageCount)
        assertTrue(result.html.contains("https://example.org/photo.png"))
    }

    @Test fun fake_emoji_sources_or_mismatched_alt_remain_images_without_html_injection() {
        val result = ArticleSanitizer.sanitize("""<p>
            <img src="https://s.w.org.evil.example/images/core/emoji/11/72x72/1f4f1.png" alt="📱">
            <img src="https://s.w.org/images/core/emoji/11/72x72/1f4f1.png" alt="&lt;script&gt;bad&lt;/script&gt;">
            <img src="https://s.w.org/other/1f4f1.png" alt="📱">
            <img src="https://user:pass@s.w.org/images/core/emoji/11/72x72/1f4f1.png" alt="📱">
            </p>""", null)
        assertEquals(4, result.imageCount)
        assertFalse(result.html.contains("<script>"))
        assertFalse(result.html.contains("style="))
    }

    private val articleUrl = "https://blog.example.com/posts/hello"

    private fun sanitize(html: String, base: String? = articleUrl) =
        ArticleSanitizer.sanitize(html, base)

    // ── 危险容器 ──

    @Test
    fun script_and_style_are_removed_with_their_content() {
        val out = sanitize(
            """<p>正文一</p><script>var x = "alert(1)"; document.write('pwned')</script>
               <style>body{background:url("https://evil.example/x.png")}</style><p>正文二</p>""",
        )
        assertFalse("脚本元素必须整体消失", out.html.contains("<script", ignoreCase = true))
        assertFalse("脚本文本不得作为正文保留", out.html.contains("alert(1)"))
        assertFalse("脚本不得执行 document.write", out.html.contains("document.write"))
        assertFalse("样式元素必须整体消失", out.html.contains("<style", ignoreCase = true))
        assertFalse("样式里的外部地址不得保留", out.html.contains("evil.example"))
        assertEquals(
            "正文文本应完整保留（忽略标签留下的空白）",
            "正文一正文二",
            out.html.replace(Regex("<[^>]+>"), "").filterNot { it.isWhitespace() },
        )
    }

    @Test
    fun frames_objects_and_forms_are_removed() {
        val out = sanitize(
            """<p>正文</p><iframe src="https://evil.example/frame"></iframe>
               <object data="https://evil.example/o.swf"></object>
               <embed src="https://evil.example/e.swf">
               <form action="https://evil.example/steal"><input name="q" value="x"><button>提交</button></form>
               <svg onload="alert(1)"><circle r="1"/></svg>""",
        )
        for (tag in listOf("iframe", "object", "embed", "form", "input", "button", "svg")) {
            assertFalse("$tag 标签必须被删除", out.html.contains("<$tag", ignoreCase = true))
        }
        assertFalse("不允许外部地址出现在正文里", out.html.contains("evil.example"))
        assertFalse("svg 上的 onload 不得保留", out.html.contains("onload"))
    }

    // ── 事件属性 ──

    @Test
    fun event_attributes_are_removed_from_allowed_tags() {
        val out = sanitize(
            """<p onclick="alert(1)">段落</p>
               <img src="https://cdn.example.com/a.png" onerror="alert('x')" onload="alert('y')" alt="图">
               <a href="https://example.com/page" onmouseover="alert(1)">链接</a>""",
        )
        for (handler in listOf("onclick", "onerror", "onload", "onmouseover")) {
            assertFalse("$handler 事件属性必须被删除", out.html.contains(handler, ignoreCase = true))
        }
        assertFalse("事件属性里的脚本内容不得保留", out.html.contains("alert"))
    }

    @Test
    fun unquoted_event_attribute_is_also_removed() {
        val out = sanitize("<img src=x onerror=alert(1)><p>正文</p>")
        assertFalse("无引号的事件属性同样要删除", out.html.contains("onerror", ignoreCase = true))
        assertFalse("事件属性里的脚本内容不得保留", out.html.contains("alert"))
        assertTrue(
            "相对图片地址应解析到文章站上，而不是保留成无法判断的裸地址",
            out.html.contains("""src="https://blog.example.com/posts/x""""),
        )
    }

    @Test
    fun form_controls_are_unwrapped_without_losing_text() {
        val out = sanitize(
            """<form action="https://evil.example/steal"><p>表单里的正文</p><input name="q"></form><p>后面的正文</p>""",
        )
        assertFalse("form 标签必须删除", out.html.contains("<form", ignoreCase = true))
        assertFalse("input 控件必须删除", out.html.contains("<input", ignoreCase = true))
        assertFalse("表单提交目标不得保留", out.html.contains("evil.example"))
        assertTrue("表单里的正文不能一起丢掉", out.html.contains("表单里的正文"))
        assertTrue("表单外的正文保留", out.html.contains("后面的正文"))
    }

    @Test
    fun multi_url_attributes_and_document_level_redirects_are_dropped() {
        val out = sanitize(
            """<meta http-equiv="refresh" content="0;url=https://evil.example/"><base href="https://evil.example/">
               <link rel="stylesheet" href="https://evil.example/x.css">
               <img src="https://cdn.example.com/a.png" srcset="https://evil.example/b.png 2x">""",
        )
        assertFalse("meta 必须删除", out.html.contains("<meta", ignoreCase = true))
        assertFalse("base 必须删除（否则会改掉所有相对地址的解析基准）", out.html.contains("<base", ignoreCase = true))
        assertFalse("外部样式表必须删除", out.html.contains("<link", ignoreCase = true))
        assertFalse("srcset 里的备用地址不得保留", out.html.contains("evil.example"))
        assertTrue("图片本身保留", out.html.contains("https://cdn.example.com/a.png"))
    }

    // ── 危险协议 ──

    @Test
    fun dangerous_protocols_are_removed_from_links_and_images() {
        val samples = mapOf(
            "javascript: 小写" to """<a href="javascript:alert(1)">x</a>""",
            "JaVaScRiPt: 大小写混合" to """<a href="JaVaScRiPt:alert(1)">x</a>""",
            "javascript: 带制表符" to "<a href=\"java\tscript:alert(1)\">x</a>",
            "javascript: 带换行" to "<a href=\"java\nscript:alert(1)\">x</a>",
            "实体编码的 javascript:" to """<a href="&#106;avascript:alert(1)">x</a>""",
            "data: 文本" to """<a href="data:text/html,<script>alert(1)</script>">x</a>""",
            "data: 图片" to """<img src="data:image/svg+xml,<svg onload=alert(1)>">""",
            "vbscript:" to """<a href="vbscript:msgbox(1)">x</a>""",
            "file: 本地文件" to """<a href="file:///etc/passwd">x</a>""",
            "intent: 跳转" to """<a href="intent://evil#Intent;scheme=https;end">x</a>""",
            "content: 提供者" to """<a href="content://com.example/secret">x</a>""",
            "带凭据的地址" to """<a href="https://user:pass@example.com/x">x</a>""",
        )
        for ((label, html) in samples) {
            val out = sanitize(html)
            for (protocol in listOf("javascript:", "vbscript:", "file:", "intent:", "content:", "user:pass")) {
                assertFalse("$label：结果里不得残留 $protocol", out.html.contains(protocol, ignoreCase = true))
            }
            assertFalse("$label：结果里不得残留 data: 载荷", out.html.contains("data:text/html"))
        }
    }

    @Test
    fun dropped_url_attributes_are_counted() {
        // 含空白的伪协议（"jav ascript:"）会被解析成文章站上的相对地址，协议白名单拦不住，
        // 由复核阶段删除并计数；这是这一步存在的直接证据。
        val out = sanitize("""<a href="jav ascript:alert(1)">x</a>""")
        assertEquals("复核阶段应删除该地址", 1, out.droppedUrlAttributes)
        assertFalse("结果里不得残留这个地址", out.html.contains("ascript"))
    }

    // ── 正常内容与地址解析 ──

    @Test
    fun allowed_structure_survives() {
        val out = sanitize(
            """<h1>标题</h1><h2>小标题</h2><p>段落<strong>粗</strong><em>斜</em></p>
               <ul><li>一</li><li>二</li></ul><ol start="3"><li>三</li></ol>
               <blockquote cite="https://example.com/src">引用</blockquote>
               <pre><code>val x = 1</code></pre>
               <table><thead><tr><th scope="col">表头</th></tr></thead>
                 <tbody><tr><td colspan="2">单元格</td></tr></tbody></table>""",
        )
        for (fragment in listOf("<h1>", "<h2>", "<strong>", "<em>", "<ul>", "<li>", "<ol start=\"3\">",
                "<blockquote", "<pre>", "<code>", "<table>", "<th scope=\"col\">", "<td colspan=\"2\">")) {
            assertTrue("应保留 $fragment", out.html.contains(fragment))
        }
        assertEquals("表格单元格文本应保留", true, out.html.contains("单元格"))
    }

    @Test
    fun relative_urls_are_resolved_against_the_verified_article_url() {
        val out = sanitize(
            """<a href="/about">关于</a><img src="images/pic.png" alt="图">
               <a href="../other">上一篇</a>""",
        )
        assertTrue("根相对地址应转成绝对地址", out.html.contains("https://blog.example.com/about"))
        assertTrue("路径相对地址应转成绝对地址", out.html.contains("https://blog.example.com/posts/images/pic.png"))
        assertTrue("上一级相对地址应转成绝对地址", out.html.contains("https://blog.example.com/other"))
    }

    @Test
    fun relative_urls_are_dropped_without_a_verified_base() {
        val out = sanitize("""<a href="/about">关于</a><img src="pic.png" alt="说明">""", base = null)
        assertFalse("没有已验证的文章地址时不得合成地址", out.html.contains("/about"))
        assertFalse("没有基准地址时图片地址应删除", out.html.contains("pic.png"))
        assertTrue("替代说明（alt）要保留", out.html.contains("alt=\"说明\""))
        assertEquals("链接文字不应因删除属性而丢失", true, out.html.contains("关于"))
    }

    @Test
    fun styles_and_classes_are_not_part_of_the_body() {
        val out = sanitize(
            """<p style="position:fixed;top:0" class="ad" id="x" data-evil="1">正文</p>""",
        )
        for (attribute in listOf("style=", "class=", "id=", "data-evil=")) {
            assertFalse("$attribute 属性不应出现（排版由 App 模板提供）", out.html.contains(attribute))
        }
        assertTrue("文本要保留", out.html.contains("正文"))
    }

    @Test
    fun image_alt_and_dimensions_are_kept_for_placeholders() {
        val out = sanitize(
            """<img src="https://cdn.example.com/a.png" alt="封面图" width="800" height="450" loading="eager">""",
        )
        assertTrue("alt 是图片失败时的替代说明，必须保留", out.html.contains("alt=\"封面图\""))
        assertTrue("width 保留", out.html.contains("width=\"800\""))
        assertTrue("height 保留", out.html.contains("height=\"450\""))
        assertEquals("图片计数应精确", 1, out.imageCount)
    }

    // ── 计数与空正文判定 ──

    @Test
    fun empty_input_is_reported_as_empty() {
        assertEquals("null 正文应判为空", true, sanitize("").isEmpty)
        assertEquals("纯空白正文应判为空", true, sanitize("   \n ").isEmpty)
        assertEquals("只有脚本的正文应判为空（否则会把脚本文本当正文）", true, sanitize("<script>x</script>").isEmpty)
        assertEquals("有文本就不算空", false, sanitize("<p>正文</p>").isEmpty)
    }

    @Test
    fun text_length_counts_visible_text_only() {
        val out = sanitize("<p>四个汉字</p><script>1234567890</script>")
        assertEquals("文本长度只算可见文字", 4, out.textLength)
    }

    @Test
    fun lazy_loaded_images_get_a_real_src() {
        // 真实站点常见写法：src 是内联占位图或干脆为空，真实地址在 srcset / data-src 里
        val bySrcset = sanitize("""<img srcset="/img/a-800.jpg 800w, /img/a-1600.jpg 1600w" alt="图">""")
        assertTrue(
            "没有 src 时应从 srcset 里补出地址：${bySrcset.html}",
            bySrcset.html.contains("https://blog.example.com/img/a-1600.jpg"),
        )
        assertFalse("srcset 本身不应保留在正文里", bySrcset.html.contains("srcset"))

        val byDataSrc = sanitize("""<img src="data:image/gif;base64,R0lGODlh" data-src="/img/real.png" alt="图">""")
        assertTrue(
            "内联占位图应被 data-src 取代：${byDataSrc.html}",
            byDataSrc.html.contains("https://blog.example.com/img/real.png"),
        )
        assertFalse("内联 data: 地址不得作为图片地址保留", byDataSrc.html.contains("data:image"))

        val picture = sanitize("""<picture><source srcset="/img/p.webp 1x"><img alt="图"></picture>""")
        assertTrue(
            "picture 里的 img 应继承 source 的地址：${picture.html}",
            picture.html.contains("https://blog.example.com/img/p.webp"),
        )
        assertFalse("picture/source 标签不应保留", picture.html.contains("<picture") || picture.html.contains("<source"))
        assertEquals("补出地址后仍算一张图", 1, picture.imageCount)
    }

    @Test
    fun absolute_url_helper_rejects_non_http_schemes() {
        assertEquals(
            "https 地址应原样通过",
            "https://example.com/a?b=1",
            ArticleSanitizer.absoluteUrl("https://example.com/a?b=1", articleUrl),
        )
        assertNull("javascript: 不接受", ArticleSanitizer.absoluteUrl("javascript:alert(1)", articleUrl))
        assertNull("data: 不接受", ArticleSanitizer.absoluteUrl("data:text/html,x", articleUrl))
        assertNull("空值不接受", ArticleSanitizer.absoluteUrl("  ", articleUrl))
        assertNull("协议相对地址没有基准时无法判定", ArticleSanitizer.absoluteUrl("//example.com/x", null))
        assertEquals(
            "协议相对地址在有基准时按 https 解析",
            "https://example.com/x",
            ArticleSanitizer.absoluteUrl("//example.com/x", articleUrl),
        )
    }
}
