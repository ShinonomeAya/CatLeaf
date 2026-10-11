package dev.local.catleaf.nativeapp.data.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.rss.FeedParser
import dev.local.catleaf.nativeapp.data.rss.FeedXmlDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only confirmation of a previously pending public feed using the app parser. */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class CatalogPendingFeedProbeTest {
    @Test fun taoyinFeedParsesItems() {
        val response = PublicFetch().fetch("https://wangyurui.com/feed.xml").getOrThrow()
        val xml = FeedXmlDecoder.decode(response.bytes, response.contentType).getOrThrow()
        val feed = FeedParser.parse(xml).getOrThrow()
        assertTrue("订阅源应包含文章", feed.items.isNotEmpty())
    }
}
