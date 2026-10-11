package dev.local.catleaf.nativeapp.data.catalog

import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.network.HttpClients
import dev.local.catleaf.nativeapp.data.rss.FeedParser
import dev.local.catleaf.nativeapp.data.rss.FeedXmlDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Read-only captured responses and the user-specified public feed; isolated emulator only. */
@ManualProbe
class CatalogHttpsProbeTest {
    @Test fun captured_https_responses_use_the_app_parser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "catalog-https-20261009")
        val manifest = JSONArray(File(folder, "manifest.json").readText())
        val results = JSONArray()
        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i)
            val bytes = File(folder, entry.getString("response")).readBytes()
            val xml = FeedXmlDecoder.decode(bytes, entry.optString("contentType")).getOrThrow()
            val parsed = FeedParser.parse(xml, entry.getString("candidate"))
            if (entry.getBoolean("feedLike")) assertTrue(entry.getString("name"), parsed.getOrThrow().items.isNotEmpty())
            else assertTrue(entry.getString("name"), parsed.isFailure)
            results.put(JSONObject().put("name", entry.getString("name")).put("url", entry.getString("candidate"))
                .put("success", parsed.isSuccess).put("items", parsed.getOrNull()?.items?.size ?: 0)
                .put("error", parsed.exceptionOrNull()?.message.orEmpty()))
        }
        File(folder, "native-results.json").writeText(results.toString(2))
    }

    @Test fun juya_live_https_feed_parses_without_credentials() {
        val client = HttpClients.forPublic().newBuilder().eventListener(object : okhttp3.EventListener() {
            override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
                android.util.Log.i("CatalogHttpsProbe", "public DNS: $domainName; ${inetAddressList.map { it.hostAddress }}")
            }
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                android.util.Log.i("CatalogHttpsProbe", "public connect: $inetSocketAddress")
            }
            override fun connectFailed(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: java.io.IOException) {
                android.util.Log.i("CatalogHttpsProbe", "public connect failed: ${ioe.message}")
            }
            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
                android.util.Log.i("CatalogHttpsProbe", "public response: ${response.code}; ${response.protocol}; declared=${response.header("Content-Length")}; encoding=${response.header("Content-Encoding")}")
            }
            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                android.util.Log.i("CatalogHttpsProbe", "public read failed: ${ioe.javaClass.simpleName}: ${ioe.message}")
            }
        }).build()
        val url = InstrumentationRegistry.getArguments().getString("feedUrl") ?: "https://daily.juya.uk/rss.xml"
        val response = PublicFetch(client).fetch(url).getOrThrow()
        val xml = FeedXmlDecoder.decode(response.bytes, response.contentType).getOrThrow()
        assertTrue(FeedParser.parse(xml, url).getOrThrow().items.isNotEmpty())
    }
}
