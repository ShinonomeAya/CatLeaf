package dev.local.catleaf.nativeapp.data.opml

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.catleaf.nativeapp.data.FeedStore
import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.network.FeedApiError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T09 的 OPML 解析、去重与串行导入检查。
 * 样本由本测试构造（工作区与机器上都没有现成 OPML 文件），覆盖契约点名的边界。
 */
@RunWith(AndroidJUnit4::class)
class OpmlTest {

    private lateinit var server: MockWebServer
    private val secret = "FAKE-INSTANCE-SECRET-opml-test"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    /**
     * 构造样本。刻意用字符串拼接而不是 trimIndent：含插值的模板会让整体缩进为 0，
     * 使 XML 声明前多出空格——声明必须在文档最开头，否则是非法 XML。
     */
    private fun sample(
        body: String = BODY_WITH_GROUPS,
        title: String = "我的订阅",
    ): String = listOf(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
        "<opml version=\"2.0\">",
        "  <head><title>$title</title></head>",
        "  <body>",
        body,
        "  </body>",
        "</opml>",
        "",
    ).joinToString("\n")

    private val BODY_WITH_GROUPS = """
          <outline text="设计与阅读" title="设计与阅读">
            <outline type="rss" text="设计札记" xmlUrl="https://a.example.org/feed.xml"
                     htmlUrl="https://a.example.org/" />
            <outline text="子分组">
              <outline type="rss" text="阅读周刊" xmlUrl="https://b.example.org/rss" />
            </outline>
          </outline>
          <outline type="rss" text="技术手记" xmlUrl="https://c.example.org/atom.xml" />
    """.trimIndent()

    // ── 解析 ──────────────────────────────────────────────────────

    @dev.local.catleaf.nativeapp.ManualProbe
    @Test fun public_microsoft_sample_parses_and_keeps_http_entries_flagged() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = context.cacheDir.resolve("public-microsoft.opml").readBytes()
        val document = Opml.parse(fixture).getOrThrow()
        assertEquals(20, document.entries.size)
        assertEquals(setOf("News", "Leisure", "Sports", "Tech"), document.entries.map { it.group }.toSet())
        assertTrue(document.entries.all { !it.selectable && it.invalidReason == "不是有效的 HTTPS 地址" })
    }

    @Test fun extension_attributes_are_ignored_but_web_scraping_configuration_is_not_mislabelled_as_rss() {
        val doc = Opml.parse(bytes(sample(body = """
            <outline xmlns:frss="https://freshrss.org/opml" type="rss" text="订阅" xmlUrl="https://feeds.example.org/rss" frss:priority="main"/>
            <outline xmlns:frss="https://freshrss.org/opml" type="HTML+XPath" text="网页配置" xmlUrl="https://www.example.org/page" frss:xPathItem="//article"/>
        """.trimIndent()))).getOrThrow()
        assertTrue(doc.entries[0].selectable)
        assertFalse(doc.entries[1].selectable)
        assertTrue(doc.entries[1].invalidReason!!.contains("其他抓取配置"))
    }

    @Test fun parses_bom_and_explicit_utf16_without_losing_chinese_groups() {
        val xml = sample()
        val utf8Bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes(xml)
        assertEquals("设计与阅读 / 子分组", Opml.parse(utf8Bom).getOrThrow().entries[1].group)
        for (charset in listOf(Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val encoded = xml.replace("UTF-8", charset.name()).toByteArray(charset)
            assertEquals("我的订阅", Opml.parse(encoded).getOrThrow().title)
        }
        assertTrue(Opml.parse(bytes(xml.replace("UTF-8", "ISO-8859-1"))).isFailure)
    }

    @Test fun rejects_non_opml_and_declarations_beyond_the_old_prefix_budget() {
        assertTrue(Opml.parse(bytes("<html><body><outline xmlUrl=\"https://a.example.org/rss\"/></body></html>")).isFailure)
        assertTrue(Opml.parse(bytes("<opml><head/></opml>")).isFailure)
        val padded = "<!--" + " ".repeat(5000) + "-->\n<!DOCTYPE opml><opml><body/></opml>"
        assertTrue(Opml.parse(bytes(padded)).exceptionOrNull() is Opml.OpmlError.DoctypeForbidden)
    }

    @Test fun accepts_large_subscription_lists_but_rejects_entry_overflow() {
        fun list(count: Int) = sample(body = (1..count).joinToString("\n") {
            "<outline text=\"源$it\" xmlUrl=\"https://feeds.example.org/$it\"/>"
        })
        assertEquals(201, Opml.parse(bytes(list(201))).getOrThrow().entries.size)
        assertEquals(2000, Opml.parse(bytes(list(2000))).getOrThrow().entries.size)
        assertTrue(Opml.parse(bytes(list(2001))).exceptionOrNull() is Opml.OpmlError.TooManyEntries)
    }

    @Test
    fun parses_title_entries_and_group_paths() {
        val doc = Opml.parse(bytes(sample())).getOrThrow()

        assertEquals("我的订阅", doc.title)
        assertEquals(3, doc.entries.size)

        val first = doc.entries[0]
        assertEquals("设计札记", first.title)
        assertEquals("https://a.example.org/feed.xml", first.xmlUrl)
        assertEquals("设计与阅读", first.group)
        assertEquals("https://a.example.org/", first.htmlUrl)
        assertTrue("正常条目应可勾选", first.selectable)

        assertEquals("多级分组拼成「父 / 子」", "设计与阅读 / 子分组", doc.entries[1].group)
        assertEquals("顶层条目分组为空", "", doc.entries[2].group)
        assertEquals(0, doc.invalidCount)
    }

    @Test
    fun rejects_doctype_and_entity_declarations() {
        val withDoctype = """
            <?xml version="1.0"?>
            <!DOCTYPE opml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <opml version="2.0"><body>
              <outline type="rss" text="坏" xmlUrl="https://a.example.org/&xxe;" />
            </body></opml>
        """.trimIndent()
        val r1 = Opml.parse(bytes(withDoctype))
        assertTrue(r1.exceptionOrNull() is Opml.OpmlError.DoctypeForbidden)

        val withEntity = """<!ENTITY foo "bar">"""
        val r2 = Opml.parse(bytes("<?xml version=\"1.0\"?>\n$withEntity\n<opml><body/></opml>"))
        assertTrue(r2.exceptionOrNull() is Opml.OpmlError.DoctypeForbidden)
    }

    @Test
    fun local_and_private_targets_are_flagged_before_import() {
        // 契约：拒绝 localhost/私有/回环地址；预览阶段就标出原因且不可勾选，不等写入时才失败
        val doc = Opml.parse(bytes("""
            <opml version="2.0"><body>
              <outline type="rss" text="本机" xmlUrl="https://localhost/rss" />
              <outline type="rss" text="内网" xmlUrl="https://192.168.1.2/rss" />
              <outline type="rss" text="公开" xmlUrl="https://feeds.example.org/rss" />
            </body></opml>
        """.trimIndent())).getOrThrow()
        val (local, lan, public) = doc.entries
        assertEquals("不接受本机或内网地址", local.invalidReason)
        assertFalse(local.selectable)
        assertEquals("不接受本机或内网地址", lan.invalidReason)
        assertFalse(lan.selectable)
        assertNull(public.invalidReason)
        assertTrue(public.selectable)
    }

    @Test
    fun rejects_oversized_and_malformed_and_non_utf8() {
        val tooLarge = Opml.parse(ByteArray(Opml.MAX_BYTES + 1) { 'a'.code.toByte() })
        assertTrue("超 2 MiB 应拒绝", tooLarge.exceptionOrNull() is Opml.OpmlError.TooLarge)

        val malformed = Opml.parse(bytes("<opml><body><outline unclosed"))
        assertTrue("解析错误应拒绝", malformed.exceptionOrNull() is Opml.OpmlError.Malformed)

        // 非法 UTF-8 字节序列（0xC3 后面缺续字节）
        val badUtf8 = byteArrayOf(0x3C, 0xC3.toByte(), 0x3E)
        assertTrue("非 UTF-8 应拒绝", Opml.parse(badUtf8).exceptionOrNull() is Opml.OpmlError.NotUtf8)
    }

    @Test
    fun flags_non_https_and_overlong_group_without_truncating() {
        val longGroup = "很长的分组名".repeat(20) // > 80 字符
        val doc = Opml.parse(
            bytes(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <opml version="2.0"><body>
                  <outline type="rss" text="明文源" xmlUrl="http://insecure.example.org/feed.xml" />
                  <outline type="rss" text="缺地址" />
                  <outline text="$longGroup">
                    <outline type="rss" text="组内源" xmlUrl="https://d.example.org/rss" />
                  </outline>
                </body></opml>
                """.trimIndent(),
            ),
        ).getOrThrow()

        val insecure = doc.entries.first { it.title == "明文源" }
        assertNotNull("非 HTTPS 应被标记", insecure.invalidReason)
        assertFalse(insecure.selectable)

        val inLongGroup = doc.entries.first { it.title == "组内源" }
        assertTrue("超 80 字符分组应被标记", inLongGroup.groupTooLong)
        assertFalse("超长分组不可直接导入（不能悄悄截断）", inLongGroup.selectable)
        assertTrue("分组名保留原样，没有截断", inLongGroup.group.length > Opml.MAX_GROUP_LENGTH)

        // 分组节点（无 xmlUrl）不会被当成订阅
        assertTrue(doc.entries.none { it.title == longGroup })
    }

    @Test
    fun marks_duplicates_against_remote_and_within_file() {
        val fileWithDup = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0"><body>
              <outline type="rss" text="已在订阅" xmlUrl="https://known.example.org/feed.xml" />
              <outline type="rss" text="写法不同但同一源" xmlUrl="HTTPS://Known.Example.ORG:443/feed.xml" />
              <outline type="rss" text="文件内重复" xmlUrl="https://dup.example.org/rss" />
              <outline type="rss" text="又一次" xmlUrl="https://dup.example.org/rss" />
            </body></opml>
        """.trimIndent()

        val doc = Opml.parse(
            bytes(fileWithDup),
            knownUrls = listOf("https://known.example.org/feed.xml"),
        ).getOrThrow()

        assertEquals("已在订阅中", doc.entries[0].duplicateOf)
        assertEquals("规范化后同一 URL 也算重复", "已在订阅中", doc.entries[1].duplicateOf)
        assertNull("文件内首条不重复", doc.entries[2].duplicateOf)
        assertEquals("文件内重复", doc.entries[3].duplicateOf)
        assertEquals("只有文件内首条是新源，可勾选", 1, doc.entries.count { it.selectable })
    }

    @Test
    fun conservative_normalization_does_not_merge_different_urls() {
        // 与后端同一套规则：不动 path/query，不重写编码
        assertEquals(
            EndpointPolicy.normalizeFeedUrl("https://a.example.org/feed.xml"),
            EndpointPolicy.normalizeFeedUrl("HTTPS://A.Example.ORG:443/feed.xml"),
        )
        assertTrue(
            "尾斜杠视为不同",
            EndpointPolicy.normalizeFeedUrl("https://a.example.org/feed.xml") !=
                EndpointPolicy.normalizeFeedUrl("https://a.example.org/feed.xml/"),
        )
        assertTrue(
            "带 query 视为不同",
            EndpointPolicy.normalizeFeedUrl("https://a.example.org/f") !=
                EndpointPolicy.normalizeFeedUrl("https://a.example.org/f?token=1"),
        )
        assertTrue(
            "http 不是有效订阅地址",
            EndpointPolicy.normalizeFeedUrl("http://a.example.org/f") == null,
        )
    }

    // ── 导出 ─────────────────────────────────────────────────────

    @Test
    fun export_escapes_xml_and_round_trips() {
        val feeds = listOf(
            Opml.ExportFeed(title = "A & B <技术>", xmlUrl = "https://a.example.org/f?a=1&b=2", group = "组 & 一"),
            Opml.ExportFeed(title = "无分组源", xmlUrl = "https://b.example.org/rss"),
        )
        val xml = Opml.write(feeds)

        assertTrue("应含 XML 声明", xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue("& 必须转义", xml.contains("A &amp; B &lt;技术&gt;"))
        assertTrue("URL 中的 & 也要转义", xml.contains("?a=1&amp;b=2"))
        assertTrue("分组名转义", xml.contains("组 &amp; 一"))
        assertFalse("导出不得包含任何密钥字段", xml.contains("secret", ignoreCase = true))

        // 导出的内容再导入应得到同样的源与分组
        val reparsed = Opml.parse(bytes(xml)).getOrThrow()
        assertEquals(2, reparsed.entries.size)
        // 导出按分组排序（空分组在前），因此按标题查找而不是按下标
        val roundTripped = reparsed.entries.first { it.title == "A & B <技术>" }
        assertEquals("组 & 一", roundTripped.group)
        assertEquals("https://a.example.org/f?a=1&b=2", roundTripped.xmlUrl)
        assertEquals("", reparsed.entries.first { it.title == "无分组源" }.group)
    }

    // ── 串行导入 ──────────────────────────────────────────────────

    /**
     * 按路径分发响应，而不是排固定队列：`FeedStore.add` 内部还会 refresh 一次列表，
     * 请求次数随实现变化，固定队列很容易错位（先前就是这么误判的）。
     */
    private fun serve(
        remoteUrls: List<String> = emptyList(),
        addBehavior: (String) -> MockResponse = { MockResponse().setResponseCode(200).setBody("""{"success":true}""") },
    ) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/feeds/list/admin") -> {
                        val body = remoteUrls.joinToString(",", "[", "]") {
                            """{"id":"x-${it.hashCode()}","url":"$it","category":"","customTitle":"","displayOrder":0}"""
                        }
                        MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json")
                    }
                    path.startsWith("/api/feeds/add") -> {
                        val body = request.body.readUtf8()
                        val url = Regex("\"url\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                        addBehavior(url)
                    }
                    path.startsWith("/api/feed?") -> MockResponse().setResponseCode(200).setBody("<rss/>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun api() = FeedApi(origin = server.url("").toString().trimEnd('/'), secret = secret)

    private fun importer(store: FeedStore, api: FeedApi = api()) = OpmlImporter(store)

    private fun makeStore(api: FeedApi = api()): FeedStore {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            dev.local.catleaf.nativeapp.data.local.CatLeafDatabase::class.java,
        ).build()
        return FeedStore(db, api, "opml-test-instance")
    }

    private fun apiWithTimeout(millis: Long) = FeedApi(
        origin = server.url("").toString().trimEnd('/'),
        secret = secret,
        clientFactory = { s ->
            dev.local.catleaf.nativeapp.data.network.HttpClients.forInstance(s)
                .newBuilder()
                .callTimeout(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        },
    )

    private fun docOf(vararg entries: Pair<String, String>, known: List<String> = emptyList()): List<Opml.Entry> {
        val body = entries.joinToString("\n") { (title, url) ->
            "<outline type=\"rss\" text=\"$title\" xmlUrl=\"$url\" />"
        }
        return Opml.parse(bytes(sample(body = body)), knownUrls = known).getOrThrow().entries
    }

    @Test
    fun serial_import_reports_added_failed_and_skipped_per_item() = runBlocking {
        serve(
            addBehavior = { url ->
                if (url.contains("bad")) {
                    MockResponse().setResponseCode(400).setBody("""{"error":"bad","code":"invalid_url"}""")
                } else {
                    MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                }
            },
        )

        val entries = docOf(
            "新源" to "https://new.example.org/rss",
            "坏源" to "https://bad.example.org/rss",
            "已有源" to "https://known.example.org/feed.xml",
            known = listOf("https://known.example.org/feed.xml"),
        )

        val report = importer(makeStore()).import(entries)

        assertEquals("共 3 条", 3, report.items.size)
        assertEquals("新增 1 条", 1, report.added)
        assertEquals("失败 1 条", 1, report.failed)
        assertEquals("已有源在解析阶段即被判为不可导入", 1,
            report.items.count { it.outcome is OpmlImporter.Outcome.SkippedInvalid })
        assertTrue(report.needsRetry)
        // 逐条结果能定位到具体条目
        assertTrue(report.items[0].outcome is OpmlImporter.Outcome.Added)
        assertEquals("坏源", report.items[1].title)
        assertTrue(report.items[1].outcome is OpmlImporter.Outcome.Failed)
    }

    @Test
    fun timeout_is_pending_confirm_not_failure_and_retry_reconciles() = runBlocking {
        // 真实超时：add 不回包 + 800ms 短超时 client。
        // 若用 DISCONNECT_AT_START 只会得到连接失败（服务端根本没收到），那不算“结果待确认”。
        serve(addBehavior = { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE) })
        val slow = apiWithTimeout(800)

        val entries = docOf("可能已写入" to "https://maybe.example.org/rss")
        val report = OpmlImporter(makeStore(slow)).import(entries)

        assertEquals("超时必须标为待确认，不能断言失败", 1, report.pending)
        assertEquals(0, report.failed)
        assertTrue(report.needsRetry)

        // 重试：服务端此时已有该源 → 关联为已存在，不重复新增
        serve(remoteUrls = listOf("https://maybe.example.org/rss"))
        val retried = importer(makeStore()).retryUnresolved(report)
        assertEquals(1, retried.exists)
        assertEquals(0, retried.pending)
        assertFalse(retried.needsRetry)
    }

    @Test
    fun cancel_stops_later_items_but_keeps_completed_ones() = runBlocking {
        serve()
        val entries = docOf(
            "第一条" to "https://one.example.org/rss",
            "第二条" to "https://two.example.org/rss",
            "第三条" to "https://three.example.org/rss",
        )

        var processed = 0
        val report = importer(makeStore()).import(
            entries,
            onProgress = { _, _, _ -> processed++ },
            isCancelled = { processed >= 1 },
        )

        assertEquals("第 1 条完成，其余 2 条标取消", 2, report.cancelled)
        assertTrue("已完成的第一条保留", report.items[0].outcome is OpmlImporter.Outcome.Added)
        assertEquals(1, report.added)
    }

    @Test
    fun repeated_url_within_file_is_not_added_twice() = runBlocking {
        serve()
        val entries = docOf(
            "同一源" to "https://same.example.org/rss",
            "同一源不同写法" to "HTTPS://Same.Example.ORG:443/rss",
        )

        val report = importer(makeStore()).import(entries)

        assertEquals("文件内重复只添加一次", 1, report.added)
        assertEquals(1, report.items.count { it.outcome is OpmlImporter.Outcome.SkippedInvalid })
    }

    @Test
    fun remote_unreadable_marks_everything_failed_without_writing() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setResponseCode(503).setBody("""{"error":"x","code":"not_configured"}""")
        }

        val entries = docOf("A" to "https://a.example.org/rss")
        val report = importer(makeStore()).import(entries)

        assertEquals(1, report.failed)
        assertEquals(0, report.added)
        assertEquals("读不到列表时不应发出写请求", 1, server.requestCount)
    }
}
